package com.ipt.ged.cleapi;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.JournalAdministration;
import com.ipt.ged.cleapi.dto.DtoCleApi.ApplicationRequest;
import com.ipt.ged.cleapi.dto.DtoCleApi.ApplicationResponse;
import com.ipt.ged.cleapi.dto.DtoCleApi.CleGenereeResponse;
import com.ipt.ged.cleapi.dto.DtoCleApi.CleResponse;
import com.ipt.ged.cleapi.dto.DtoCleApi.GenerationRequest;
import com.ipt.ged.common.erreur.ConflitException;
import com.ipt.ged.common.erreur.RegleMetierException;
import com.ipt.ged.common.erreur.RequeteInvalideException;
import com.ipt.ged.common.erreur.RessourceIntrouvableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cycle de vie des applications et de leurs clés, piloté depuis
 * l'administration (DAT §5.4) : génération, consultation, expiration,
 * révocation, régénération avec chevauchement, adresses autorisées, quotas.
 *
 * <p>Chaque opération est tracée au journal d'audit. Les traces ne contiennent
 * jamais le secret ni son empreinte : seulement l'identifiant public.
 */
@Service
public class ServiceClesApi {

    private final ApplicationRepository applications;
    private final CleApiRepository cles;
    private final QuotasCleApi quotas;
    private final ProprietesCleApi proprietes;
    private final GardeAdministrationCles garde;
    private final JournalAdministration journal;
    private final Clock horloge;

    @Autowired
    public ServiceClesApi(ApplicationRepository applications, CleApiRepository cles, QuotasCleApi quotas,
                          ProprietesCleApi proprietes, GardeAdministrationCles garde, JournalAdministration journal) {
        this(applications, cles, quotas, proprietes, garde, journal, Clock.systemUTC());
    }

    ServiceClesApi(ApplicationRepository applications, CleApiRepository cles, QuotasCleApi quotas,
                   ProprietesCleApi proprietes, GardeAdministrationCles garde, JournalAdministration journal,
                   Clock horloge) {
        this.applications = applications;
        this.cles = cles;
        this.quotas = quotas;
        this.proprietes = proprietes;
        this.garde = garde;
        this.journal = journal;
        this.horloge = horloge;
    }

    /* ------------------------------------------------------------ applications */

    @Transactional(readOnly = true)
    public List<ApplicationResponse> lister() {
        garde.verifier();
        return applications.findAllByOrderByCodeAsc().stream().map(this::versReponse).toList();
    }

    @Transactional(readOnly = true)
    public ApplicationResponse consulter(UUID id) {
        garde.verifier();
        return versReponse(application(id));
    }

    @Transactional
    public ApplicationResponse creer(ApplicationRequest req) {
        garde.verifier();
        if (applications.existsByCode(req.code())) {
            throw new ConflitException(CodesErreurCleApi.APPLICATION_EXISTANTE,
                    "Le code d'application « " + req.code() + " » est déjà utilisé.");
        }
        Application a = new Application(req.code(), req.nom().trim());
        appliquer(a, req);
        a = applications.save(a);
        journal.cree(ActionAudit.APPLICATION_CREEE, "APPLICATION", a.getId(), descripteur(a));
        return versReponse(a);
    }

    @Transactional
    public ApplicationResponse modifier(UUID id, ApplicationRequest req) {
        garde.verifier();
        Application a = application(id);
        if (!a.getCode().equals(req.code())) {
            throw new RegleMetierException("CODE_APPLICATION_IMMUABLE",
                    "Le code d'une application ne change pas : il identifie ses traces d'audit.");
        }
        Map<String, Object> avant = descripteur(a);
        a.setNom(req.nom().trim());
        appliquer(a, req);
        a.setModifieLe(horloge.instant());
        journal.modifie(ActionAudit.APPLICATION_MODIFIEE, "APPLICATION", id, avant, descripteur(a));
        return versReponse(a);
    }

    @Transactional
    public ApplicationResponse activer(UUID id, boolean active) {
        garde.verifier();
        Application a = application(id);
        if (a.isActive() != active) {
            a.setActive(active);
            a.setModifieLe(horloge.instant());
            journal.action(active ? ActionAudit.APPLICATION_ACTIVEE : ActionAudit.APPLICATION_DESACTIVEE,
                    "APPLICATION", id);
        }
        return versReponse(a);
    }

    private void appliquer(Application a, ApplicationRequest req) {
        a.setDescription(req.description());
        List<String> adresses = req.adressesAutorisees() == null ? List.of()
                : req.adressesAutorisees().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        for (String adresse : adresses) {
            try {
                new IpAddressMatcher(adresse);
            } catch (IllegalArgumentException e) {
                throw new RequeteInvalideException("Adresse ou plage invalide : « " + adresse + " ».");
            }
        }
        if (adresses.isEmpty() && a.getId() != null && cles.findByApplicationIdOrderByCreeLeDesc(a.getId())
                .stream().anyMatch(c -> c.isDelegation() && c.utilisable(horloge.instant()))) {
            throw new RegleMetierException(CodesErreurCleApi.DELEGATION_SANS_ADRESSES,
                    "Une application dont une clé peut déléguer doit restreindre ses adresses (DAT §5.4).");
        }
        a.definirAdresses(adresses);
        a.setQuotaMinute(req.quotaMinute() != null ? req.quotaMinute() : proprietes.quotaMinute());
        a.setQuotaJour(req.quotaJour() != null ? req.quotaJour() : proprietes.quotaJour());
    }

    /* ------------------------------------------------------------------- clés */

    @Transactional
    public CleGenereeResponse generer(UUID applicationId, GenerationRequest req) {
        garde.verifier();
        Application a = application(applicationId);
        boolean delegation = req != null && req.delegation();
        if (delegation && a.adresses().isEmpty()) {
            throw new RegleMetierException(CodesErreurCleApi.DELEGATION_SANS_ADRESSES,
                    "Une clé habilitée à déléguer exige une liste d'adresses autorisées (DAT §5.4).");
        }
        Duration validite = req != null && req.validiteJours() != null
                ? Duration.ofDays(req.validiteJours()) : proprietes.validite();
        CleGeneree nouvelle = emettre(a, delegation, validite);
        journal.action(ActionAudit.CLE_API_GENEREE, "APPLICATION", a.getId(), null, traceCle(nouvelle.cle()));
        return new CleGenereeResponse(nouvelle.valeur(), versReponse(nouvelle.cle()));
    }

    /**
     * Régénération : nouvelle clé, même attribut de délégation ; l'ancienne reste
     * valide pendant le chevauchement (7 jours par défaut) pour que l'application
     * bascule sans interruption (DAT §5.4).
     */
    @Transactional
    public CleGenereeResponse regenerer(UUID cleId) {
        garde.verifier();
        CleApi ancienne = cle(cleId);
        Instant maintenant = horloge.instant();
        if (!ancienne.utilisable(maintenant) || ancienne.getRemplaceeParCleApiId() != null) {
            throw new RegleMetierException(CodesErreurCleApi.CLE_API_NON_REGENERABLE,
                    "Seule une clé en service, non encore remplacée, se régénère.");
        }
        CleGeneree nouvelle = emettre(ancienne.getApplication(), ancienne.isDelegation(), proprietes.validite());
        Instant finChevauchement = maintenant.plus(proprietes.chevauchement());
        if (finChevauchement.isBefore(ancienne.getExpireLe())) {
            ancienne.setExpireLe(finChevauchement);
        }
        ancienne.setRemplaceeParCleApiId(nouvelle.cle().getId());
        Map<String, Object> apres = traceCle(nouvelle.cle());
        apres.put("cleRemplacee", ancienne.getIdentifiant());
        apres.put("ancienneExpireLe", ancienne.getExpireLe().toString());
        journal.action(ActionAudit.CLE_API_REGENEREE, "APPLICATION", ancienne.getApplication().getId(),
                traceCle(ancienne), apres);
        return new CleGenereeResponse(nouvelle.valeur(), versReponse(nouvelle.cle()));
    }

    @Transactional
    public CleResponse revoquer(UUID cleId, String motif) {
        garde.verifier();
        CleApi c = cle(cleId);
        if (!c.revoquee()) {
            c.setRevoqueeLe(horloge.instant());
            c.setMotifRevocation(motif);
            quotas.oublier(c.getId());
            Map<String, Object> apres = traceCle(c);
            apres.put("motif", motif);
            journal.action(ActionAudit.CLE_API_REVOQUEE, "APPLICATION", c.getApplication().getId(), null, apres);
        }
        return versReponse(c);
    }

    private record CleGeneree(String valeur, CleApi cle) {}

    private CleGeneree emettre(Application a, boolean delegation, Duration validite) {
        FormatCleApi.CleGeneree g = FormatCleApi.generer(proprietes.environnement());
        Instant maintenant = horloge.instant();
        CleApi c = new CleApi();
        c.setApplication(a);
        c.setIdentifiant(g.identifiant());
        c.setEnvironnement(proprietes.environnement());
        c.setEmpreinte(g.empreinteSecret());
        c.setDelegation(delegation);
        c.setCreeLe(maintenant);
        c.setExpireLe(maintenant.plus(validite));
        return new CleGeneree(g.valeur(), cles.save(c));
    }

    /* ------------------------------------------------------------- lectures */

    private Application application(UUID id) {
        return applications.findById(id).orElseThrow(RessourceIntrouvableException::new);
    }

    private CleApi cle(UUID id) {
        return cles.findById(id).orElseThrow(RessourceIntrouvableException::new);
    }

    private ApplicationResponse versReponse(Application a) {
        List<CleResponse> sesCles = cles.findByApplicationIdOrderByCreeLeDesc(a.getId()).stream()
                .map(this::versReponse).toList();
        int bientot = (int) sesCles.stream().filter(CleResponse::expireBientot).count();
        return new ApplicationResponse(a.getId(), a.getCode(), a.getNom(), a.getDescription(), a.adresses(),
                a.getQuotaMinute(), a.getQuotaJour(), a.isActive(), a.getCreeLe(), a.getModifieLe(), sesCles, bientot);
    }

    CleResponse versReponse(CleApi c) {
        Instant maintenant = horloge.instant();
        String etat = c.revoquee() ? "REVOQUEE" : c.expiree(maintenant) ? "EXPIREE"
                : c.getRemplaceeParCleApiId() != null ? "EN_CHEVAUCHEMENT" : "ACTIVE";
        boolean bientot = c.utilisable(maintenant)
                && c.getExpireLe().isBefore(maintenant.plus(proprietes.alerteExpiration()));
        return new CleResponse(c.getId(), c.getIdentifiant(), c.getEnvironnement(), c.isDelegation(),
                c.getCreeLe(), c.getExpireLe(), c.getRevoqueeLe(), c.getMotifRevocation(),
                c.getRemplaceeParCleApiId(), quotas.derniereUtilisation(c), quotas.appelsDuJour(c), etat, bientot);
    }

    private static Map<String, Object> descripteur(Application a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", a.getCode());
        m.put("nom", a.getNom());
        m.put("description", a.getDescription());
        m.put("adressesAutorisees", a.adresses());
        m.put("quotaMinute", a.getQuotaMinute());
        m.put("quotaJour", a.getQuotaJour());
        m.put("active", a.isActive());
        return m;
    }

    private static Map<String, Object> traceCle(CleApi c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cle", c.getIdentifiant());
        m.put("environnement", c.getEnvironnement());
        m.put("delegation", c.isDelegation());
        m.put("expireLe", c.getExpireLe().toString());
        return m;
    }
}
