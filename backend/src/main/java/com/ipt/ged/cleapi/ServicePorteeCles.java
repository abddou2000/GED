package com.ipt.ged.cleapi;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.JournalAdministration;
import com.ipt.ged.autorisation.VersionHabilitations;
import com.ipt.ged.cleapi.dto.DtoCleApi.PorteeRequest;
import com.ipt.ged.cleapi.dto.DtoCleApi.PorteeResponse;
import com.ipt.ged.common.erreur.RegleMetierException;
import com.ipt.ged.common.erreur.RessourceIntrouvableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Portée des clés d'API (DAT §5.4, table {@code cle_api_portee}) : nœuds et
 * opérations permises à une clé. La portée est une source d'attributions du
 * modèle de droits ({@link SourceHabilitationsApplications}) : toute
 * modification incrémente {@code version_habilitations}, comme une
 * habilitation, et prend effet à la requête suivante.
 *
 * <p>Réservée à l'Administrateur ({@link GardeAdministrationCles}) ; chaque
 * modification est tracée (valeurs avant et après).
 */
@Service
public class ServicePorteeCles {

    private final CleApiRepository cles;
    private final PorteeCleApiRepository portees;
    private final GardeAdministrationCles garde;
    private final VersionHabilitations version;
    private final JournalAdministration journal;
    private final JdbcTemplate jdbc;

    public ServicePorteeCles(CleApiRepository cles, PorteeCleApiRepository portees, GardeAdministrationCles garde,
                             VersionHabilitations version, JournalAdministration journal, JdbcTemplate jdbc) {
        this.cles = cles;
        this.portees = portees;
        this.garde = garde;
        this.version = version;
        this.journal = journal;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<PorteeResponse> consulter(UUID cleId) {
        garde.verifier();
        cle(cleId);
        return reponses(portees.findByCleApiId(cleId));
    }

    /** Remplace toute la portée de la clé. Une portée vide ne donne accès à rien. */
    @Transactional
    public List<PorteeResponse> definir(UUID cleId, List<PorteeRequest> demande) {
        garde.verifier();
        CleApi c = cle(cleId);
        Set<UUID> vus = new HashSet<>();
        for (PorteeRequest p : demande) {
            if (!vus.add(p.noeudId())) {
                throw new RegleMetierException("PORTEE_NOEUD_EN_DOUBLE", "Un nœud ne figure qu'une fois dans la portée.");
            }
            Integer vivant = jdbc.queryForObject("SELECT count(*) FROM noeud WHERE id = ? AND NOT supprime",
                    Integer.class, p.noeudId());
            if (vivant == null || vivant == 0) {
                throw new RegleMetierException("PORTEE_NOEUD_INCONNU", "Espace ou dossier inconnu : " + p.noeudId());
            }
        }
        List<PorteeCleApi> actuelles = portees.findByCleApiId(cleId);
        List<PorteeResponse> avant = reponses(actuelles);
        portees.deleteAll(actuelles);
        portees.flush();
        List<PorteeCleApi> nouvelles = new ArrayList<>();
        for (PorteeRequest p : demande) {
            nouvelles.add(portees.save(new PorteeCleApi(cleId, p.noeudId(), EnumSet.copyOf(p.operations()))));
        }
        version.incrementer();
        List<PorteeResponse> apres = reponses(nouvelles);
        journal.modifie(ActionAudit.CLE_API_PORTEE_MODIFIEE, "APPLICATION", c.getApplication().getId(),
                trace(c, avant), trace(c, apres));
        return apres;
    }

    /** Régénération : la nouvelle clé reçoit la portée de celle qu'elle remplace. */
    @Transactional
    public void copier(UUID ancienne, UUID nouvelle) {
        for (PorteeCleApi p : portees.findByCleApiId(ancienne)) {
            portees.save(new PorteeCleApi(nouvelle, p.getNoeudId(), p.operationsAutorisees()));
        }
        version.incrementer();
    }

    private CleApi cle(UUID id) {
        return cles.findById(id).orElseThrow(RessourceIntrouvableException::new);
    }

    private List<PorteeResponse> reponses(List<PorteeCleApi> lignes) {
        Map<UUID, String> noms = new LinkedHashMap<>();
        for (PorteeCleApi p : lignes) {
            noms.put(p.getNoeudId(), jdbc.queryForList("SELECT nom FROM noeud WHERE id = ?", String.class,
                    p.getNoeudId()).stream().findFirst().orElse(null));
        }
        return lignes.stream()
                .map(p -> new PorteeResponse(p.getNoeudId(), noms.get(p.getNoeudId()), p.operationsAutorisees()))
                .toList();
    }

    private static Map<String, Object> trace(CleApi c, List<PorteeResponse> portee) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cle", c.getIdentifiant());
        m.put("portee", portee.stream().map(p -> p.noeudId() + ":" + p.operations()).toList());
        return m;
    }
}
