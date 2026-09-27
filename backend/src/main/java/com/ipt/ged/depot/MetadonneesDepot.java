package com.ipt.ged.depot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.indexation.ValidationPlan;
import com.ipt.ged.indexation.dto.ValeurRequest;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lecture et validation des métadonnées reçues avec le fichier (§5.3 « dépôt
 * avec métadonnées », §12.11 temps 1), <b>avant toute écriture</b>.
 *
 * <p>Format : un objet JSON dont chaque clé désigne un index du plan du type,
 * par son code (insensible à la casse) ou son identifiant, et chaque valeur est
 * une valeur simple (texte, nombre, booléen, ou {@code null} pour « vide ») :
 * <pre>{"NUM_FACTURE": "F-2026-001", "MONTANT": 1250.5, "DATE_FACTURE": "2026-09-01"}</pre>
 * Refus : 413 au-delà de 64 Ko (§5.3.2), 400 {@code METADONNEES_INVALIDES}
 * avec un dictionnaire d'erreurs par champ (clé inconnue du plan, nature de la
 * valeur, index obligatoire manquant).
 */
@Component
public class MetadonneesDepot {

    /** 64 Ko de métadonnées par requête (§5.3.2). */
    public static final int LIMITE_OCTETS = 64 * 1024;

    private final TypeDocumentRepository types;
    private final ObjectMapper json;

    public MetadonneesDepot(TypeDocumentRepository types, ObjectMapper json) {
        this.types = types;
        this.json = json;
    }

    /**
     * @return les valeurs à enregistrer au temps 2, ou vide si aucune métadonnée
     *         n'a été transmise.
     */
    @Transactional(readOnly = true)
    public Optional<ValeurRequest> lire(String brut, UUID typeDocumentId) {
        if (brut == null || brut.isBlank()) return Optional.empty();
        if (brut.getBytes(StandardCharsets.UTF_8).length > LIMITE_OCTETS) {
            throw ErreurDepot.tropVolumineuses(LIMITE_OCTETS);
        }
        JsonNode racine;
        try {
            racine = json.readTree(brut);
        } catch (JsonProcessingException e) {
            throw ErreurDepot.invalides(Map.of("metadonnees", "n'est pas un JSON valide."));
        }
        if (racine == null || !racine.isObject()) {
            throw ErreurDepot.invalides(Map.of("metadonnees", "doit être un objet JSON {\"CODE_INDEX\": valeur}."));
        }
        if (racine.isEmpty()) return Optional.empty();

        TypeDocument type = types.findById(typeDocumentId)
                .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + typeDocumentId));
        List<IndexField> plan = ValidationPlan.champs(type);

        Map<UUID, String> valeurs = new LinkedHashMap<>();
        List<String> inconnus = new ArrayList<>();
        Map<String, String> erreurs = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = racine.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            Optional<IndexField> champ = designe(plan, e.getKey());
            if (champ.isEmpty()) {
                inconnus.add(e.getKey());
                continue;
            }
            JsonNode v = e.getValue();
            if (v.isContainerNode()) {
                erreurs.put(champ.get().getCode(), "attend une valeur simple (texte, nombre ou date).");
                continue;
            }
            valeurs.put(champ.get().getId(), v.isNull() ? null : v.asText());
        }
        ValidationPlan.erreurs(plan, valeurs, inconnus).forEach(erreurs::putIfAbsent);
        if (!erreurs.isEmpty()) throw ErreurDepot.invalides(erreurs);

        List<ValeurRequest.Ligne> lignes = new ArrayList<>();
        valeurs.forEach((id, valeur) -> lignes.add(new ValeurRequest.Ligne(id, valeur)));
        return Optional.of(new ValeurRequest(lignes));
    }

    private static Optional<IndexField> designe(List<IndexField> plan, String cle) {
        String c = cle.trim();
        for (IndexField f : plan) {
            if (f.getCode() != null && f.getCode().equalsIgnoreCase(c)) return Optional.of(f);
            if (f.getId().toString().equalsIgnoreCase(c)) return Optional.of(f);
        }
        return Optional.empty();
    }
}
