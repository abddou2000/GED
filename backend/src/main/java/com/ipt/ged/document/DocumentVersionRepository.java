package com.ipt.ged.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    List<DocumentVersion> findByDocumentIdOrderByIdDesc(UUID documentId);

    /**
     * Versions marquées « principale », les plus récentes d'abord.
     *
     * <p>Renvoie une LISTE, et non un {@code Optional}, à dessein. La signature
     * précédente ({@code Optional<DocumentVersion>}) traduisait l'invariant
     * « une seule principale » en <b>hypothèse de lecture</b> : dès que deux
     * lignes portaient le drapeau — ce qu'un dépôt concurrent produisait —
     * chaque appel levait une {@code NonUniqueResultException}, donc un 500, et
     * le document devenait <b>définitivement</b> illisible : plus aucune route
     * ne permettait ni de le consulter ni de réparer le drapeau.
     *
     * <p>L'invariant est désormais tenu à l'écriture (verrou pessimiste sur le
     * document, cf. {@code DocumentService}). La lecture, elle, tolère un état
     * abîmé hérité et le corrige au lieu de s'y casser.
     */
    List<DocumentVersion> findByDocumentIdAndPrincipaleTrueOrderByIdDesc(UUID documentId);
}
