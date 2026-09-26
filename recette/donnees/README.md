# Jeux de données de recette

Tous les contenus sont **fictifs** (aucune donnée réelle de Marchica Med). Chaque fichier
porte un mot-témoin inventé, absent de tout dictionnaire, pour qu'une recherche qui le
retrouve prouve la lecture du contenu : `zarkolinet` (français) et `زركولين` (arabe).

`MANIFESTE.csv` donne pour chaque fichier sa taille, son empreinte SHA-256, le type réel
que Tika doit détecter et la réponse attendue au dépôt.

| Fichier | Contenu | Sert à |
|---|---|---|
| `pdf_texte_fr_convention.pdf` | PDF 2 pages, couche texte native | Dépôt nominal, fumée, recherche sans OCR (E6), téléchargement et empreinte (E5) |
| `pdf_texte_fr_facture.pdf` | PDF 2 pages, montants et dates | Métadonnées date/nombre (E7) |
| `pdf_texte_ar_courrier.pdf` | PDF 1 page, couche texte **arabe** (formes de présentation Unicode, cas réel fréquent) | Recherche `arabic` sans OCR, normalisation (E6) |
| `scan_fr_courrier.pdf` | PDF 2 pages **images seules** 300 dpi, biais, grain | OCR `fra` (E6) ; vérifié : Tesseract `fra` restitue le témoin |
| `scan_ar_courrier.pdf` | PDF 2 pages **images seules** 300 dpi, arabe, RTL | OCR `ara` (E6) — nécessite `ara.traineddata`, absent de ce poste |
| `image_scan_fr.png` | Image 200 dpi en niveaux de gris | Dépôt image, prévisualisation, OCR d'image |
| `document_fr.docx` | Procès-verbal avec tableau | Type réel OOXML, prévisualisation LibreOffice (E5), PDF/A (E7) |
| `note_texte_brut.txt`, `tableau_decomptes.csv` | Texte brut et CSV | Formats autorisés par défaut (6.1.5) |
| `faux_pdf_texte.pdf` | Extension `.pdf`, contenu texte | **415** attendu (type réel `text/plain`) |
| `faux_pdf_executable.pdf` | Extension `.pdf`, en-tête MZ/PE sans code | **415** attendu (type réel `application/x-msdownload`) |

## Générés à la demande (jamais versionnés)

| Fichier | Commande | Pourquoi pas versionné |
|---|---|---|
| EICAR | `ecrire_eicar <sortie>` (fonction bash de `recette/e5/commun-e5.sh`) | Windows Defender mettrait la copie de travail en quarantaine ; les scripts E5 le créent dans un dossier temporaire puis le suppriment |
| Scans de 20 pages (critère de sortie E6) | `bash generer-donnees.sh --pages-scan 20 --sortie genere/` | 8 Mo (FR) et 6,5 Mo (AR) ; dossier `genere/` ignoré par git |
| Fichier de 201 Mo (413) | créé par `recette/e5/verifier-taille.sh` (fichier creux) | Taille |

## Régénérer

```
bash generer-donnees.sh [--police-arabe /chemin/police.ttf]
```

`GenerateurDonnees.java` est lancé en mode fichier source avec le classpath du backend
(PDFBox, résolu hors ligne par Maven) : **aucun Python** (décision D5 de la revue
technique). Java2D met l'arabe en forme nativement pour les scans ; la couche texte du
PDF arabe est écrite en formes de présentation Unicode et en ordre visuel, comme le font
beaucoup d'outils bureautiques (cas réel qui exige une normalisation NFKC côté recherche).
Sous Windows, la police arabe est trouvée seule (Arial, Traditional Arabic) ; sous Linux,
installer `fonts-noto-core` (Noto Naskh Arabic) ou passer `--police-arabe`. La génération
est **reproductible à l'octet près** (identifiant PDF et dates ZIP fixés, dégradations
tirées d'une graine fixe) : `MANIFESTE.csv` ne change que si le contenu change.

Vérifié le 2026-09-26 : les scans n'ont aucune couche texte ; Tesseract `fra` restitue
le mot-témoin du scan français ; le DOCX s'ouvre (1 tableau, témoin présent).

## Produire un vrai scan (si l'échantillon MMED n'est pas encore disponible)

Imprimer `pdf_texte_fr_convention.pdf` et `pdf_texte_ar_courrier.pdf`, les numériser en
300 dpi niveaux de gris, PDF image sans OCR du scanner (désactiver la « reconnaissance de
texte » du pilote, sinon le PDF porte déjà une couche texte et l'OCR de la GED n'est pas
exercé). Contrôle : `pdftotext x.pdf - | tr -d '[:space:]' | wc -c` (poppler-utils) doit
afficher `0`.
