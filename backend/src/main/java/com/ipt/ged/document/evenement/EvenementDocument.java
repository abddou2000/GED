package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.UUID;

/**
 * Événement de domaine sur un document, publié par le service métier
 * ({@code ApplicationEventPublisher}) à chaque opération : c'est la source du
 * journal d'audit (lot traçabilité, dev2), qui les écoute sans que les deux
 * lots modifient le même service.
 *
 * <p><b>Contrat de publication</b> :
 * <ul>
 *   <li>une écriture publie son événement <b>dans sa transaction</b>, après
 *       validation des règles et avant la validation de la transaction : un
 *       écouteur {@code @TransactionalEventListener(phase = BEFORE_COMMIT)}
 *       écrit l'audit dans la même transaction (tout ou rien), un écouteur
 *       {@code AFTER_COMMIT} ne voit que ce qui a réellement eu lieu ;</li>
 *   <li>une lecture (téléchargement, aperçu) publie son événement une fois le
 *       contrôle d'accès passé et avant de servir le contenu ;</li>
 *   <li>un refus (règle métier, droits, antivirus) ne publie pas d'événement
 *       document : les refus de fichier ont les leurs
 *       ({@code ControleFichiers.FichierInfecte}).</li>
 * </ul>
 */
public sealed interface EvenementDocument permits DocumentDepose, VersionAjoutee, VersionRestauree,
        DocumentTelecharge, ApercuConsulte, MetadonneesModifiees, VerrouModifie, DocumentSupprime,
        DocumentRestaure, ContenuIndexe, OcrEnEchec {

    /** Code stable de l'action, repris tel quel par le journal d'audit. */
    String type();

    UUID documentId();

    /** Version concernée, ou {@code null} quand l'opération porte sur la fiche. */
    UUID versionId();

    Acteur acteur();

    Instant survenuLe();
}
