package com.ipt.ged.indexation;

import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.index.IndexRepository;
import com.ipt.ged.indexation.dto.*;
import com.ipt.ged.ocr.ExtracteurValeurs;
import com.ipt.ged.ocr.OcrService;
import com.ipt.ged.ocr.TexteExtrait;
import com.ipt.ged.planindexation.PlanIndexation;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
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

    private final IndexRepository indexRepository;
    private final UploadDocumentRepository documentRepository;
    private final DocumentIndexRepository valeurRepository;
    private final OcrService ocr;
    private final ExtracteurValeurs valeurs;

    /* ===================== Critères ===================== */

    /** Critères de recherche disponibles, dérivés des index. */
    public List<CritereResponse> criteres() {
        return indexRepository.findByDeletedFalseOrderByIdAsc().stream()
                .filter(IndexField::isIndexePourRecherche)
                .map(IndexationService::versCritere)
                .toList();
    }

    /** Index servant au regroupement des résultats. */
    public List<CritereResponse> groupages() {
        return indexRepository.findByDeletedFalseOrderByIdAsc().stream()
                .filter(IndexField::isIndexDeGroupage)
                .map(IndexationService::versCritere)
                .toList();
    }

    /** Champs à renseigner pour un document : ceux du plan d'indexation de son type. */
    public List<CritereResponse> champsDuDocument(Long documentId) {
        UploadDocument doc = document(documentId);
        if (doc.getTypeDocument() == null || doc.getTypeDocument().getPlanIndexation() == null) {
            return List.of();
        }
        return doc.getTypeDocument().getPlanIndexation().getIndices().stream()
                .filter(i -> !i.isDeleted())
                .map(IndexationService::versCritere)
                .toList();
    }

    private static CritereResponse versCritere(IndexField f) {
        List<String> options = f.getFieldType() == IndexFieldType.LISTE && f.getValeurs() != null
                ? Arrays.stream(f.getValeurs().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : List.of();
        return new CritereResponse(f.getId(), f.getCode(), f.getNomIndex(),
                f.getFieldType().name(), options, f.isIndexDeGroupage());
    }

    /* ===================== Valeurs d'un document ===================== */

    public List<ResultatResponse.ValeurResponse> valeurs(Long documentId) {
        document(documentId);
        return valeurRepository.findByDocumentIdOrderByIdAsc(documentId).stream()
                .map(IndexationService::versValeur)
                .toList();
    }

    /** Enregistre (crée ou met à jour) les valeurs d'index d'un document. */
    @Transactional
    public List<ResultatResponse.ValeurResponse> enregistrer(Long documentId, ValeurRequest requete) {
        UploadDocument doc = document(documentId);

        Map<Long, DocumentIndex> existantes = valeurRepository.findByDocumentIdOrderByIdAsc(documentId).stream()
                .collect(Collectors.toMap(v -> v.getIndexField().getId(), v -> v));

        for (ValeurRequest.Ligne ligne : requete.valeurs()) {
            IndexField champ = indexRepository.findById(ligne.indexFieldId())
                    .orElseThrow(() -> new EntityNotFoundException("Index introuvable : " + ligne.indexFieldId()));

            String valeur = ligne.valeur() == null ? null : ligne.valeur().trim();
            if (champ.isObligatoire() && (valeur == null || valeur.isEmpty())) {
                throw new IllegalArgumentException("L'index « " + champ.getNomIndex() + " » est obligatoire.");
            }
            controlerType(champ, valeur);

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

        // La confirmation de l'opérateur vaut validation : c'est ici, et nulle part
        // avant, que la référence du document est composée depuis le plan.
        recomposerReference(doc);
        return valeurs(documentId);
    }

    /** Recompose la référence du document à partir des valeurs désormais enregistrées. */
    private void recomposerReference(UploadDocument doc) {
        PlanIndexation plan = doc.getTypeDocument() != null
                ? doc.getTypeDocument().getPlanIndexation() : null;
        if (plan == null) return;

        Map<Long, String> valeurs = valeurRepository.findByDocumentIdOrderByIdAsc(doc.getId()).stream()
                .collect(Collectors.toMap(v -> v.getIndexField().getId(), DocumentIndex::getValeur, (a, b) -> a));

        List<String> ordonnees = plan.getIndices().stream()
                .filter(i -> !i.isDeleted())
                .map(i -> valeurs.get(i.getId()))
                .toList();

        doc.setReference(ordonnees.stream().allMatch(v -> v == null || v.isBlank())
                ? null : composerReference(ordonnees, plan));
        documentRepository.save(doc);
    }

    /** Refuse une valeur incompatible avec le type de l'index. */
    private void controlerType(IndexField champ, String valeur) {
        if (valeur == null || valeur.isEmpty()) return;
        switch (champ.getFieldType()) {
            case NOMBRE -> {
                if (nombre(valeur) == null) {
                    throw new IllegalArgumentException("« " + champ.getNomIndex() + " » attend un nombre.");
                }
            }
            case DATE -> {
                if (!valeur.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw new IllegalArgumentException("« " + champ.getNomIndex() + " » attend une date (AAAA-MM-JJ).");
                }
            }
            case LISTE -> {
                List<String> options = versCritere(champ).options();
                if (!options.isEmpty() && options.stream().noneMatch(o -> o.equalsIgnoreCase(valeur))) {
                    throw new IllegalArgumentException(
                            "« " + valeur + " » ne fait pas partie des valeurs de « " + champ.getNomIndex() + " ».");
                }
            }
            case TEXTE -> { /* aucune contrainte */ }
        }
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
    public AnalyseResponse analyser(Long documentId) {
        UploadDocument doc = document(documentId);
        String fichier = doc.getFileName() != null ? doc.getFileName() : doc.getName();

        PlanIndexation plan = doc.getTypeDocument() != null
                ? doc.getTypeDocument().getPlanIndexation() : null;
        if (plan == null) {
            return vide(doc, fichier, "Le type « "
                    + (doc.getTypeDocument() != null ? doc.getTypeDocument().getTypeDeDocument() : "?")
                    + " » n'a pas de plan d'indexation : aucune analyse possible.");
        }

        List<IndexField> champs = plan.getIndices().stream().filter(i -> !i.isDeleted()).toList();
        if (champs.isEmpty()) {
            return vide(doc, fichier, "Le plan « " + plan.getNomDuPlan() + " » ne contient aucun index.");
        }

        Map<Long, String> actuelles = valeurRepository.findByDocumentIdOrderByIdAsc(documentId).stream()
                .collect(Collectors.toMap(v -> v.getIndexField().getId(), DocumentIndex::getValeur, (a, b) -> a));

        String sep = plan.getSeparateur() == null || plan.getSeparateur().isEmpty() ? "_" : plan.getSeparateur();
        List<String> segments = decouper(fichier, sep);

        // Second recours : le contenu du document, lu par la chaîne d'OCRisation.
        // Il n'est sollicité que si le nom de fichier ne suffit pas — la lecture
        // d'un scan coûte cher, on ne la déclenche pas pour rien.
        TexteExtrait texte = manqueUnChamp(champs, segments)
                ? ocr.lire(doc.getId())
                : TexteExtrait.aucune("Nom de fichier suffisant : contenu non sollicité.");

        List<AnalyseResponse.Proposition> propositions = new ArrayList<>();
        for (int i = 0; i < champs.size(); i++) {
            IndexField champ = champs.get(i);
            String segment = i < segments.size() ? segments.get(i).trim() : null;
            String motif = motifDeRejet(champ, segment, segments.size(), i);

            String valeur = motif == null ? segment : null;
            String source = valeur != null ? "NOM_FICHIER" : null;

            // Le nom n'a rien donné : on tente le contenu, puis on le contrôle
            // comme n'importe quelle saisie — l'OCR n'a aucun passe-droit.
            if (valeur == null && texte.exploitable()) {
                String duContenu = valeurs.deduire(champ, texte.texte(), versCritere(champ).options());
                if (duContenu != null && motifDeRejet(champ, duContenu, 1, 0) == null) {
                    valeur = duContenu;
                    source = "CONTENU";
                    motif = null;
                }
            }

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

        String avertissement = reconnus == champs.size() ? null
                : reconnus == 0
                    ? "Ni le nom du fichier (charte « "
                        + champs.stream().map(IndexField::getNomIndex).collect(Collectors.joining(sep))
                        + " ») ni le contenu n'ont permis de conclure : renseignez les champs à la main."
                    : (champs.size() - reconnus) + " champ(s) n'ont pas pu être déduits : complétez-les avant de confirmer.";

        return new AnalyseResponse(doc.getId(), fichier, plan.getNomDuPlan(), sep, plan.isMajuscule(),
                plan.isModeIndexation(), segments, propositions, reference,
                reconnus, champs.size(), avertissement,
                texte.provenance().name(), texte.detail());
    }

    /** Le nom de fichier laisse-t-il au moins un champ sans valeur exploitable ? */
    private boolean manqueUnChamp(List<IndexField> champs, List<String> segments) {
        for (int i = 0; i < champs.size(); i++) {
            String segment = i < segments.size() ? segments.get(i).trim() : null;
            if (motifDeRejet(champs.get(i), segment, segments.size(), i) != null) return true;
        }
        return false;
    }

    private AnalyseResponse vide(UploadDocument doc, String fichier, String avertissement) {
        return new AnalyseResponse(doc.getId(), fichier, null, "_", false, false,
                List.of(), List.of(), null, 0, 0, avertissement,
                TexteExtrait.Provenance.AUCUNE.name(), null);
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
     * Compose la référence du document : valeurs des index dans l'ordre du plan,
     * jointes par le séparateur, en majuscules si la charte l'exige.
     */
    private static String composerReference(List<String> valeurs, PlanIndexation plan) {
        String sep = plan.getSeparateur() == null || plan.getSeparateur().isEmpty() ? "_" : plan.getSeparateur();
        String joint = valeurs.stream()
                .map(v -> v == null || v.isBlank() ? "?" : v.trim())
                .collect(Collectors.joining(sep));
        return joint.isBlank() ? null : (plan.isMajuscule() ? joint.toUpperCase() : joint);
    }

    /* ===================== Recherche ===================== */

    /**
     * Recherche multi-critères. Un document est retenu s'il satisfait
     * <b>tous</b> les filtres renseignés (ET logique).
     */
    public List<GroupeResponse> rechercher(RechercheRequest requete) {
        List<UploadDocument> candidats = documentRepository.findByDeletedFalseOrderByIdDesc().stream()
                .filter(d -> requete.workspaceId() == null
                        || (d.getWorkspace() != null && d.getWorkspace().getId().equals(requete.workspaceId())))
                .filter(d -> requete.typeDocumentId() == null
                        || (d.getTypeDocument() != null && d.getTypeDocument().getId().equals(requete.typeDocumentId())))
                .toList();
        if (candidats.isEmpty()) return List.of();

        // Valeurs de tous les candidats en une seule requête
        Map<Long, Map<Long, String>> parDocument = new HashMap<>();
        valeurRepository.findByDocumentIds(candidats.stream().map(UploadDocument::getId).toList())
                .forEach(v -> parDocument
                        .computeIfAbsent(v.getDocument().getId(), k -> new HashMap<>())
                        .put(v.getIndexField().getId(), v.getValeur()));

        List<RechercheRequest.FiltreIndex> filtres = requete.criteres() == null ? List.of()
                : requete.criteres().stream().filter(IndexationService::filtreRenseigne).toList();

        Map<Long, IndexField> champs = indexRepository.findByDeletedFalseOrderByIdAsc().stream()
                .collect(Collectors.toMap(IndexField::getId, f -> f));

        List<UploadDocument> retenus = candidats.stream()
                .filter(d -> filtres.stream().allMatch(f -> satisfait(
                        parDocument.getOrDefault(d.getId(), Map.of()).get(f.indexFieldId()),
                        champs.get(f.indexFieldId()), f)))
                .toList();

        return grouper(retenus, parDocument, champs, requete.grouperPar());
    }

    private static boolean filtreRenseigne(RechercheRequest.FiltreIndex f) {
        return estRenseigne(f.valeur()) || estRenseigne(f.de()) || estRenseigne(f.a());
    }

    private static boolean estRenseigne(String s) { return s != null && !s.isBlank(); }

    /** Confronte la valeur portée par le document au filtre demandé. */
    private static boolean satisfait(String valeur, IndexField champ, RechercheRequest.FiltreIndex filtre) {
        if (champ == null) return false;
        if (valeur == null || valeur.isBlank()) return false;   // document non indexé sur ce champ

        return switch (champ.getFieldType()) {
            case TEXTE -> !estRenseigne(filtre.valeur())
                    || valeur.toLowerCase().contains(filtre.valeur().trim().toLowerCase());
            case LISTE -> !estRenseigne(filtre.valeur())
                    || valeur.equalsIgnoreCase(filtre.valeur().trim());
            // Dates au format ISO : l'ordre alphabétique vaut ordre chronologique
            case DATE -> (!estRenseigne(filtre.de()) || valeur.compareTo(filtre.de()) >= 0)
                    && (!estRenseigne(filtre.a()) || valeur.compareTo(filtre.a()) <= 0);
            case NOMBRE -> {
                Double v = nombre(valeur);
                if (v == null) yield false;
                Double min = nombre(filtre.de()), max = nombre(filtre.a());
                yield (min == null || v >= min) && (max == null || v <= max);
            }
        };
    }

    private static Double nombre(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.valueOf(s.trim().replace(',', '.')); }
        catch (NumberFormatException e) { return null; }
    }

    /* ===================== Regroupement ===================== */

    private List<GroupeResponse> grouper(List<UploadDocument> documents,
                                         Map<Long, Map<Long, String>> parDocument,
                                         Map<Long, IndexField> champs,
                                         Long grouperPar) {
        if (grouperPar == null || !champs.containsKey(grouperPar)) {
            List<ResultatResponse> tous = documents.stream()
                    .map(d -> versResultat(d, parDocument, champs)).toList();
            return tous.isEmpty() ? List.of() : List.of(new GroupeResponse("Tous", tous.size(), tous));
        }

        Map<String, List<ResultatResponse>> groupes = new TreeMap<>();
        for (UploadDocument d : documents) {
            String cle = parDocument.getOrDefault(d.getId(), Map.of()).get(grouperPar);
            if (cle == null || cle.isBlank()) cle = "(non renseigné)";
            groupes.computeIfAbsent(cle, k -> new ArrayList<>()).add(versResultat(d, parDocument, champs));
        }
        return groupes.entrySet().stream()
                .map(e -> new GroupeResponse(e.getKey(), e.getValue().size(), e.getValue()))
                .toList();
    }

    private static ResultatResponse versResultat(UploadDocument d,
                                                 Map<Long, Map<Long, String>> parDocument,
                                                 Map<Long, IndexField> champs) {
        List<ResultatResponse.ValeurResponse> valeurs = parDocument.getOrDefault(d.getId(), Map.of())
                .entrySet().stream()
                .filter(e -> champs.containsKey(e.getKey()))
                .map(e -> {
                    IndexField f = champs.get(e.getKey());
                    return new ResultatResponse.ValeurResponse(f.getId(), f.getCode(), f.getNomIndex(), e.getValue());
                })
                .sorted(Comparator.comparing(ResultatResponse.ValeurResponse::libelle))
                .toList();

        return new ResultatResponse(
                d.getId(), d.getName(), d.getExtension(), humanSize(d.getSizeKo()),
                d.getWorkspace() != null ? d.getWorkspace().getName() : null,
                d.getTypeDocument() != null ? d.getTypeDocument().getTypeDeDocument() : null,
                d.getExpirationDate() != null ? d.getExpirationDate().toString() : null,
                d.getReference(),
                valeurs);
    }

    private static ResultatResponse.ValeurResponse versValeur(DocumentIndex v) {
        return new ResultatResponse.ValeurResponse(
                v.getIndexField().getId(), v.getIndexField().getCode(),
                v.getIndexField().getNomIndex(), v.getValeur());
    }

    private static String humanSize(long ko) {
        return ko < 1024 ? ko + " Ko" : String.format("%.1f Mo", ko / 1024.0);
    }

    private UploadDocument document(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + id));
    }
}
