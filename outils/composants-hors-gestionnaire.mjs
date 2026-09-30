/*
 * Composants tiers qui ne passent par aucun gestionnaire de paquets (DAT 8.3 :
 * « moteur OCR notamment ») : Tesseract, ses modèles de langue livrés dans
 * backend/tessdata, les icônes recopiées dans le front. Source unique pour
 *   - outils/completer-sbom.mjs : ajout au SBOM CycloneDX du back-end à chaque
 *     `mvn package` (T-085, ANO-E0-002) ;
 *   - outils/registre-dependances.mjs : tableau « hors gestionnaire » du registre.
 */
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';

/* Modèles Tesseract identifiés par leur empreinte SHA-256 contre les
   publications officielles (dépôts tesseract-ocr/tessdata_best et
   tesseract-ocr/tessdata, étiquette 4.1.0, fichiers identiques sur la branche
   main ; vérifié le 30/09/2026). Un modèle ajouté ou remplacé sans être inscrit
   ici bloque la vérification : son origine et sa version doivent être
   consignées (DAT 8.3). */
export const MODELES_TESSERACT = {
  '8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba': { depot: 'tessdata_best', version: '4.1.0' },
  '907743d98915c91a3906dfbf6e48b97598346698fe53aaa797e1a064ffcac913': { depot: 'tessdata_best', version: '4.1.0' },
  'ab9d157d8e38ca00e7e39c7d5363a5239e053f5b0dbdb3167dde9d8124335896': { depot: 'tessdata_best', version: '4.1.0' },
  'e19f2ae860792fdf372cf48d8ce70ae5da3c4052962fe22e9de1f680c374bb0e': { depot: 'tessdata', version: '4.1.0' },
};
const USAGE_MODELES = {
  ara: 'OCR en arabe (DAT 4.3.1)', fra: 'OCR en français (DAT 4.3.1)', eng: 'OCR en anglais',
  osd: 'Orientation et écriture de la page (OSD)',
};

/** Moteur OCR : binaire du paquet du serveur, appelé en processus externe (D5). */
export const TESSERACT = {
  nom: 'Tesseract OCR', cle: 'tesseract-ocr', version: '5',
  versionDetail: '5.x (paquet du serveur ; 5.3.4 sur le poste de développement)',
  licence: 'Apache-2.0', usage: 'Moteur OCR, appelé en processus externe (DAT 4.3.1)',
  url: 'https://github.com/tesseract-ocr/tesseract',
};

/** Modèles de backend/tessdata : { lignes, inconnus }. */
export function modelesTesseract(dossier) {
  if (!existsSync(dossier)) return { lignes: [], inconnus: [] };
  const lignes = [], inconnus = [];
  for (const f of readdirSync(dossier).filter(n => n.endsWith('.traineddata')).sort()) {
    const langue = f.replace('.traineddata', '');
    const sha = createHash('sha256').update(readFileSync(join(dossier, f))).digest('hex');
    const origine = MODELES_TESSERACT[sha];
    if (!origine) inconnus.push(`${f} (SHA-256 ${sha})`);
    lignes.push({
      nom: `Modèle Tesseract ${langue}`, cle: `tessdata-${langue}`, langue, fichier: f, sha256: sha,
      depot: origine?.depot, version: origine ? origine.version : 'inconnue',
      versionDetail: origine ? `${origine.depot} ${origine.version}` : '**origine inconnue**',
      licence: 'Apache-2.0',
      usage: `${USAGE_MODELES[langue] || 'OCR'} ; backend/tessdata/${f}, SHA-256 \`${sha.slice(0, 16)}…\``,
      url: origine ? `https://github.com/tesseract-ocr/${origine.depot}/blob/${origine.version}/${f}` : undefined,
    });
  }
  return { lignes, inconnus };
}

/** Composants du front recopiés à la main. */
export const HORS_GESTIONNAIRE_FRONT = [
  { nom: 'Icônes lucide-static', version: '1.26.0', versionDetail: '1.26.0', licence: 'ISC',
    usage: 'Tracés SVG recopiés dans frontend/src/app/core/ged-icons.ts' },
];
