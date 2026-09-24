/* ============================================================
   Coque commune — barre supérieure et onglets.
   Une seule source pour la navigation : ajouter une rubrique ici
   la fait apparaître sur les onze écrans, sans les rouvrir.
   ============================================================ */

/* `p` : pastille de la rubrique. Seule « Mes workflow » en porte une dans le
   produit — le nombre de signatures en attente (shell.html l. 65). */
const RUBRIQUES = [
  { t: 'Accueil',                h: 'accueil.html', i: `<svg class="lucide lucide-home" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8" /> <path d="M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /> </svg>` },
  { t: 'Mes workflow',           h: 'mes-workflow.html', p: 3, i: `<svg class="lucide lucide-circle-check" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <circle cx="12" cy="12" r="10" /> <path d="m9 12 2 2 4-4" /> </svg>` },
  { t: 'Espaces de travail',     h: 'espaces-de-travail.html', i: `<svg class="lucide lucide-layers" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z" /> <path d="M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12" /> <path d="M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17" /> </svg>` },
  { t: 'Documents',              h: 'documents.html', i: `<svg class="lucide lucide-file-text" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z" /> <path d="M14 2v5a1 1 0 0 0 1 1h5" /> <path d="M10 9H8" /> <path d="M16 13H8" /> <path d="M16 17H8" /> </svg>` },
  /* « Règles de Workflow » et non « Workflows » : c'est le nom de la rubrique
     dans le produit (shell.html l. 74). Le raccourci apprenait au client un
     nom d'écran qu'il ne retrouverait sur aucune barre de navigation. */
  { t: 'Règles de Workflow',     h: 'workflows.html', i: `<svg class="lucide lucide-workflow" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" > <rect width="8" height="8" x="3" y="3" rx="2" /> <path d="M7 11v4a2 2 0 0 0 2 2h4" /> <rect width="8" height="8" x="13" y="13" rx="2" /> </svg>` },
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
    `<a href="${r.h}"${cle(r.h) === ici ? ' class="on" aria-current="page"' : ''}>${r.i || ''}<span>${r.t}</span>${
      r.p ? `<span class="compte" aria-label="${r.p} document(s) en attente">${r.p}</span>` : ''
    }</a>`
  ).join('');

  /* Le bloc de compte porte le nom et l'ADRESSE E-MAIL, jamais un service ni
     un rang. La ligne affichait « Service documentation » : toute la couche
     des rôles a été retirée du produit (AccessGroup.java l. 14-18 — « toute
     écriture lui est ouverte »), et l'application n'a qu'un seul compte,
     l'administrateur (CompteSeeder.java l. 17-26). Afficher un service laisse
     croire à une hiérarchie de comptes qui n'existe pas.
     « Se déconnecter » est ajouté parce que le produit en a une (shell.html
     l. 17-49) : une maquette sans sortie raconte un produit sans sortie. */
  hote.insertAdjacentHTML('afterbegin', `
    <header class="topbar">
      ${MARQUE}
      <div class="spacer"></div>
      <div class="who">
        <span class="av">SB</span>
        <span>
          <span class="nm" style="display:block">Sara Bennani</span>
          <span class="rl">sara.bennani@marchica.ma</span>
        </span>
        <a class="bouton" href="#">Mon profil</a>
        <a class="bouton" href="login.html">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="m16 17 5-5-5-5" /><path d="M21 12H9" />
          </svg>
          Se déconnecter
        </a>
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

/**
 * Filtre de tableau : masque les lignes qui ne contiennent pas la saisie.
 *
 * `data-filtre` accepte plusieurs tableaux (« Mes workflow » en a deux depuis
 * la séparation À traiter / Historique). Chaque tableau met à jour l'état vide
 * DE SA propre enveloppe et le compteur DE SON propre pied : avec deux registres
 * dans une même carte, un `querySelector` global aurait écrit le compte du
 * second dans le pied du premier.
 *
 * `.vide.recherche` est visé explicitement : chaque écran porte désormais deux
 * états vides — la recherche infructueuse et le référentiel encore vierge.
 */
function brancherRecherche() {
  document.querySelectorAll('[data-filtre]').forEach(champ => {
    const tables = [...document.querySelectorAll(champ.dataset.filtre)];
    if (!tables.length) return;
    champ.addEventListener('input', () => {
      const q = champ.value.trim().toLowerCase();
      tables.forEach(table => {
        let vus = 0;
        table.querySelectorAll('tbody tr').forEach(tr => {
          const ok = !q || tr.textContent.toLowerCase().includes(q);
          tr.hidden = !ok;
          if (ok) vus++;
        });
        const enveloppe = table.closest('.registre-wrap') || table.parentElement;
        const vide = enveloppe.querySelector('.vide.recherche') || enveloppe.querySelector('.vide');
        if (vide) vide.hidden = vus > 0;
        const suivant = enveloppe.nextElementSibling;
        const pied = suivant && suivant.classList.contains('pied') ? suivant : document;
        pied.querySelectorAll('[data-compte]').forEach(c => { c.textContent = vus; });
      });
    });
  });
}

/**
 * Onglets internes à une carte (« À traiter » / « Historique »).
 * Purement présentiel : on montre l'un des volets déjà présents dans la page,
 * rien n'est chargé ni enregistré. Sans ce basculement, le second tableau —
 * qui n'a pas les mêmes colonnes que le premier — resterait invisible.
 */
function brancherOnglets() {
  document.querySelectorAll('[data-onglet]').forEach(bouton => {
    bouton.addEventListener('click', () => {
      const groupe = bouton.closest('.onglets');
      const carte = bouton.closest('.panel');
      if (!groupe || !carte) return;
      groupe.querySelectorAll('[data-onglet]').forEach(b => {
        const actif = b === bouton;
        b.classList.toggle('on', actif);
        b.setAttribute('aria-selected', actif ? 'true' : 'false');
      });
      carte.querySelectorAll('[data-volet]').forEach(v => {
        v.hidden = v.dataset.volet !== bouton.dataset.onglet;
      });
      const barre = carte.querySelector('[data-bulk]');
      if (barre) barre.hidden = true;
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
        /* Plusieurs porteurs possibles : le décompte de la barre, et celui que
           les actions groupées portent entre parenthèses — « Signer (2) ». */
        barre.querySelectorAll('[data-bulk-count]').forEach(t => { t.textContent = n; });
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


/* ============================================================
   DIALOGUE ET AVIS — communs a toutes les pages
   ------------------------------------------------------------
   La maquette montrait des actions qui ne demandaient rien et ne
   repondaient rien : on cliquait « Signer », il ne se passait rien.
   Or dans le produit, signer OUVRE une confirmation qui nomme le
   document, et rejeter EXIGE un motif — c'est ce qui distingue un
   visa d'un clic distrait. Ces deux moments manquaient a la
   demonstration.
   ============================================================ */

/**
 * Demande une confirmation. Rend une promesse :
 *   - sans champ  : true (confirme) ou false
 *   - avec champ  : le texte saisi, ou null si annule
 *
 * Le champ est OBLIGATOIRE quand il est demande : c'est la regle du
 * produit pour un rejet, un refus sans motif n'apprend rien a personne.
 */
function demander({ titre, message, confirmer = 'Confirmer', danger = false,
                    champ = null, champs = null }) {
  /* `champ` (une zone de motif) et `champs` (un formulaire) sont deux formes de
     la meme idee. La premiere existait d'abord ; on la garde plutot que de
     reecrire ses appelants, et on la traduit dans la seconde. */
  const liste = champs || (champ ? [{ nom: 'valeur', type: 'zone', ...champ }] : []);
  const seuleZone = !champs && champ;

  return new Promise(resoudre => {
    const voile = document.createElement('div');
    voile.className = 'voile';

    const ech = t => String(t == null ? '' : t).replace(/"/g, '&quot;');

    const balise = (c, i) => {
      const id = 'md-c' + i;
      const idErr = 'md-e' + i;
      const requis = c.requis !== false;
      /* `valeur` pre-remplit : sans elle, « Modifier » ouvrait un formulaire
         vide et l'operateur devait ressaisir ce qu'il venait de lire. */
      const v = c.valeur == null ? '' : String(c.valeur);

      /* UN SEUL controle par champ, pose DIRECTEMENT dans le bloc.
         L'enveloppe `<div class="champ">` a disparu : `.champ` est deja, plus
         haut dans la feuille, une boite bordee de 40px de haut destinee au
         controle LUI-MEME. L'employer comme enveloppe dessinait donc une
         seconde boite autour d'un input natif qui ne la remplissait pas —
         une boite dans une boite, le champ reel large de 177px dans un cadre
         de 414px. Le controle porte maintenant son propre style. */
      /* `erreur` : le produit ne dit pas « Ce champ est requis » partout — il
         nomme le champ (« Le code est obligatoire », « Le nom est
         obligatoire »). Le message par defaut reste celui du produit
         (confirm-dialog.html l. 14). */
      /* `name` DOUBLE `data-nom` : la lecture des valeurs se fait par
         `data-nom` (c'est la cle que rendent les appelants), mais un controle
         sans `name` n'est adressable ni par un test, ni par le remplissage
         automatique du navigateur. Meme chaine des deux cotes. */
      const attrs = `id="${id}" class="modale-controle" name="${ech(c.nom)}" data-nom="${c.nom}"`
        + ` aria-describedby="${idErr}"${requis ? ' aria-required="true"' : ''}`;
      const controle = c.type === 'zone'
        ? `<textarea ${attrs} rows="3" placeholder="${ech(c.exemple)}">${v}</textarea>`
        /* Le chevron du menu deroulant est dessine en FOND du <select>
           (`appearance: none` cote feuille de style) : aucune balise
           supplementaire, donc aucune enveloppe a redoubler, et le controle
           reste celui du navigateur pour le clavier. */
        : c.type === 'liste'
          ? `<select ${attrs}>${
              (c.options || []).map(o =>
                `<option value="${ech(o)}"${o === v ? ' selected' : ''}>${o}</option>`).join('')
            }</select>`
          : `<input ${attrs} type="text" value="${ech(v)}" placeholder="${ech(c.exemple)}" />`;
      return `<div class="modale-champ">
        <label for="${id}">${c.label}${requis ? ' <span class="requis">*</span>' : ''}</label>
        ${controle}
        <p class="err" id="${idErr}" hidden>${c.erreur || 'Ce champ est requis.'}</p>
      </div>`;
    };

    /* Trois zones distinctes : ce qu'on demande (tete), ce qu'on remplit
       (corps, seul a defiler quand le formulaire est long), ce qu'on decide
       (pied). L'ancien bloc melait le titre aux champs dans une seule boite. */
    voile.innerHTML = `
      <div class="modale${danger ? ' danger' : ''}" role="dialog" aria-modal="true" aria-labelledby="md-t">
        <div class="modale-tete">
          <h3 id="md-t"></h3>
          <button class="modale-fermer" type="button" data-annuler aria-label="Fermer">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="2" stroke-linecap="round" aria-hidden="true">
              <path d="M18 6 6 18" /><path d="m6 6 12 12" />
            </svg>
          </button>
        </div>
        <div class="modale-corps">
          <p class="modale-message"></p>
          ${liste.map(balise).join('')}
        </div>
        <div class="modale-pied">
          <button class="bouton" type="button" data-annuler>Annuler</button>
          <button class="btn-primaire${danger ? ' danger' : ''}" type="button" data-ok></button>
        </div>
      </div>`;
    voile.querySelector('h3').textContent = titre;
    const messageEl = voile.querySelector('.modale-message');
    if (message) messageEl.textContent = message; else messageEl.remove();
    voile.querySelector('[data-ok]').textContent = confirmer;
    document.body.appendChild(voile);

    const controles = [...voile.querySelectorAll('[data-nom]')];
    (controles[0] || voile.querySelector('[data-ok]')).focus();

    const fermer = valeur => {
      voile.remove();
      document.removeEventListener('keydown', auClavier);
      resoudre(valeur);
    };

    const valider = () => {
      if (!controles.length) return fermer(true);
      let manque = false;
      /* Remis a zero a CHAQUE tentative : garde en dehors, le focus ne se
         deplaçait plus au second essai. */
      let dejaFocalise = false;
      const valeurs = {};
      controles.forEach((ctrl, i) => {
        const bloc = ctrl.closest('.modale-champ');
        const requis = liste[i].requis !== false;
        const texte = (ctrl.value || '').trim();
        const enFaute = requis && !texte;
        bloc.querySelector('.err').hidden = !enFaute;
        /* La faute se voit AUSSI sur le controle : un message rouge sous un
           champ inchange laisse chercher lequel des six est en cause. */
        bloc.classList.toggle('invalide', enFaute);
        ctrl.setAttribute('aria-invalid', enFaute ? 'true' : 'false');
        if (enFaute) { manque = true; if (!dejaFocalise) { ctrl.focus(); dejaFocalise = true; } }
        valeurs[ctrl.dataset.nom] = texte;
      });
      if (manque) return;
      // Retro-compatibilite : un seul motif demande, un seul texte rendu.
      fermer(seuleZone ? valeurs.valeur : valeurs);
    };

    const auClavier = e => {
      if (e.key === 'Escape') fermer(controles.length ? null : false);
      /* Entree valide, sauf dans une zone multiligne ou elle sert a passer a
         la ligne. */
      if (e.key === 'Enter' && e.target.tagName !== 'TEXTAREA') valider();
    };
    /* La faute s'efface des que le champ est rempli : la garder affichee
       pendant la saisie corrigee accuse a tort. */
    controles.forEach(ctrl => ctrl.addEventListener('input', () => {
      const bloc = ctrl.closest('.modale-champ');
      if (!bloc.classList.contains('invalide') || !(ctrl.value || '').trim()) return;
      bloc.classList.remove('invalide');
      bloc.querySelector('.err').hidden = true;
      ctrl.setAttribute('aria-invalid', 'false');
    }));

    /* Deux sorties — la croix de l'entete et « Annuler » — un seul chemin. */
    voile.querySelectorAll('[data-annuler]').forEach(b =>
      b.addEventListener('click', () => fermer(controles.length ? null : false)));
    voile.querySelector('[data-ok]').addEventListener('click', valider);
    voile.addEventListener('mousedown', e => { if (e.target === voile) fermer(controles.length ? null : false); });
    document.addEventListener('keydown', auClavier);
  });
}

/** Confirme ce qui vient d'etre fait, puis s'efface. Ne bloque rien. */
function avis(texte, type = '') {
  let pile = document.querySelector('.avis-pile');
  if (!pile) { pile = document.createElement('div'); pile.className = 'avis-pile'; document.body.appendChild(pile); }
  const el = document.createElement('div');
  el.className = 'avis' + (type ? ' ' + type : '');
  el.setAttribute('role', 'status');
  el.textContent = texte;
  pile.appendChild(el);
  setTimeout(() => { el.classList.add('part'); setTimeout(() => el.remove(), 300); }, 2600);
}

document.addEventListener('DOMContentLoaded', () => {
  poserCoque();
  animerCompteurs();
  brancherRecherche();
  brancherSelection();
  brancherOnglets();
});
