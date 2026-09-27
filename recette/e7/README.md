# Recette E7 — cycle de vie (archivage, export, purge, aperçu)

`bash recette/lib/lancer-java.sh recette/e7/RecetteCycleDeVie.java RecetteCycleDeVie`

Prérequis : jeu d'habilitations de la recette E3. Contrôles sur disque et en base facultatifs :
`GED_STOCKAGE_RACINE` (racine des `.enc`) et `GED_E7_JDBC`
(`jdbc:postgresql://localhost:5432/<base>?user=…&currentSchema=ged`).

Contrôles : archivage manuel (D10) avec copie PDF/A-2 validée par veraPDF (réel) ; document
archivé intouchable pour tous les rôles (fiche, versement, verrou, suppression, rattachement) ;
copie servie par défaut, original disponible (`?original=true`) ; désarchivage réservé ; DOCX
converti à l'archivage (LibreOffice **simulé** : `FauxSoffice`) ; archivage d'un dossier entier en
traitement de fond et dépôt refusé dans un dossier archivé ; export ZIP (contenu au périmètre,
omissions silencieuses, manifeste UTF-8 et empreintes exactes, 404 hors périmètre) ; purge
(refusée hors corbeille et sans la permission, fichier et clé de données détruits) ; aperçu PDF
en ligne et DOCX converti, 404 hors périmètre.

## Instance de recette sur le poste

Profil dev, annuaire simulé (`recette/donnees/annuaire-recette.ldif`), antivirus par
`recette/lib/ClamdSimule.java` (faux clamd des tests de dev3, `GED_ANTIVIRUS_ACTIF=true`,
`GED_CLAMAV_PORT`), LibreOffice par un `.cmd` qui lance `FauxSoffice` ; veraPDF et Tesseract
`fra`/`ara` réels. Voir `docs/conformite/recette/RESULTATS-VAGUE-3.md` §1.
