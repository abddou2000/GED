package com.ipt.ged.contratapi.dto;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.common.Tri;
import com.ipt.ged.common.erreur.ChampsInconnusSignales;
import com.ipt.ged.indexation.dto.RechercheRequest;
import com.ipt.ged.recherche.RequeteRecherche;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Charges utiles du contrat d'API (DAT §5.3.1) qui n'existaient pas encore. */
public final class DtoContratApi {

    private DtoContratApi() {}

    /**
     * Création d'un dossier dans un espace ou un dossier parent
     * ({@code POST /noeuds/{id}/dossiers}).
     *
     * @param code facultatif : un code unique est attribué s'il est absent
     */
    public record DossierRequest(
            @NotBlank(message = "Le nom est obligatoire") @Size(max = 255) String nom,
            @Size(max = 255) @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "Code : lettres, chiffres, . _ -")
            String code,
            @Size(max = 1000) String description) {}

    /**
     * Recherche multicritère et/ou plein texte ({@code POST /recherches}),
     * filtrée à la source par les droits de l'appelant.
     *
     * @param texte     plein texte (syntaxe web : "expression", -exclusion, or) ; facultatif
     * @param criteres  filtres sur les index (TEXTE, LISTE : valeur ; DATE, NOMBRE : de / a)
     * @param archives  INCLURE (défaut), EXCLURE ou SEULEMENT (§12.6)
     * @param tri       PERTINENCE (défaut avec texte), DATE_DEPOT (défaut sans texte), NOM, TYPE
     * @param taille    50 par défaut, plafonnée à 200 (DAT §5.3.2) ; alias de {@code size}
     * @param size      taille de page sous son nom du §5.3.2 (T-050) ; l'emporte sur {@code taille}
     * @param dateDocumentDu borne basse incluse de la date du document (§4.4.3)
     * @param dateDocumentAu borne haute incluse de la date du document (§4.4.3)
     * @param confidentialite niveau de confidentialité : PUBLIC, PRIVE ou CONFIDENTIEL (§4.4.3)
     * @param deposantUtilisateurId identité GED du déposant (§4.4.3)
     *
     * <p>Un champ inconnu est ignoré (P-08) et signalé dans l'en-tête
     * {@code GED-Champs-Ignores} (ANO-F-011).
     */
    @ChampsInconnusSignales
    public record RechercheContratRequest(
            @Size(max = 500, message = "La recherche ne peut pas dépasser 500 caractères.") String texte,
            @Size(max = 50) @Valid List<RechercheRequest.FiltreIndex> criteres,
            UUID typeDocumentId,
            UUID noeudId,
            LocalDate deposeDu,
            LocalDate deposeAu,
            @Pattern(regexp = "^(INCLURE|EXCLURE|SEULEMENT)?$") String archives,
            @Pattern(regexp = "^(?i)(INTERFACE|API|BUREAU_ORDRE|REPRISE)?$",
                    message = "Canal : INTERFACE, API, BUREAU_ORDRE ou REPRISE.") String canal,
            /** Vrai : seuls les documents dont l'échéance de conservation est atteinte (T-112, §12.9). */
            Boolean echeanceDepassee,
            RequeteRecherche.Tri tri,
            @Min(0) Integer page,
            Integer taille,
            LocalDate dateDocumentDu,
            LocalDate dateDocumentAu,
            Confidentialite confidentialite,
            UUID deposantUtilisateurId,
            Integer size) {

        /** Taille de page retenue : {@code size}, sinon {@code taille} ; 50 par défaut, 200 au plus. */
        public int tailleDemandee() {
            return Tri.taillePage(size, taille);
        }
    }
}
