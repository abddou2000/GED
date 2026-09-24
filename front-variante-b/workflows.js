/* ============================================================
   RÈGLES DE WORKFLOW — la mecanique de l'ecran
   ------------------------------------------------------------
   L'ecran ne chargeait que la coque : « Creer Regles de Workflow »
   n'ouvrait rien, le crayon et la poubelle ne repondaient pas, et le
   pied annoncait un total qui ne bougeait jamais.

   Ce qui se joue ici, et qui se joue desormais dans le DOM :
     - creer une regle, avec ses etapes ordonnees ;
     - la modifier — le formulaire s'ouvre REMPLI ;
     - la supprimer : elle part a l'archive, elle ne disparaot pas ;
     - la restaurer depuis la bascule « Archive » ;
     - tout selectionner, rafraochir ;
     - les compteurs et les deux etats vides suivent, toujours.

   Aucun reseau, aucun cadre applicatif. Toutes les popups passent par
   `demander()` et toutes les confirmations par `avis()` (shell.js) :
   c'est ce qui garantit qu'elles se ressemblent d'un ecran a l'autre.
   ============================================================ */

(() => {
  const SEL = '#reg-workflows';
  const table = () => document.querySelector(SEL);
  const corps = () => document.querySelector(SEL + ' tbody');
  const tous  = () => [...corps().querySelectorAll('tr')];

  /* Tracés repris a l'identique de `espaces-de-travail.js` : un meme geste
     doit porter la meme icone sur les onze ecrans. */
  const IC = {
    crayon: `<svg class="lucide lucide-pencil" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"/><path d="m15 5 4 4"/></svg>`,
    poubelle: `<svg class="lucide lucide-trash-2" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 11v6"/><path d="M14 11v6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
    restaurer: `<svg class="lucide lucide-refresh-cw" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/></svg>`,
  };

  const ACTIONS_ACTIF = `<button class="act" type="button" title="Modifier" aria-label="Modifier">${IC.crayon}</button>`
                      + `<button class="act danger" type="button" title="Supprimer" aria-label="Supprimer">${IC.poubelle}</button>`;
  const ACTIONS_ARCHIVE = `<button class="act" type="button" title="Restaurer" aria-label="Restaurer">${IC.restaurer}</button>`;

  /* ------------------------------------------------------------- lecture */

  const decouper = s => String(s || '').split(',').map(x => x.trim()).filter(Boolean);

  const lire = tr => {
    const c = tr.querySelectorAll('td');
    return {
      tr,
      nom:     c[1].querySelector('.nom')?.textContent.trim() ?? '',
      sous:    c[1].querySelector('.sous')?.textContent.trim() ?? '',
      /* Le rang est affiche, pas saisi : on le retire a la lecture pour ne pas
         le faire ressaisir, on le recalcule a l'ecriture. */
      etapes:  [...c[2].querySelectorAll('.jeton')].map(j => j.textContent.trim().replace(/^\d+\s+/, '')).join(', '),
      espaces: c[3].textContent.trim(),
    };
  };

  /** Pose des jetons SANS interpreter le texte saisi comme du HTML. */
  function poserJetons(cellule, valeurs, numerote) {
    cellule.textContent = '';
    valeurs.forEach((v, i) => {
      const s = document.createElement('span');
      s.className = 'jeton';
      s.textContent = numerote ? `${i + 1} ${v}` : v;
      cellule.appendChild(s);
    });
  }

  function ecrire(tr, v) {
    const c = tr.querySelectorAll('td');
    c[1].querySelector('.nom').textContent = v.nom;
    c[1].querySelector('.sous').textContent = v.sous || '';
    poserJetons(c[2], decouper(v.etapes), true);
    c[3].textContent = v.espaces;
  }

  function ligne(v) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><input type="checkbox" aria-label="Sélectionner" /></td>
      <td><span class="nom"></span><span class="sous"></span></td>
      <td class="c-etapes"></td>
      <td></td>
      <td class="fin">${ACTIONS_ACTIF}</td>`;
    ecrire(tr, v);
    return tr;
  }

  /* ------------------------------------------------- vue actifs / archive
     La suppression est reversible : la ligne reste dans le tableau, marquee
     `data-archive`, et la bascule choisit laquelle des deux populations on
     regarde.

     Le masquage passe par `style.display` et NON par l'attribut `hidden` :
     `hidden` appartient deja au filtre de recherche de shell.js, et deux
     mecanismes sur la meme propriete se volent la main. Les deux conditions
     se cumulent naturellement. */

  let vue = 'actifs';
  const caches = new Set();                       // colonnes masquees
  const archivee = tr => tr.dataset.archive === '1';
  const dansLaVue = tr => (vue === 'archive') === archivee(tr);

  function majVierge(vierge) {
    if (!vierge) return;
    if (!vierge.dataset.t0) {
      vierge.dataset.t0 = vierge.querySelector('.t').textContent;
      vierge.dataset.s0 = vierge.querySelector('.s').textContent;
    }
    /* Cet ecran-ci n'a qu'UN etat vide dans le produit : sa ligne « aucune
       donnee » ne se dedouble pas selon l'archive (workflow-list.html
       l. 121-129). Le texte ne change donc pas d'une vue a l'autre. */
    vierge.querySelector('.t').textContent = vierge.dataset.t0;
    vierge.querySelector('.s').textContent = vierge.dataset.s0;
  }

  function majColonnes() {
    const ths = [...table().querySelectorAll('thead th')];
    ths.forEach((th, i) => { th.style.display = caches.has(i) ? 'none' : ''; });
    tous().forEach(tr => tr.querySelectorAll('td').forEach((td, i) => {
      td.style.display = caches.has(i) ? 'none' : '';
    }));
  }

  /** Le verbe du bouton groupe suit la vue, comme dans le produit. */
  function majBoutonLot() {
    const barre = document.querySelector('[data-bulk]');
    if (!barre) return;
    const arch = vue === 'archive';
    const b = barre.querySelector('[data-bulk-action]');
    if (b) {
      b.querySelector('[data-bulk-label]').textContent = arch ? 'Restaurer' : 'Supprimer';
      b.classList.toggle('danger', !arch);
    }
    const note = barre.querySelector('.muet');
    if (note) note.hidden = arch;
  }

  /** Barre d'action groupee : elle compte ce qui est coche ET visible. */
  function majSelection() {
    const barre = document.querySelector('[data-bulk]');
    majBoutonLot();
    const cases = tous().filter(dansLaVue).map(tr => tr.querySelector('input[type=checkbox]'));
    const n = cases.filter(c => c && c.checked).length;
    if (barre) {
      barre.hidden = n === 0;
      barre.querySelectorAll('[data-bulk-count]').forEach(t => { t.textContent = n; });
    }
    const maitre = table().querySelector('thead input[type=checkbox]');
    if (maitre) {
      maitre.checked = n > 0 && n === cases.length;
      maitre.indeterminate = n > 0 && n < cases.length;
    }
  }

  function majTout() {
    const lignes = tous();
    lignes.forEach(tr => { tr.style.display = dansLaVue(tr) ? '' : 'none'; });
    const enVue = lignes.filter(dansLaVue);
    const visibles = enVue.filter(tr => !tr.hidden);

    document.querySelectorAll('.pied [data-compte]').forEach(e => { e.textContent = visibles.length; });

    const env = document.querySelector('.registre-wrap');
    const vierge = env.querySelector('.vide.vierge');
    const rech = env.querySelector('.vide.recherche');
    table().hidden = enVue.length === 0;
    majVierge(vierge);
    if (vierge) vierge.hidden = enVue.length !== 0;
    if (rech) rech.hidden = !(enVue.length > 0 && visibles.length === 0);

    majColonnes();
    majSelection();
  }

  /* ---------------------------------------------------------- formulaire */

  const valeursDe = colonne => [...new Set(tous()
    .map(tr => tr.querySelectorAll('td')[colonne]?.textContent.trim())
    .filter(Boolean))];

  /* Libelles et exemples du formulaire du produit (workflow-form.html l. 8-17
     et l. 50-56) : « Nom de la règle », « Étapes du circuit », et les exemples
     ecrits « Ex. : … », deux-points compris. */
  const champsRegle = (v = {}) => [
    { nom: 'nom',     label: 'Nom de la règle', exemple: 'Ex. : Validation comptable', valeur: v.nom,
      erreur: 'Le nom est obligatoire' },
    { nom: 'sous',    label: 'Description', exemple: 'Ce que le circuit contrôle', requis: false, valeur: v.sous },
    { nom: 'etapes',  label: 'Étapes du circuit',
      exemple: 'Ex. : Contrôle, Ordonnancement, Validation', valeur: v.etapes,
      erreur: 'Au moins une étape est requise.' },
    { nom: 'espaces', label: 'Espaces de travail', exemple: 'Ex. Comptabilité, Direction',
      requis: false, valeur: v.espaces },
  ];

  /* ------------------------------------------------------------ decisions */

  async function creer() {
    const v = await demander({
      titre: 'Créer Règles de Workflow',
      confirmer: 'Créer',
      champs: champsRegle(),
    });
    if (!v) return;
    if (tous().some(tr => lire(tr).nom.toLowerCase() === v.nom.toLowerCase())) {
      avis(`La règle « ${v.nom} » existe déjà.`, 'ko');
      return;
    }
    vue = 'actifs';
    majBasculeArchive();
    corps().prepend(ligne(v));
    majTout();
    avis('Règle de workflow créée.', 'ok');
  }

  async function modifier(tr) {
    const v = await demander({
      titre: 'Modifier Règles de Workflow',
      confirmer: 'Modifier',
      champs: champsRegle(lire(tr)),
    });
    if (!v) return;
    ecrire(tr, v);
    majTout();
    avis('Règle de workflow modifiée.', 'ok');
  }

  /* « Supprimer cette règle » pour une ligne, « Supprimer la sélection » pour
     un lot — et c'est bien « élément(s) » que le produit compte ici
     (workflow-list.ts l. 197-198 et l. 222-223). */
  async function supprimer(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander(n === 1
      ? { titre: 'Supprimer cette règle',
          message: `« ${lire(lignes[0]).nom} » sera déplacée vers la corbeille.`,
          confirmer: 'Supprimer', danger: true }
      : { titre: 'Supprimer la sélection',
          message: `Voulez-vous supprimer ${n} élément(s) ?`,
          confirmer: 'Supprimer', danger: true });
    if (!ok) return;
    lignes.forEach(tr => {
      tr.dataset.archive = '1';
      tr.querySelector('td.fin').innerHTML = ACTIONS_ARCHIVE;
      const c = tr.querySelector('input[type=checkbox]');
      if (c) c.checked = false;
    });
    majTout();
    avis(n === 1 ? 'Règle de workflow supprimée.' : `${n} élément(s) supprimé(s).`, 'ok');
  }

  /** Restauration d'une ligne : le produit ne demande rien (l. 210-214). */
  function restaurer(tr) {
    delete tr.dataset.archive;
    tr.querySelector('td.fin').innerHTML = ACTIONS_ACTIF;
    const c = tr.querySelector('input[type=checkbox]');
    if (c) c.checked = false;
    majTout();
    avis('Règle de workflow restaurée.', 'ok');
  }

  /** Restauration en lot : elle, le produit la confirme. */
  async function restaurerLot(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander({
      titre: 'Restaurer la sélection',
      message: `Voulez-vous restaurer ${n} élément(s) ?`,
      confirmer: 'Restaurer',
    });
    if (!ok) return;
    lignes.forEach(tr => {
      delete tr.dataset.archive;
      tr.querySelector('td.fin').innerHTML = ACTIONS_ACTIF;
      const c = tr.querySelector('input[type=checkbox]');
      if (c) c.checked = false;
    });
    majTout();
    avis(`${n} élément(s) restauré(s).`, 'ok');
  }

  /* Le bouton « Colonnes » a ete retire des barres d'outils pendant la mise a
     niveau : les sept registres n'exposent plus que Selectionner / Rafraochir /
     Archive. La fonction reste ecrite et branchee en option — elle se rebranche
     d'elle-meme si le bouton revient, et ne coute rien tant qu'il est absent. */
  async function colonnes() {
    const ths = [...table().querySelectorAll('thead th')]
      .map((th, i) => ({ th, i }))
      .filter(o => !o.th.classList.contains('c-sel') && !o.th.classList.contains('c-act'));
    const v = await demander({
      titre: 'Colonnes affichées',
      message: 'Choisissez la colonne à afficher ou à masquer.',
      confirmer: 'Appliquer',
      champs: [
        { nom: 'colonne', label: 'Colonne', type: 'liste', options: ths.map(o => o.th.textContent.trim()) },
        { nom: 'etat', label: 'Affichage', type: 'liste', options: ['Masquer', 'Afficher'] },
      ],
    });
    if (!v) return;
    const cible = ths.find(o => o.th.textContent.trim() === v.colonne);
    if (!cible) return;
    if (v.etat === 'Masquer') caches.add(cible.i); else caches.delete(cible.i);
    majColonnes();
    avis(`Colonne « ${v.colonne} » ${v.etat === 'Masquer' ? 'masquée' : 'affichée'}.`, '');
  }

  /* ------------------------------------------------------------ coquille */

  const boutonNomme = txt => [...document.querySelectorAll('.outils .droite .bouton')]
    .find(b => b.textContent.trim() === txt);

  let boutonArchive = null;
  function majBasculeArchive() {
    if (!boutonArchive) return;
    const arch = vue === 'archive';
    boutonArchive.classList.toggle('on', arch);
    boutonArchive.setAttribute('aria-pressed', String(arch));
    const t = [...boutonArchive.childNodes].reverse().find(n => n.nodeType === 3 && n.textContent.trim());
    if (t) t.nodeValue = arch ? ' Actifs' : ' Archive';
    boutonArchive.title = arch ? 'Revenir aux règles actives' : 'Voir les règles archivées';
  }

  document.addEventListener('DOMContentLoaded', () => {
    if (!corps()) return;

    boutonArchive = boutonNomme('Archive');
    majBasculeArchive();
    majTout();

    document.addEventListener('click', e => {
      const act = e.target.closest(SEL + ' button.act');
      if (act) {
        const tr = act.closest('tr');
        const titre = act.getAttribute('title');
        if (titre === 'Modifier') modifier(tr);
        else if (titre === 'Supprimer') supprimer([tr]);
        else if (titre === 'Restaurer') restaurer(tr);
        return;
      }
      const cta = e.target.closest('.head .cta');
      if (cta) { e.preventDefault(); creer(); }
    });

    /* Les lignes naissent et meurent : un ecouteur pose sur chaque case serait
       perdu des la premiere creation. */
    document.addEventListener('change', e => {
      if (e.target.closest(SEL)) majSelection();
    });

    /* Le filtre de shell.js ecrit son propre total ; il ignore la bascule
       Archive. On recompte APRES lui — notre ecouteur est pose en second. */
    document.querySelectorAll('[data-filtre]').forEach(ch => ch.addEventListener('input', majTout));

    document.querySelectorAll('[data-bulk] [data-bulk-action]').forEach(b => b.addEventListener('click', () => {
      const lignes = tous().filter(tr => dansLaVue(tr) && tr.querySelector('input:checked'));
      (vue === 'archive' ? restaurerLot : supprimer)(lignes);
    }));

    boutonArchive?.addEventListener('click', () => {
      vue = vue === 'archive' ? 'actifs' : 'archive';
      majBasculeArchive();
      majTout();
      avis(vue === 'archive' ? 'Archive affichée.' : 'Règles actives affichées.', '');
    });

    boutonNomme('Colonnes')?.addEventListener('click', colonnes);

    boutonNomme('Rafraîchir')?.addEventListener('click', () => { majTout(); avis('Liste à jour.', ''); });

    boutonNomme('Sélectionner')?.addEventListener('click', () => {
      const lignes = tous().filter(tr => dansLaVue(tr) && !tr.hidden);
      const toutes = lignes.every(tr => tr.querySelector('input[type=checkbox]')?.checked);
      lignes.forEach(tr => {
        const c = tr.querySelector('input[type=checkbox]');
        if (c) c.checked = !toutes;
      });
      majSelection();
    });
  });
})();
