package com.ipt.ged.fichier.previsualisation;

import com.ipt.ged.fichier.Refus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * {@code GET /api/v1/versions/{versionId}/apercu} : aperçu d'une version de
 * document, affiché dans la visionneuse intégrée (§6.1.6).
 *
 * <p>Désactivé tant que {@code ged.fichiers.previsualisation.api-active} n'est
 * pas vrai : il a besoin d'un {@link ResolveurFichierVersion} branché sur le
 * modèle de données, qui n'existe pas encore.
 *
 * <p>Chaque aperçu produit un événement {@link ApercuConsulte}, distinct du
 * téléchargement, que le journal d'audit enregistre.
 */
@RestController
@RequestMapping("/api/v1/versions")
@ConditionalOnProperty(prefix = "ged.fichiers.previsualisation", name = "api-active", havingValue = "true")
public class PrevisualisationController {

    private final ResolveurFichierVersion resolveur;
    private final ControleAccesPrevisualisation controleAcces;
    private final ServicePrevisualisation service;
    private final ApplicationEventPublisher evenements;

    public PrevisualisationController(ResolveurFichierVersion resolveur, ControleAccesPrevisualisation controleAcces,
                                      ServicePrevisualisation service, ApplicationEventPublisher evenements) {
        this.resolveur = resolveur;
        this.controleAcces = controleAcces;
        this.service = service;
        this.evenements = evenements;
    }

    @GetMapping("/{versionId}/apercu")
    public ResponseEntity<StreamingResponseBody> apercu(@PathVariable String versionId, Authentication utilisateur) {
        ResolveurFichierVersion.FichierVersion version = resolveur.resoudre(versionId)
                .orElseThrow(() -> Refus.introuvable("version " + versionId));
        // Droits d'abord : un refus ne doit rien déchiffrer ni convertir.
        controleAcces.verifierLecture(version, utilisateur);

        ServicePrevisualisation.Apercu apercu = service.ouvrir(version.fichierId(), version.typeMime());
        evenements.publishEvent(new ApercuConsulte(version.versionId(), version.documentId(), version.fichierId(),
                utilisateur != null ? utilisateur.getName() : null, Instant.now()));

        HttpHeaders entetes = new HttpHeaders();
        entetes.setContentType(MediaType.parseMediaType(apercu.typeMime()));
        entetes.setContentDisposition(ContentDisposition.inline()
                .filename(version.nomOrigine() != null ? version.nomOrigine() : "apercu", StandardCharsets.UTF_8)
                .build());
        // Contenu déchiffré : ni cache partagé, ni cache navigateur, ni
        // réinterprétation du type par le navigateur.
        entetes.setCacheControl(CacheControl.noStore());
        entetes.set("X-Content-Type-Options", "nosniff");
        if (apercu.taille() >= 0) entetes.setContentLength(apercu.taille());

        StreamingResponseBody corps = sortie -> {
            try (InputStream in = apercu.flux()) {
                in.transferTo(sortie);
            }
        };
        return ResponseEntity.ok().headers(entetes).body(corps);
    }

    /** Événement d'audit de la prévisualisation (§6.1.6). */
    public record ApercuConsulte(String versionId, String documentId, java.util.UUID fichierId,
                                 String utilisateur, Instant consulteLe) {
    }
}
