package com.ipt.ged.indexation;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.AuditService;
import com.ipt.ged.audit.EntreeAudit;
import com.ipt.ged.common.Limites;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.document.ContraintesDepot;
import com.ipt.ged.fichier.controle.ControleFichiers;
import com.ipt.ged.fichier.controle.SourceFichier;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.index.IndexRepository;
import com.ipt.ged.indexation.dto.*;
import com.ipt.ged.planindexation.CharteNommage;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.planindexation.JetonsSysteme;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.recherche.FragmentSql;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * Indexation & recherche par index.
 *
 * <p>Principe : les critères de recherche ne sont pas codés en dur, ils sont
 * <b>dérivés des index</b> cochés « indexé pour recherche ». Ajouter un index
 * suffit à faire apparaître un nouveau critère — c'est ce qui rend la recherche
 * automatique. Les index cochés « index de groupage » servent à regrouper les
 * résultats.
 */
@Service
@RequiredArgsConstructor
public class IndexationService {

    /**
     * Provenance du texte des propositions : toujours « aucune » depuis le
     * cloisonnement §4.3.3 (seul le nom de fichier propose des valeurs). Le
     * champ reste dans les réponses pour la compatibilité de l'écran.
     */
    private static final String PROVENANCE_AUCUNE = "AUCUNE";

    private final IndexRepository indexRepository;
    private final UploadDocumentRepository documentRepository;

    /** Écritures refusées sur un document verrouillé ou archivé (lot modèle, 409). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.ipt.ged.document.GardeEcriture garde;

    /** Miroir JSON des métadonnées du document (lot E7, §12.7). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.ipt.ged.document.modele.ServiceModeleDocument modele;
    private final DocumentIndexRepository valeurRepository;
    /** Le type porte le plan : l'apercu part du type, pas d'un document. */
    private final TypeDocumentRepository typeRepository;
    /** Journal d'audit : indexation enregistrée, valeurs avant et après (DAT §7.4.1). */
    private final AuditService audit;
    private final ControleFichiers controleFichiers;
    /** Point d'application unique des droits (lot E3). */
    private final com.ipt.ged.autorisation.AccessPredicate droits;

    /** Recherche multicritère filtrée en SQL (R32). */
    @org.springframework.beans.factory.annotation.Autowired
    private CriteresIndexSql criteresIndex;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /* ===================== Critères ===================== */

    /** Critères de recherche disponibles, dérivés des index. */
    public List<CritereResponse> criteres() {
        return indexRepository.findBySupprimeFalseOrderByIdAsc().stream()
                .filter(IndexField::isIndexePourRecherche)
                .map(IndexationService::versCritere)
                .toList();
    }

    /** Index servant au regroupement des résultats. */
    public List<CritereResponse> groupages() {
        return indexRepository.findBySupprimeFalseOrderByIdAsc().stream()
                .filter(IndexField::isIndexDeGroupage)
                .map(IndexationService::versCritere)
                .toList();
    }

    /** Champs à renseigner pour un document : ceux du plan d'indexation de son type. */
    public List<CritereResponse> champsDuDocument(UUID documentId) {
        UploadDocument doc = document(documentId);
        if (doc.getTypeDocument() == null || doc.getTypeDocument().getPlanIndexation() == null) {
            return List.of();
        }
        return doc.getTypeDocument().getPlanIndexation().getIndices().stream()
                .filter(i -> !i.isSupprime())
                .map(IndexationService::versCritere)
                .toList();
    }

    private static CritereResponse versCritere(IndexField f) {
        List<String> options = f.getFieldType() == IndexFieldType.LISTE && f.getValeurs() != null
                ? Arrays.stream(f.getValeurs().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : List.of();
        return new CritereResponse(f.getId(), f.getCode(), f.getNomIndex(),
                f.getFieldType().name(), options, f.isIndexDeGroupage(), f.isObligatoire());
    }

    /* ===================== Valeurs d'un document ===================== */

    public List<ResultatResponse.ValeurResponse> valeurs(UUID documentId) {
        document(documentId);
        return valeurRepository.findByDocumentIdOrderByIdAsc(documentId).stream()
                .map(IndexationService::versValeur)
                .toList();
    }

    /**
     * Enregistre (crée ou met à jour) les valeurs d'index d'un document.
     *
     * <p>Trois contrôles y étaient absents, tous exploitables par une simple
     * requête bien choisie :
     * <ol>
     *   <li>le document <b>verrouillé</b> n'était pas vérifié — l'écriture des
     *       index renomme le document, ce verrou était donc contournable par une
     *       autre porte que {@code PUT /documents/{id}} ;</li>
     *   <li>l'obligation d'un index n'était évaluée que sur les lignes
     *       <b>reçues</b> : ne pas envoyer un index obligatoire suffisait à s'en
     *       dispenser, et un corps {@code {"valeurs":[]}} passait entièrement au
     *       travers ;</li>
     *   <li>l'index était cherché dans toute la table, sans lien avec le plan du
     *       type : une valeur étrangère au plan se posait sur le document,
     *       invisible dans le formulaire, mais bien présente dans les résultats
     *       de recherche.</li>
     * </ol>
     */
    // Seule écriture du module.
    @Transactional
    public List<ResultatResponse.ValeurResponse> enregistrer(UUID documentId, ValeurRequest requete) {
        UploadDocument doc = document(documentId);
        if (doc.isSupprime()) {
            throw new IllegalArgumentException("Document en corbeille : indexation impossible. Restaurez-le d'abord.");
        }
        // Verrouillé ou archivé (§12.6, §12.8) : 409 DOCUMENT_VERROUILLE /
        // DOCUMENT_ARCHIVE, comme toute écriture sur le document.
        garde.exigerModifiable(documentId);

        // Le plan du type fait autorité : il dit ce qu'on a le droit d'écrire ET
        // ce qu'on est tenu de renseigner.
        List<IndexField> duPlan = champsDuPlan(doc);
        Map<UUID, IndexField> autorises = duPlan.stream()
                .collect(Collectors.toMap(IndexField::getId, f -> f, (a, b) -> a));

        Map<UUID, DocumentIndex> existantes = valeurRepository.findByDocumentIdOrderByIdAsc(documentId).stream()
                .collect(Collectors.toMap(v -> v.getIndexField().getId(), v -> v));

        // Première passe : on valide TOUT avant d'écrire quoi que ce soit. Une
        // validation entrelacée avec les écritures laissait un enregistrement
        // partiel derrière elle quand la ligne suivante était refusée.
        Map<UUID, String> recues = new LinkedHashMap<>();
        for (ValeurRequest.Ligne ligne : requete.valeurs()) {
            IndexField champ = autorises.get(ligne.indexFieldId());
            if (champ == null) {
                throw new IllegalArgumentException("L'index " + designer(ligne.indexFieldId())
                        + " n'appartient pas au plan d'indexation de ce type de document.");
            }
            String valeur = ligne.valeur() == null ? null : ligne.valeur().trim();
            controlerType(champ, valeur);
            recues.put(champ.getId(), valeur);
        }

        // Le contrôle « obligatoire » porte sur le plan, pas sur ce qui a été
        // envoyé : un index absent du corps est un index non renseigné.
        for (IndexField champ : duPlan) {
            if (!champ.isObligatoire()) continue;
            String valeur = recues.containsKey(champ.getId())
                    ? recues.get(champ.getId())
                    : existantes.containsKey(champ.getId()) ? existantes.get(champ.getId()).getValeur() : null;
            if (valeur == null || valeur.isBlank()) {
                throw new IllegalArgumentException("L'index « " + champ.getNomIndex() + " » est obligatoire.");
            }
        }

        // Valeurs avant et après, par nom d'index, pour le journal d'audit.
        Map<String, Object> avant = new LinkedHashMap<>();
        Map<String, Object> apres = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> ligne : recues.entrySet()) {
            DocumentIndex ancienne = existantes.get(ligne.getKey());
            String valeurAvant = ancienne != null ? ancienne.getValeur() : null;
            String valeurApres = ligne.getValue() == null || ligne.getValue().isEmpty() ? null : ligne.getValue();
            if (!java.util.Objects.equals(valeurAvant, valeurApres)) {
                String nom = autorises.get(ligne.getKey()).getNomIndex();
                avant.put(nom, valeurAvant);
                apres.put(nom, valeurApres);
            }
        }

        // Seconde passe : écriture, plus rien ne peut être refusé ici.
        for (Map.Entry<UUID, String> ligne : recues.entrySet()) {
            IndexField champ = autorises.get(ligne.getKey());
            String valeur = ligne.getValue();
            DocumentIndex cible = existantes.remove(champ.getId());
            if (valeur == null || valeur.isEmpty()) {
                if (cible != null) valeurRepository.delete(cible);   // valeur effacée
                continue;
            }
            if (cible == null) {
                valeurRepository.save(new DocumentIndex(doc, champ, valeur));
            } else {
                cible.setValeur(valeur);
                valeurRepository.save(cible);
            }
        }
        // Miroir JSON (§12.7) : les métadonnées du document, normalisées par
        // nature, suivent les valeurs d'index — recherche par critères et fiche.
        if (!recues.isEmpty()) {
            Map<String, Object> parCode = new LinkedHashMap<>();
            valeurRepository.findByDocumentIdOrderByIdAsc(documentId)
                    .forEach(v -> parCode.put(v.getIndexField().getCode(), v.getValeur()));
            modele.synchroniser(doc, parCode);
        }
        // Plan satisfait (obligatoires compris) : issue INDEXE, y compris
        // pour la reprise d'un dépôt resté A_INDEXER (§12.11).
        if (doc.getTypeDocument() != null && doc.getTypeDocument().getPlanIndexation() != null) {
            doc.setStatutIndexation(com.ipt.ged.depot.IssueIndexation.INDEXE);
        }

        /* La confirmation de l'opérateur vaut validation : c'est ici, et nulle
           part avant, que la référence du document est composée depuis le plan.
           Mais seulement si cette requête a effectivement écrit : recomposer
           après un corps vide remplaçait le nom du document par les seuls jetons
           système suivis d'interrogations — « 260811_105301_?_? » — c'est-à-dire
           détruisait le nom sans qu'aucune donnée n'ait été fournie. */
        if (!recues.isEmpty()) {
            String nomAvant = doc.getName();
            recomposerReference(doc);
            if (!java.util.Objects.equals(nomAvant, doc.getName())) {
                avant.put("nom du document", nomAvant);
                apres.put("nom du document", doc.getName());
            }
        }
        if (!avant.isEmpty() || !apres.isEmpty()) {
            audit.enregistrer(EntreeAudit.de(ActionAudit.INDEXATION_ENREGISTREE, "DOCUMENT", documentId)
                    .avecAvantApres(avant, apres));
        }
        return valeurs(documentId);
    }

    /** Index du plan du type de ce document, corbeille exclue ; vide s'il n'y a pas de plan. */
    private List<IndexField> champsDuPlan(UploadDocument doc) {
        PlanIndexation plan = doc.getTypeDocument() != null ? doc.getTypeDocument().getPlanIndexation() : null;
        if (plan == null) return List.of();
        return plan.getIndices().stream().filter(i -> !i.isSupprime()).toList();
    }

    /** Désigne un index refusé par son nom quand il existe, par son identifiant sinon. */
    private String designer(UUID indexFieldId) {
        return indexRepository.findById(indexFieldId)
                .map(f -> "« " + f.getNomIndex() + " »")
                .orElse("#" + indexFieldId);
    }

    /** Recompose la référence du document à partir des valeurs désormais enregistrées. */
    private void recomposerReference(UploadDocument doc) {
        PlanIndexation plan = doc.getTypeDocument() != null
                ? doc.getTypeDocument().getPlanIndexation() : null;
        if (plan == null) return;

        Map<UUID, String> valeurs = valeurRepository.findByDocumentIdOrderByIdAsc(doc.getId()).stream()
                .collect(Collectors.toMap(v -> v.getIndexField().getId(), DocumentIndex::getValeur, (a, b) -> a));

        // La charte décide quels jetons composent le nom et dans quel ordre ; à
        // défaut (plan hérité), on retombe sur les index du plan.
        List<String> jetons = jetonsDuPlan(plan);

        String reference = composerDepuisCharte(plan, jetons, valeurs);
        if (plan.isManuel()) controlerReferenceManuelle(reference);
        doc.setReference(reference);

        // Charte automatique : le nom du document EST la référence composée,
        // comme dans l'original où le champ est alors en lecture seule. En mode
        // manuel, le nom saisi par l'opérateur reste intact.
        if (!plan.isManuel() && reference != null && !reference.isBlank()) {
            // La référence est composée de valeurs libres : rien ne garantit
            // qu'elle tienne dans la colonne. Sans ce contrôle, une valeur
            // d'index un peu longue faisait sortir un 500 nu au moment de la
            // sauvegarde, bien après la saisie.
            Limites.controler(reference, "nom composé du document");
            doc.setName(reference);
        }
        documentRepository.save(doc);
    }

    /**
     * Charte manuelle : le nom saisi reste intact, mais la référence composée
     * est tout de même enregistrée, dans une colonne de {@value Limites#TEXTE}
     * caractères. Sans ce contrôle (ANO-E6-002), une valeur d'index longue
     * n'était refusée que par la base : au dépôt, le texte brut de l'erreur SQL
     * remontait dans {@code motifIndexation}. Le refus garde le code que la
     * saisie par l'écran d'indexation renvoyait déjà ({@code DONNEE_REFUSEE},
     * 400), avec un message qui nomme le champ et sa limite.
     */
    private static void controlerReferenceManuelle(String reference) {
        if (reference != null && reference.length() > Limites.TEXTE) {
            throw new com.ipt.ged.common.erreur.RequeteInvalideException(
                    com.ipt.ged.common.erreur.CodesErreur.DONNEE_REFUSEE,
                    "Le champ « référence composée du document » ne peut pas dépasser " + Limites.TEXTE
                            + " caractères (reçu : " + reference.length()
                            + ") : raccourcissez les valeurs d'index qui la composent.");
        }
    }

    /** Identifiant d'index porté par un jeton, ou null si ce n'en est pas un. */
    private static UUID idOuNull(String jeton) {
        if (jeton == null) return null;
        try {
            return UUID.fromString(jeton);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Date a separateur : AAAA-MM-JJ, JJ/MM/AAAA, 28.08.2026... L'annee est
        reconnue a sa longueur, ce qui evite d'inverser jour et mois. */
    private static final java.util.regex.Pattern DATE_SEPAREE =
            java.util.regex.Pattern.compile("(\\d{2,4})[-/.](\\d{1,2})[-/.](\\d{2,4})");

    /** Refuse une valeur incompatible avec le type de l'index. */
    private void controlerType(IndexField champ, String valeur) {
        // Règle partagée avec le dépôt avec métadonnées (§5.3), qui valide
        // avant toute écriture.
        ValidationPlan.controlerType(champ, valeur);
    }

    /* ===================== Indexation automatique ===================== */

    /**
     * Analyse un document et <b>propose</b> ses valeurs d'index, sans rien écrire.
     *
     * <p>Le nom du fichier est découpé avec le séparateur du plan ; le n-ième
     * segment alimente le n-ième index du plan. Chaque segment est confronté au
     * type de l'index : ce qui ne passe pas est signalé plutôt que deviné.
     * L'enregistrement reste à la main de l'opérateur ({@link #enregistrer}).
     */
    /**
     * Aperçu des index déduits d'un NOM DE FICHIER, avant tout dépôt.
     *
     * <p>Le formulaire de dépôt s'en sert pour montrer les champs indexés dès
     * que l'opérateur choisit son fichier, sans attendre le téléversement. Les
     * règles — séparateur du plan, ordre des index, contrôle de type,
     * normalisation des dates — sont celles de {@link #analyser} : les
     * réimplémenter côté navigateur les ferait diverger au premier changement.</p>
     *
     * <p>Le contenu du document n'est pas lu : il n'existe pas encore côté
     * serveur. Ce qui n'est pas dans le nom du fichier reste donc à saisir, et
     * la lecture du document prend le relais après le dépôt.</p>
     */
    @Transactional(readOnly = true)
    public ApercuResponse apercu(UUID typeDocumentId, String nomFichier, MultipartFile fichier) {
        TypeDocument type = typeRepository.findById(typeDocumentId)
                .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + typeDocumentId));

        /* Mêmes contraintes que le dépôt qu'il précède. Cet aperçu recevait
           n'importe quel fichier, de n'importe quelle taille : un exécutable de
           50 Mo y passait en 200 alors que le dépôt suivant l'aurait refusé — et
           l'écran appelle cette route à CHAQUE sélection de fichier. Le serveur
           acceptait donc de recopier puis de tenter de lire, en boucle, des
           contenus qu'il n'accepterait jamais.
           Le format est vérifié même sans contenu : c'est le nom du fichier qui
           décide, et il est toujours là. */
        ContraintesDepot.validerTypeVivant(type);
        ContraintesDepot.validerFormat(type, ContraintesDepot.extension(nomFichier));
        // Même refus que le dépôt qu'il précède (taille 413, type réel 415) ;
        // pas d'antivirus : le fichier n'est ni conservé ni ouvert ici.
        if (fichier != null && !fichier.isEmpty()) {
            controleFichiers.verifierTailleEtType(SourceFichier.de(fichier),
                    controleFichiers.regles(type.getTailleMaxMo(), type.formatsAutorises()));
        }

        PlanIndexation plan = type.getPlanIndexation();
        if (plan == null) return ApercuResponse.sansPlan();

        List<IndexField> champs = plan.getIndices().stream().filter(i -> !i.isSupprime()).toList();
        if (champs.isEmpty()) return ApercuResponse.sansPlan();

        String sep = plan.getSeparateur() == null || plan.getSeparateur().isEmpty() ? "_" : plan.getSeparateur();
        List<String> segments = decouper(nomFichier, sep);

        /* Cloisonnement du §4.3.3 : « les champs d'indexation du formulaire de
           dépôt ne sont en aucun cas alimentés par le contenu extrait de
           l'OCR ». Seul le nom de fichier, saisi par l'opérateur, propose des
           valeurs ; le contenu ne sert qu'à la recherche plein texte (lot E6). */
        List<AnalyseResponse.Proposition> propositions = new ArrayList<>();
        for (int i = 0; i < champs.size(); i++) {
            IndexField champ = champs.get(i);
            String segment = segmentPour(champ, segments, i);
            String motif = motifDeRejet(champ, segment, segments.size(), i);
            String valeur = motif == null ? segment : null;
            String source = valeur != null ? "NOM_FICHIER" : null;

            propositions.add(new AnalyseResponse.Proposition(
                    champ.getId(), champ.getCode(), champ.getNomIndex(), champ.getFieldType().name(),
                    versCritere(champ).options(),
                    valeur, source,
                    null, valeur != null, valeur != null ? null : motif));
        }

        int reconnus = (int) propositions.stream().filter(AnalyseResponse.Proposition::reconnue).count();

        /* Nom que porterait le document s'il était déposé maintenant. Il n'est
           calculé que si la charte est automatique : en mode manuel, le nom
           appartient à l'opérateur et lui proposer une composition serait
           trompeur. */
        String nomPropose = null;
        if (!plan.isManuel()) {
            Map<UUID, String> valeursIndex = propositions.stream()
                    .filter(pr -> pr.valeurProposee() != null && !pr.valeurProposee().isBlank())
                    .collect(Collectors.toMap(AnalyseResponse.Proposition::indexFieldId,
                                              AnalyseResponse.Proposition::valeurProposee, (a, b) -> a));
            nomPropose = composerDepuisCharte(plan, jetonsDuPlan(plan), valeursIndex);
        }

        /* Même formulation que l'analyse d'un document déposé : l'opérateur doit
           lire la même phrase pour le même fait, qu'il soit avant ou après le
           dépôt. Le calcul est partagé pour qu'ils ne puissent pas diverger. */
        String avertissement = avertissementDeLecture(reconnus, champs, sep);

        return new ApercuResponse(plan.getNomDuPlan(), sep, propositions, reconnus, champs.size(),
                nomPropose, avertissement, PROVENANCE_AUCUNE);
    }

    /**
     * Ce que la lecture n'a pas su conclure, dit à l'opérateur.
     *
     * <p>Partagé entre l'aperçu (avant dépôt) et l'analyse (après) : deux
     * formulations pour le même constat auraient laissé croire à deux
     * comportements différents.</p>
     */
    private String avertissementDeLecture(int reconnus, List<IndexField> champs, String sep) {
        if (reconnus == champs.size()) return null;
        if (reconnus == 0) {
            return "Ni le nom du fichier (charte « "
                    + champs.stream().map(IndexField::getNomIndex).collect(Collectors.joining(sep))
                    + " ») ni le contenu n'ont permis de conclure : renseignez les champs à la main.";
        }
        return (champs.size() - reconnus)
                + " champ(s) n'ont pas pu être déduits : complétez-les avant de confirmer.";
    }

    public AnalyseResponse analyser(UUID documentId) {
        UploadDocument doc = document(documentId);
        String fichier = doc.getFileName() != null ? doc.getFileName() : doc.getName();

        PlanIndexation plan = doc.getTypeDocument() != null
                ? doc.getTypeDocument().getPlanIndexation() : null;
        if (plan == null) {
            return vide(doc, fichier, "Le type « "
                    + (doc.getTypeDocument() != null ? doc.getTypeDocument().getTypeDeDocument() : "?")
                    + " » n'a pas de plan d'indexation : aucune analyse possible.");
        }

        List<IndexField> champs = plan.getIndices().stream().filter(i -> !i.isSupprime()).toList();
        if (champs.isEmpty()) {
            return vide(doc, fichier, "Le plan « " + plan.getNomDuPlan() + " » ne contient aucun index.");
        }

        Map<UUID, String> actuelles = valeurRepository.findByDocumentIdOrderByIdAsc(documentId).stream()
                .collect(Collectors.toMap(v -> v.getIndexField().getId(), DocumentIndex::getValeur, (a, b) -> a));

        String sep = plan.getSeparateur() == null || plan.getSeparateur().isEmpty() ? "_" : plan.getSeparateur();
        List<String> segments = decouper(fichier, sep);

        // Cloisonnement du §4.3.3 : aucune valeur d'index n'est déduite du contenu.
        List<AnalyseResponse.Proposition> propositions = new ArrayList<>();
        for (int i = 0; i < champs.size(); i++) {
            IndexField champ = champs.get(i);
            String segment = segmentPour(champ, segments, i);
            String motif = motifDeRejet(champ, segment, segments.size(), i);

            String valeur = motif == null ? segment : null;
            String source = valeur != null ? "NOM_FICHIER" : null;

            propositions.add(new AnalyseResponse.Proposition(
                    champ.getId(), champ.getCode(), champ.getNomIndex(), champ.getFieldType().name(),
                    versCritere(champ).options(),
                    valeur, source,
                    actuelles.get(champ.getId()),
                    valeur != null,
                    valeur != null ? null : motif));
        }

        int reconnus = (int) propositions.stream().filter(AnalyseResponse.Proposition::reconnue).count();
        String reference = composerReference(
                propositions.stream().map(AnalyseResponse.Proposition::valeurProposee).toList(), plan);

        String avertissement = avertissementDeLecture(reconnus, champs, sep);

        return new AnalyseResponse(doc.getId(), fichier, plan.getNomDuPlan(), sep, plan.isMajuscule(),
                plan.isModeIndexation(), segments, propositions, reference,
                reconnus, champs.size(), avertissement,
                PROVENANCE_AUCUNE,
                "Le contenu du document n'alimente aucun champ d'index (dossier technique §4.3.3).");
    }

    /**
     * Le n-ième segment du nom de fichier, prêt à être confronté au type de
     * l'index. Pour un index DATE, le segment est d'abord ramené en AAAA-MM-JJ :
     * un nom de fichier porte rarement une date au format ISO, et exiger
     * « 2026-08-28 » alors que la lecture du contenu accepte « 28/08/2026 »
     * était une asymétrie sans justification — la même date remontait ou non
     * selon l'endroit où on la lisait.
     */
    private String segmentPour(IndexField champ, List<String> segments, int position) {
        String segment = position < segments.size() ? segments.get(position).trim() : null;
        if (segment == null || champ.getFieldType() != IndexFieldType.DATE) return segment;
        String iso = normaliserDate(segment);
        return iso != null ? iso : segment;
    }

    /**
     * Ramène en AAAA-MM-JJ les écritures de date usuelles dans un nom de
     * fichier : {@code 2026-08-28}, {@code 2026/08/28}, {@code 28/08/2026},
     * {@code 28-08-2026}, {@code 28.08.2026}, {@code 20260828}, {@code 28082026}.
     *
     * <p>Les formes à année sur DEUX chiffres sont volontairement refusées.
     * « 260805 » se lit aussi bien 2026-08-05 que 2005-08-26, et rien dans le
     * nom ne permet de trancher : écrire une date fausse dans un index est plus
     * grave que de la demander à l'opérateur, qui a le document sous les yeux.
     *
     * @return la date ISO, ou {@code null} si le segment n'est pas une date
     *         reconnaissable sans deviner.
     */
    static String normaliserDate(String segment) {
        if (segment == null) return null;
        String s = segment.strip();

        // Séparateurs explicites : l'ordre est donné par la position de l'année.
        Matcher m = DATE_SEPAREE.matcher(s);
        if (m.matches()) {
            String a = m.group(1), b = m.group(2), c = m.group(3);
            return a.length() == 4 ? valider(a, b, c) : valider(c, b, a);
        }

        // Huit chiffres collés : AAAAMMJJ si les quatre premiers font une année
        // plausible, JJMMAAAA si ce sont les quatre derniers.
        if (s.matches("\\d{8}")) {
            String tete = s.substring(0, 4), queue = s.substring(4);
            if (estAnnee(tete)) return valider(tete, s.substring(4, 6), s.substring(6));
            if (estAnnee(queue)) return valider(queue, s.substring(2, 4), s.substring(0, 2));
        }
        return null;
    }

    /** Année plausible pour un document : ni un jour, ni un montant. */
    private static boolean estAnnee(String quatreChiffres) {
        int an = Integer.parseInt(quatreChiffres);
        return an >= 1900 && an <= 2100;
    }

    /** Assemble et vérifie : un 31 février doit être refusé, pas normalisé. */
    private static String valider(String annee, String mois, String jour) {
        String iso = "%s-%s-%s".formatted(annee, mois, jour);
        try {
            LocalDate.parse(iso);
            return iso;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private AnalyseResponse vide(UploadDocument doc, String fichier, String avertissement) {
        return new AnalyseResponse(doc.getId(), fichier, null, "_", false, false,
                List.of(), List.of(), null, 0, 0, avertissement,
                PROVENANCE_AUCUNE, null);
    }

    /** Retire l'extension puis découpe sur le séparateur du plan. */
    private static List<String> decouper(String fichier, String separateur) {
        String base = fichier == null ? "" : fichier;
        int point = base.lastIndexOf('.');
        if (point > 0) base = base.substring(0, point);
        if (base.isBlank()) return List.of();
        return Arrays.stream(base.split(java.util.regex.Pattern.quote(separateur)))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** null si le segment est exploitable pour ce champ, sinon la raison du rejet. */
    private String motifDeRejet(IndexField champ, String segment, int nbSegments, int position) {
        if (segment == null || segment.isBlank()) {
            return "Le nom du fichier ne compte que " + nbSegments + " segment(s) : rien en position " + (position + 1) + ".";
        }
        try {
            controlerType(champ, segment);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * Compose le nom depuis la charte : jetons système (date, heure…) résolus à
     * l'instant présent, jetons d'index remplacés par leur valeur.
     *
     * <p>Partagée entre l'enregistrement — qui la grave sur le document — et
     * l'aperçu, qui la montre AVANT le dépôt. Les deux doivent produire la même
     * chaîne, sans quoi l'opérateur verrait un nom puis en obtiendrait un
     * autre.</p>
     *
     * @return la référence, ou {@code null} si aucun jeton n'a de valeur.
     */
    private String composerDepuisCharte(PlanIndexation plan, List<String> jetons, Map<UUID, String> valeurs) {
        java.time.LocalDateTime maintenant = java.time.LocalDateTime.now();
        List<String> ordonnees = jetons.stream()
                .map(j -> {
                    String systeme = JetonsSysteme.valeur(j, maintenant);
                    if (systeme != null) return systeme;
                    UUID idIndex = idOuNull(j);
                    return idIndex == null ? null : valeurs.get(idIndex);
                })
                .toList();

        return ordonnees.stream().allMatch(v -> v == null || v.isBlank())
                ? null : composerReference(ordonnees, plan);
    }

    /** Jetons de la charte, ou à défaut les index du plan dans leur ordre. */
    private List<String> jetonsDuPlan(PlanIndexation plan) {
        List<String> jetons = CharteNommage.jetons(plan.getCharteNommage());
        if (!jetons.isEmpty()) return jetons;
        return plan.getIndices().stream()
                .filter(i -> !i.isSupprime())
                .map(i -> String.valueOf(i.getId()))
                .toList();
    }

    /**
     * Compose la référence du document : valeurs des index dans l'ordre du plan,
     * jointes par le séparateur, en majuscules si la charte l'exige.
     *
     * <p>Un jeton sans valeur est <b>omis</b>. Il produisait auparavant un
     * « ? » littéral, qui finissait dans le nom du document puis, tel quel, dans
     * l'en-tête {@code Content-Disposition} du téléchargement : caractère
     * interdit dans un nom de fichier sous Windows et réservé en URL, il rendait
     * la pièce inenregistrable côté poste de travail. Omettre plutôt que
     * marquer garde le nom composé exploitable — ce qui manque se voit à
     * l'écran d'indexation, qui est fait pour cela, pas dans un nom de fichier.
     */
    private static String composerReference(List<String> valeurs, PlanIndexation plan) {
        String sep = plan.getSeparateur() == null || plan.getSeparateur().isEmpty() ? "_" : plan.getSeparateur();
        String joint = valeurs.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(sep));
        return joint.isBlank() ? null : (plan.isMajuscule() ? joint.toUpperCase() : joint);
    }

    /* ===================== Recherche ===================== */

    /** Libellé du groupe des documents sans valeur pour l'index de groupage. */
    static final String NON_RENSEIGNE = "(non renseigné)";

    /**
     * Recherche multi-critères, <b>paginée</b> (50 par défaut, plafond 200, DAT
     * §5.3.2) : un document est retenu s'il satisfait <b>tous</b> les filtres
     * renseignés (ET logique).
     *
     * <p>La page porte sur les documents ; avec un index de groupage, ils sont
     * triés par valeur de groupe (ordre binaire, « (non renseigné) » compris),
     * puis du plus récent au plus ancien, et regroupés sur la page : un groupe
     * peut ainsi se poursuivre sur la page suivante. Le {@code total} d'un groupe
     * compte tous ses documents, pas seulement ceux de la page ; celui de la page
     * compte tous les documents retenus.
     */
    public PageResponse<GroupeResponse> rechercher(RechercheRequest requete) {
        int page = requete.page() == null ? 0 : Math.max(0, requete.page());
        int taille = requete.tailleDemandee();
        Map<UUID, IndexField> champs = indexRepository.findBySupprimeFalseOrderByIdAsc().stream()
                .collect(Collectors.toMap(IndexField::getId, f -> f));
        UUID grouperPar = requete.grouperPar() != null && champs.containsKey(requete.grouperPar())
                ? requete.grouperPar() : null;

        // Filtres, critères d'index et droits évalués par la base (R32) ; seule la page
        // est lue. Droits À LA SOURCE (point d'application unique, P5) : un document hors
        // périmètre n'entre ni dans les résultats ni dans les totaux.
        MapSqlParameterSource p = new MapSqlParameterSource();
        String ou = conditionsRecherche(requete, p);
        NamedParameterJdbcTemplate nomme = new NamedParameterJdbcTemplate(jdbc);
        Long total = nomme.queryForObject("SELECT count(*) FROM document d WHERE " + ou, p, Long.class);
        long n = total == null ? 0 : total;
        int pages = (int) ((n + taille - 1) / taille);
        if (n == 0) return new PageResponse<>(List.of(), 0, page, taille, 0);

        // Clé de groupe calculée par la base : la page suit l'ordre des groupes.
        String groupe = grouperPar == null ? "NULL" : "CASE WHEN g.valeur IS NULL OR btrim(g.valeur, E' \\t\\r\\n') = '' "
                + "THEN '" + NON_RENSEIGNE + "' ELSE g.valeur END";
        String jointureGroupe = grouperPar == null ? "" : " LEFT JOIN document_index_valeur g"
                + " ON g.document_id = d.id AND g.index_def_id = :recherche_groupe";
        if (grouperPar != null) p.addValue("recherche_groupe", grouperPar);
        p.addValue("recherche_taille", taille).addValue("recherche_decalage", (long) page * taille);
        List<LigneRecherche> retenus = nomme.query("SELECT d.id, d.name, d.extension, d.size_ko, w.nom AS espace, "
                        + "t.type_de_document, d.expiration_date, d.reference, d.statut_conservation, " + groupe
                        + " AS groupe FROM document d LEFT JOIN noeud w ON w.id = d.noeud_principal_id "
                        + "LEFT JOIN type_document t ON t.id = d.type_document_id" + jointureGroupe + " WHERE " + ou
                        // Ordre binaire (celui de String.compareTo), indépendant de la collation de la base.
                        + " ORDER BY " + (grouperPar == null ? "" : "(" + groupe + ") COLLATE \"C\", ")
                        + "d.id DESC LIMIT :recherche_taille OFFSET :recherche_decalage", p,
                (rs, i) -> new LigneRecherche(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("extension"),
                        rs.getLong("size_ko"), rs.getString("espace"), rs.getString("type_de_document"),
                        rs.getObject("expiration_date", java.time.LocalDate.class), rs.getString("reference"),
                        rs.getString("statut_conservation"), rs.getString("groupe")));
        if (retenus.isEmpty()) return new PageResponse<>(List.of(), n, page, taille, pages);

        Map<UUID, Map<UUID, String>> parDocument = new HashMap<>();
        jdbc.query("SELECT document_id, index_def_id, valeur FROM document_index_valeur WHERE document_id = ANY (?)",
                rs -> {
                    parDocument.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>())
                            .put(rs.getObject(2, UUID.class), rs.getString(3));
                }, (Object) retenus.stream().map(LigneRecherche::id).toArray(UUID[]::new));

        // Effectif de chaque groupe de la page sur l'ensemble des documents retenus.
        Map<String, Integer> effectifs = new HashMap<>();
        if (grouperPar == null) {
            effectifs.put(null, (int) n);
        } else {
            p.addValue("recherche_cles", retenus.stream().map(LigneRecherche::groupe).distinct().toArray(String[]::new));
            nomme.query("SELECT groupe, count(*) FROM (SELECT " + groupe + " AS groupe FROM document d" + jointureGroupe
                    + " WHERE " + ou + ") s WHERE groupe = ANY (:recherche_cles) GROUP BY groupe", p,
                    rs -> { effectifs.put(rs.getString(1), rs.getInt(2)); });
        }

        Map<String, List<ResultatResponse>> groupes = new LinkedHashMap<>();
        for (LigneRecherche d : retenus) {
            groupes.computeIfAbsent(d.groupe(), k -> new ArrayList<>()).add(versResultat(d, parDocument, champs));
        }
        List<GroupeResponse> contenu = groupes.entrySet().stream()
                .map(e -> new GroupeResponse(e.getKey() == null ? "Tous" : e.getKey(),
                        effectifs.getOrDefault(e.getKey(), e.getValue().size()), e.getValue()))
                .toList();
        return new PageResponse<>(contenu, n, page, taille, pages);
    }

    /** Colonnes d'un document retenu, lues en SQL ; {@code groupe} : valeur de l'index de groupage. */
    private record LigneRecherche(UUID id, String name, String extension, long sizeKo, String workspace,
                                  String typeDocument, java.time.LocalDate expirationDate, String reference,
                                  String statutConservation, String groupe) {
    }

    /**
     * Conditions SQL (alias {@code d}) des documents vivants, visibles, qui
     * satisfont la requête ; leurs paramètres sont ajoutés à {@code p}.
     */
    private String conditionsRecherche(RechercheRequest requete, MapSqlParameterSource p) {
        List<FragmentSql> fragments = new ArrayList<>();
        fragments.add(droits.predicatSql("d.id", org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication(), com.ipt.ged.autorisation.CodePermission.CONSULTER));
        if (requete.workspaceId() != null) {
            // Emplacement principal, comme la recherche historique.
            fragments.add(new FragmentSql("d.noeud_principal_id = :recherche_espace",
                    Map.of("recherche_espace", requete.workspaceId())));
        }
        if (requete.typeDocumentId() != null) {
            fragments.add(new FragmentSql("d.type_document_id = :recherche_type",
                    Map.of("recherche_type", requete.typeDocumentId())));
        }
        // Archivés inclus par défaut, filtre pour les exclure ou ne garder qu'eux (§12.6).
        if ("EXCLURE".equals(requete.archives())) {
            fragments.add(new FragmentSql("d.statut_conservation <> 'ARCHIVE'", Map.of()));
        } else if ("SEULEMENT".equals(requete.archives())) {
            fragments.add(new FragmentSql("d.statut_conservation = 'ARCHIVE'", Map.of()));
        }
        if (requete.canal() != null && !requete.canal().isBlank()) {
            fragments.add(new FragmentSql("upper(d.canal_depot) = :recherche_canal",
                    Map.of("recherche_canal", requete.canal().trim().toUpperCase(java.util.Locale.ROOT))));
        }
        fragments.addAll(criteresIndex.fragments(requete.criteres()));

        StringBuilder ou = new StringBuilder("NOT d.supprime");
        for (FragmentSql f : fragments) {
            ou.append(" AND (").append(f.sql()).append(')');
            f.parametres().forEach(p::addValue);
        }
        return ou.toString();
    }

    /* ===================== Regroupement ===================== */

    private static ResultatResponse versResultat(LigneRecherche d,
                                                 Map<UUID, Map<UUID, String>> parDocument,
                                                 Map<UUID, IndexField> champs) {
        List<ResultatResponse.ValeurResponse> valeurs = parDocument.getOrDefault(d.id(), Map.of())
                .entrySet().stream()
                .filter(e -> champs.containsKey(e.getKey()))
                .map(e -> {
                    IndexField f = champs.get(e.getKey());
                    return new ResultatResponse.ValeurResponse(f.getId(), f.getCode(), f.getNomIndex(), e.getValue());
                })
                .sorted(Comparator.comparing(ResultatResponse.ValeurResponse::libelle))
                .toList();

        return new ResultatResponse(
                d.id(), d.name(), d.extension(), humanSize(d.sizeKo()),
                d.workspace(),
                d.typeDocument(),
                d.expirationDate() != null ? d.expirationDate().toString() : null,
                d.reference(),
                valeurs,
                d.statutConservation());
    }

    private static ResultatResponse.ValeurResponse versValeur(DocumentIndex v) {
        return new ResultatResponse.ValeurResponse(
                v.getIndexField().getId(), v.getIndexField().getCode(),
                v.getIndexField().getNomIndex(), v.getValeur());
    }

    private static String humanSize(long ko) {
        return ko < 1024 ? ko + " Ko" : String.format("%.1f Mo", ko / 1024.0);
    }

    private UploadDocument document(UUID id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + id));
    }
}
