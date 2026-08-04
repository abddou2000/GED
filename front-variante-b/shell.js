/* ============================================================
   Coque commune — barre supérieure et onglets.
   Une seule source pour la navigation : ajouter une rubrique ici
   la fait apparaître sur les onze écrans, sans les rouvrir.
   ============================================================ */

const RUBRIQUES = [
  { t: 'Accueil',                h: 'accueil.html' },
  { t: 'Mes workflow',           h: 'mes-workflow.html' },
  { t: 'Espaces de travail',     h: 'espaces-de-travail.html' },
  { t: 'Documents',              h: 'documents.html' },
  { t: 'Indexation automatique', h: 'indexation-automatique.html' },
  { t: 'Workflows',              h: 'workflows.html' },
  { t: "Groupe d'accès",         h: 'groupe-d-acces.html' },
  { t: 'Index',                  h: 'indexes.html' },
  { t: "Plan d'indexation",      h: 'plan-indexation.html' },
  { t: 'Type de document',       h: 'type-de-document.html' },
  { t: 'Étiquette',              h: 'etiquette.html' },
];

/* Logotype officiel du client — image fournie, jamais un tracé maison.
   Le logo porte déjà le mot-symbole : y ajouter un libellé le doublonnerait. */
const MARQUE = `
  <a class="mark" href="accueil.html" aria-label="Marchica Med — accueil">
    <img class="mark-img" src="brand/marchica-med-full.png" alt="Marchica Med" height="30" />
    <span class="mark-txt"><span class="mark-1">GED</span></span>
  </a>`;

/** Page courante, déduite de l'URL — fonctionne aussi en file://. */
function pageCourante() {
  const f = location.pathname.split('/').pop();
  return f && f.length ? f : 'accueil.html';
}

function poserCoque() {
  const hote = document.querySelector('[data-shell]');
  if (!hote) return;
  const ici = pageCourante();

  const onglets = RUBRIQUES.map(r =>
    `<a href="${r.h}"${r.h === ici ? ' class="on" aria-current="page"' : ''}>${r.t}</a>`
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
