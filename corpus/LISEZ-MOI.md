# Corpus volumiques T-028 et P-14

Générateur Java (sans Python, conformément à D5) de pages numérisées synthétiques :

| Jeu | Pour | Contenu par défaut |
|---|---|---|
| `t028` | T-028, qualité de l'OCR (§4.3.2) | 300 pages image réparties sur 9 types de documents, dont 100 avec texte de référence |
| `p14` | P-14, volumétrie et débit | 20 000 pages en PDF image (sans couche texte), de 1 à 16 pages par document |

## Limites

Ce corpus **ne remplace pas l'échantillon réel du bureau d'ordre (Q09)**. T-028 ne
peut pas passer « Identique » sans les 300 pages réelles de MMED et leurs 100
transcriptions. Le corpus sert à rôder le banc de mesure et à situer Tesseract en
attendant l'échantillon.

Par rapport au corpus précédent, il ajoute des tampons (ronds, rectangulaires,
mots), du manuscrit (annotations, formulaires remplis, lettres manuscrites,
cellules de tableaux), des tableaux quadrillés et des défauts de numérisation
(inclinaison, bruit, poussières, perforations, pli, bords sombres, verso par
transparence, noir et blanc, compression JPEG).

Le manuscrit est simulé à partir de polices cursives (licence OFL) déformées glyphe
par glyphe. Il reste bien plus régulier qu'une vraie écriture : le taux d'erreur
mesuré dessus est optimiste.

## Générer

```bash
./generer.sh                  # les deux jeux, dans corpus/sortie/
./generer.sh --jeu t028
./generer.sh --jeu p14 --pages 2000
```

Sous Windows : `generer.cmd` avec les mêmes options. Prérequis : JDK 17 et Maven.

| Option | Défaut | Effet |
|---|---|---|
| `--jeu t028\|p14\|tous` | `tous` | jeu à produire |
| `--pages N` | 300 / 20 000 | nombre de pages |
| `--transcriptions N` | 100 | pages de T-028 avec texte de référence, tirées au prorata des types |
| `--graine N` | 2026 | même graine, même corpus |
| `--fils N` | nombre de cœurs | parallélisme |
| `--sortie DOSSIER` | `corpus/sortie` | dossier de sortie (ignoré par git) |

Durée mesurée sur 4 cœurs : environ 1 min pour T-028, 45 min pour P-14 (environ 9 Go).
Pour une graine donnée, le contenu tiré est le même sur tous les postes. La mise en page,
elle, dépend des polices d'impression installées. Pour comparer des mesures entre elles,
il faut donc utiliser les mêmes fichiers générés, pas seulement la même graine.

## Sorties

`t028/`

- `pages/T028-001.jpg|png|tif` : une page par fichier. La résolution est inscrite dans l'image (150, 200 ou 300 dpi).
- `transcriptions/T028-xxx.txt` : texte de référence des 100 pages transcrites, en UTF-8, ligne par ligne, dans l'ordre de lecture. Les rangées de tableau ont leurs cellules séparées par une tabulation. Le texte des tampons et des annotations manuscrites est inclus.
- `zones/T028-xxx.json` : pour chaque zone, son type (`imprime`, `tableau`, `tableau_manuscrit`, `manuscrit`, `tampon`), sa boîte en pixels dans l'image inclinée et ses lignes. Permet de mesurer le taux d'erreur par catégorie, indépendamment de l'ordre de lecture.
- `manifeste.csv` : type, qualité, dpi, mode, format, inclinaison, présence de tampon, de manuscrit ou de tableau, et transcription (oui ou non).

`p14/`

- `documents/<type>/P14-xxxxx.pdf` : PDF image, en JPEG pour la couleur et les gris, en CCITT G4 pour le noir et blanc.
- `manifeste.csv` et `LISEZ-MOI.txt` : pages, dpi, mode et taille de chaque document, avec les totaux.

À noter pour P-14 : `ged.ocr.pages-max` vaut 5 par défaut. Il faut relever ce plafond
pendant la mesure, sinon les pages au-delà de la 5e d'un document ne sont pas OCRisées
et le débit en pages est faussé.

## Code

`backend/src/test/java/com/ipt/ged/corpus/` : `GenerateurCorpus` (point d'entrée),
`Modeles` (mise en page des 9 types), `Page` (rendu et vérité terrain), `Numeriseur`
(défauts de scan et écriture), `Donnees` (vocabulaire fictif). Polices manuscrites et
licences : `backend/src/test/resources/corpus/polices/`.
