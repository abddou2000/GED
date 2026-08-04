/* ============================================================
   Coque commune — barre supérieure et onglets.
   Une seule source pour la navigation : ajouter une rubrique ici
   la fait apparaître sur les onze écrans, sans les rouvrir.
   ============================================================ */

const RUBRIQUES = [
  { t: 'Accueil',                h: 'accueil.html', i: `<svg class="lucide lucide-home" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8" /> <path d="M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /> </svg>` },
  { t: 'Mes workflow',           h: 'mes-workflow.html', i: `<svg class="lucide lucide-circle-check" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <circle cx="12" cy="12" r="10" /> <path d="m9 12 2 2 4-4" /> </svg>` },
  { t: 'Espaces de travail',     h: 'espaces-de-travail.html', i: `<svg class="lucide lucide-layers" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z" /> <path d="M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12" /> <path d="M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17" /> </svg>` },
  { t: 'Documents',              h: 'documents.html', i: `<svg class="lucide lucide-file-text" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z" /> <path d="M14 2v5a1 1 0 0 0 1 1h5" /> <path d="M10 9H8" /> <path d="M16 13H8" /> <path d="M16 17H8" /> </svg>` },
  { t: 'Rechercher',             h: 'recherche.html', i: `<svg class="lucide lucide-search" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="m21 21-4.34-4.34" /> <circle cx="11" cy="11" r="8" /> </svg>` },
  { t: 'Workflows',              h: 'workflows.html', i: `<svg class="lucide lucide-workflow" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <rect width="8" height="8" x="3" y="3" rx="2" /> <path d="M7 11v4a2 2 0 0 0 2 2h4" /> <rect width="8" height="8" x="13" y="13" rx="2" /> </svg>` },
  { t: "Groupe d'accès",         h: 'groupe-d-acces.html', i: `<svg class="lucide lucide-users" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /> <path d="M16 3.128a4 4 0 0 1 0 7.744" /> <path d="M22 21v-2a4 4 0 0 0-3-3.87" /> <circle cx="9" cy="7" r="4" /> </svg>` },
  { t: 'Index',                  h: 'indexes.html', i: `<svg class="lucide lucide-list" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M3 5h.01" /> <path d="M3 12h.01" /> <path d="M3 19h.01" /> <path d="M8 5h13" /> <path d="M8 12h13" /> <path d="M8 19h13" /> </svg>` },
  { t: "Plan d'indexation",      h: 'plan-indexation.html', i: `<svg class="lucide lucide-shapes" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M8.3 10a.7.7 0 0 1-.626-1.079L11.4 3a.7.7 0 0 1 1.198-.043L16.3 8.9a.7.7 0 0 1-.572 1.1Z" /> <rect x="3" y="14" width="7" height="7" rx="1" /> <circle cx="17.5" cy="17.5" r="3.5" /> </svg>` },
  { t: 'Type de document',       h: 'type-de-document.html', i: `<svg class="lucide lucide-upload" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M12 3v12" /> <path d="m17 8-5-5-5 5" /> <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /> </svg>` },
  { t: 'Étiquette',              h: 'etiquette.html', i: `<svg class="lucide lucide-tag" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z" /> <circle cx="7.5" cy="7.5" r=".5" fill="currentColor" /> </svg>` },
];

/* Logotype officiel du client — image fournie, jamais un tracé maison.
   Le logo porte déjà le mot-symbole : y ajouter un libellé le doublonnerait. */
const MARQUE = `
  <a class="mark" href="accueil.html" aria-label="Marchica Med — accueil">
    <img class="mark-img" src="brand/marchica-med-full.png" alt="Marchica Med" height="30" />
    <span class="mark-txt"><span class="mark-1">GED</span></span>
  </a>`;

/**
 * Page courante, déduite de l'URL — fonctionne aussi en file://.
 * L'extension est retirée de part et d'autre : beaucoup de serveurs statiques
 * servent « /recherche » pour « recherche.html », et l'onglet actif ne doit pas
 * dépendre de cette convention.
 */
function cle(chemin) {
  return (chemin.split('/').pop() || 'accueil').replace(/\.html$/, '').toLowerCase();
}

function pageCourante() {
  return cle(location.pathname) || 'accueil';
}

function poserCoque() {
  const hote = document.querySelector('[data-shell]');
  if (!hote) return;
  const ici = pageCourante();

  const onglets = RUBRIQUES.map(r =>
    `<a href="${r.h}"${cle(r.h) === ici ? ' class="on" aria-current="page"' : ''}>${r.i || ''}<span>${r.t}</span></a>`
  ).join('');

  hote.insertAdjacentHTML('afterbegin', `
    <header class="topbar">
      ${MARQUE}
      <div class="spacer"></div>
      <div class="who">
        <span class="av">SB</span>
        <span>
          <span class="nm" style="display:block">Sara Bennani</span>
          <span class="rl">Service documentation</span>
        </span>
      </div>
    </header>
    <nav class="tabs" aria-label="Rubriques">${onglets}</nav>
  `);
}

/**
 * Les chiffres montent jusqu'à leur valeur. La valeur réelle est déjà dans le
 * HTML : on ne remet à zéro qu'une fois l'animation lancée, pour qu'un
 * navigateur qui n'exécute pas requestAnimationFrame affiche quand même le bon
 * nombre plutôt qu'un zéro figé.
 */
function animerCompteurs() {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  document.querySelectorAll('[data-count]').forEach((el, i) => {
    const cible = +el.dataset.count;
    const duree = 900, depart = performance.now() + 450 + i * 90;
    let amorce = false;
    const pas = (t) => {
      if (!amorce) { el.textContent = '0'; amorce = true; }
      if (t < depart) return requestAnimationFrame(pas);
      const p = Math.min((t - depart) / duree, 1);
      el.textContent = Math.round(cible * (1 - Math.pow(1 - p, 3)));
      if (p < 1) requestAnimationFrame(pas);
    };
    requestAnimationFrame(pas);
  });
}

/** Filtre de tableau : masque les lignes qui ne contiennent pas la saisie. */
function brancherRecherche() {
  document.querySelectorAll('[data-filtre]').forEach(champ => {
    const table = document.querySelector(champ.dataset.filtre);
    if (!table) return;
    const compteur = document.querySelector('[data-compte]');
    champ.addEventListener('input', () => {
      const q = champ.value.trim().toLowerCase();
      let vus = 0;
      table.querySelectorAll('tbody tr').forEach(tr => {
        const ok = !q || tr.textContent.toLowerCase().includes(q);
        tr.hidden = !ok;
        if (ok) vus++;
      });
      const vide = table.parentElement.querySelector('.vide');
      if (vide) vide.hidden = vus > 0;
      if (compteur) compteur.textContent = vus;
    });
  });
}

/** Cases à cocher : l'en-tête pilote la colonne, la barre d'action suit. */
function brancherSelection() {
  document.querySelectorAll('table[data-selectable]').forEach(table => {
    const maitre = table.querySelector('thead input[type=checkbox]');
    const cases = () => [...table.querySelectorAll('tbody input[type=checkbox]')];
    const barre = document.querySelector('[data-bulk]');
    const maj = () => {
      const n = cases().filter(c => c.checked).length;
      if (barre) {
        barre.hidden = n === 0;
        const t = barre.querySelector('[data-bulk-count]');
        if (t) t.textContent = n;
      }
      if (maitre) {
        maitre.checked = n > 0 && n === cases().length;
        maitre.indeterminate = n > 0 && n < cases().length;
      }
    };
    if (maitre) maitre.addEventListener('change', () => {
      cases().forEach(c => { c.checked = maitre.checked; });
      maj();
    });
    cases().forEach(c => c.addEventListener('change', maj));
    maj();
  });
}

document.addEventListener('DOMContentLoaded', () => {
  poserCoque();
  animerCompteurs();
  brancherRecherche();
  brancherSelection();
});
