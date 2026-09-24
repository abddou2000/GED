/* ============================================================
   TYPE DE DOCUMENT — la mecanique de l'ecran
   ------------------------------------------------------------
   Le type commande TOUT le depot : le dossier de destination, les
   formats acceptes, la taille maximale et le plan d'indexation. C'est
   la piece la plus structurante du referentiel, et son ecran ne
   repondait a aucun clic.

   Ce qui se joue desormais dans le DOM : creer, modifier, consulter la
   fiche, supprimer vers l'archive, restaurer, selectionner, rafraochir. Les compteurs et les deux etats vides
   suivent.

   Toutes les popups passent par `demander()` de shell.js, et toutes
   les confirmations par `avis()` : c'est ce qui les rend identiques
   d'un ecran a l'autre.
   ============================================================ */

(() => {
  const SEL = '#reg-types';
  const table = () => document.querySelector(SEL);
  const corps = () => document.querySelector(SEL + ' tbody');
  const tous  = () => [...corps().querySelectorAll('tr')];

  const IC = {
    oeil: `<svg class="lucide lucide-eye" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"/><circle cx="12" cy="12" r="3"/></svg>`,
    crayon: `<svg class="lucide lucide-pencil" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"/><path d="m15 5 4 4"/></svg>`,
    poubelle: `<svg class="lucide lucide-trash-2" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 11v6"/><path d="M14 11v6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
    restaurer: `<svg class="lucide lucide-refresh-cw" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/></svg>`,
  };

  const ACTIONS_ACTIF = `<button class="act" type="button" title="Ouvrir la fiche" aria-label="Ouvrir la fiche">${IC.oeil}</button>`
                      + `<button class="act" type="button" title="Modifier" aria-label="Modifier">${IC.crayon}</button>`
                      + `<button class="act danger" type="button" title="Supprimer" aria-label="Supprimer">${IC.poubelle}</button>`;
  const ACTIONS_ARCHIVE = `<button class="act" type="button" title="Restaurer" aria-label="Restaurer">${IC.restaurer}</button>`;

  /* ------------------------------------------------------------- lecture */

  const decouper = s => String(s || '').split(',').map(x => x.trim()).filter(Boolean);
  const VIDE = '—';

  const lire = tr => {
    const c = tr.querySelectorAll('td');
    return {
      tr,
      code:    c[1].textContent.trim(),
      nom:     c[2].querySelector('.nom')?.textContent.trim() ?? '',
      sous:    c[2].querySelector('.sous')?.textContent.trim() ?? '',
      espace:  c[3].textContent.trim(),
      plan:    c[4].textContent.trim(),
      formats: [...c[5].querySelectorAll('.jeton')].map(j => j.textContent.trim()).join(', '),
      taille:  c[6].textContent.trim(),
    };
  };

  function poserJetons(cellule, valeurs) {
    cellule.textContent = '';
    valeurs.forEach(v => {
      const s = document.createElement('span');
      s.className = 'jeton';
      s.textContent = v;
      cellule.appendChild(s);
    });
  }

  /** Absence de plan : le produit ecrit « — » en encre pale, pas « Aucun ». */
  function poserOuTiret(cellule, valeur) {
    cellule.textContent = '';
    if (valeur && valeur !== VIDE) { cellule.textContent = valeur; return; }
    const s = document.createElement('span');
    s.style.color = 'var(--encre-3)';
    s.textContent = VIDE;
    cellule.appendChild(s);
  }

  function ecrire(tr, v) {
    const c = tr.querySelectorAll('td');
    c[1].textContent = v.code;
    c[2].querySelector('.nom').textContent = v.nom;
    c[2].querySelector('.sous').textContent = v.sous || '';
    c[3].textContent = v.espace;
    poserOuTiret(c[4], v.plan);
    poserJetons(c[5], decouper(v.formats));
    c[6].textContent = v.taille;
  }

  function ligne(v) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><input type="checkbox" aria-label="Sélectionner" /></td>
      <td class="num"></td>
      <td><span class="nom"></span><span class="sous"></span></td>
      <td></td><td></td><td></td>
      <td class="num"></td>
      <td class="fin">${ACTIONS_ACTIF}</td>`;
    ecrire(tr, v);
    return tr;
  }

  /* ------------------------------------------------- vue actifs / archive
     Le masquage passe par `style.display` et NON par `hidden` : `hidden`
     appartient au filtre de recherche de shell.js, et deux mecanismes sur la
     meme propriete se volent la main. */

  let vue = 'actifs';
  const caches = new Set();
  const archivee = tr => tr.dataset.archive === '1';
  const dansLaVue = tr => (vue === 'archive') === archivee(tr);

  function majVierge(vierge) {
    if (!vierge) return;
    if (!vierge.dataset.t0) {
      vierge.dataset.t0 = vierge.querySelector('.t').textContent;
      vierge.dataset.s0 = vierge.querySelector('.s').textContent;
    }
    const arch = vue === 'archive';
    vierge.querySelector('.t').textContent = arch ? "L'archive est vide" : vierge.dataset.t0;
    vierge.querySelector('.s').textContent = arch
      ? "Aucun type supprimé n'attend d'être restauré."
      : vierge.dataset.s0;
  }

  function majColonnes() {
    [...table().querySelectorAll('thead th')].forEach((th, i) => { th.style.display = caches.has(i) ? 'none' : ''; });
    tous().forEach(tr => tr.querySelectorAll('td').forEach((td, i) => { td.style.display = caches.has(i) ? 'none' : ''; }));
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

  /* Les listes proposees viennent de ce que l'ecran montre deja : la maquette
     ne doit pas inventer un espace ou un plan que le client ne retrouverait
     sur aucun autre ecran. */
  const valeursDe = colonne => [...new Set(tous()
    .map(tr => tr.querySelectorAll('td')[colonne]?.textContent.trim())
    .filter(v => v && v !== VIDE))];

  /* Libelles, exemples, obligations et messages d'erreur du formulaire du
     produit (type-document-form.html l. 6-73). « Description » y est
     OBLIGATOIRE (type-document-form.ts l. 48), le champ des formats s'appelle
     « Type de fichier », et l'absence de plan se choisit par « — Aucun — ». */
  const SANS_PLAN = '— Aucun —';
  const champsType = (v = {}) => [
    { nom: 'code',    label: 'Code', exemple: 'Ex. TD-FACT', valeur: v.code,
      erreur: 'Le code est obligatoire' },
    { nom: 'nom',     label: 'Type de document', exemple: 'Ex. Facture', valeur: v.nom,
      erreur: 'Le type est obligatoire' },
    { nom: 'sous',    label: 'Description', exemple: 'Entrez une description', valeur: v.sous,
      erreur: 'La description est obligatoire' },
    { nom: 'espace',  label: 'Espace de travail', type: 'liste', options: valeursDe(3), valeur: v.espace,
      erreur: "L'espace de travail est obligatoire" },
    { nom: 'plan',    label: "Plan d'indexation", type: 'liste',
      options: [SANS_PLAN, ...valeursDe(4)], requis: false,
      valeur: !v.plan || v.plan === VIDE ? SANS_PLAN : v.plan },
    { nom: 'formats', label: 'Type de fichier', exemple: 'Ex. pdf, docx', valeur: v.formats,
      erreur: 'Au moins un format est requis' },
    { nom: 'taille',  label: 'Taille max (Mo)', exemple: 'Ex. 10',
      valeur: v.taille ? String(parseInt(v.taille, 10) || 10) : '10',
      erreur: '5 Mo minimum' },
  ];

  /** Normalise ce que rend le formulaire vers ce que le tableau affiche. */
  const normaliser = v => ({
    ...v,
    plan: v.plan === SANS_PLAN ? VIDE : v.plan,
    taille: `${parseInt(v.taille, 10)} Mo`,
  });

  /* ------------------------------------------------------------ decisions */

  /** Le produit refuse en dessous de 5 Mo (type-document-form.ts l. 52). */
  const tailleInvalide = v => !(parseInt(v.taille, 10) >= 5);

  async function creer() {
    const v = await demander({ titre: 'Créer un type de document', confirmer: 'Ajouter', champs: champsType() });
    if (!v) return;
    if (tous().some(tr => lire(tr).code.toLowerCase() === v.code.toLowerCase())) {
      avis(`Le code « ${v.code} » est déjà utilisé.`, 'ko');
      return;
    }
    if (tailleInvalide(v)) { avis('5 Mo minimum', 'ko'); return; }
    vue = 'actifs';
    majBasculeArchive();
    corps().prepend(ligne(normaliser(v)));
    majTout();
    avis('Type de document créé.', 'ok');
  }

  async function modifier(tr) {
    const v = await demander({
      titre: 'Modifier le type de document',
      confirmer: 'Mettre à jour',
      champs: champsType(lire(tr)),
    });
    if (!v) return;
    if (tailleInvalide(v)) { avis('5 Mo minimum', 'ko'); return; }
    ecrire(tr, normaliser(v));
    majTout();
    avis('Type de document modifié.', 'ok');
  }

  /**
   * Fiche en lecture. Les cartouches, leurs libelles et leur ordre viennent de
   * la page de detail du produit (type-document-detail.html l. 30-65) : Code,
   * Espace de travail, Types autorises, Taille maximale, Plan d'indexation,
   * Description. La phrase sur le depot sans index a ete retiree — la fiche du
   * produit ne commente rien, elle expose.
   */
  async function fiche(tr) {
    const e = lire(tr);
    await demander({
      titre: e.nom,
      message: `Code : ${e.code}`
             + `\nEspace de travail : ${e.espace}`
             + `\nTypes autorisés : ${e.formats || VIDE}`
             + `\nTaille maximale : ${e.taille}`
             + `\nPlan d'indexation : ${e.plan === VIDE ? 'Aucun' : e.plan}`
             + `\nDescription : ${e.sous || VIDE}`,
      confirmer: 'Fermer',
    });
  }

  /* « Supprimer ce type » pour une ligne, « Supprimer la sélection » pour un
     lot (type-document-list.ts l. 183-184 et l. 212-214). */
  async function supprimer(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander(n === 1
      ? { titre: 'Supprimer ce type',
          message: `« ${lire(lignes[0]).nom} » sera déplacé vers la corbeille.`,
          confirmer: 'Supprimer', danger: true }
      : { titre: 'Supprimer la sélection',
          message: `Voulez-vous supprimer ${n} type(s) ?`,
          confirmer: 'Supprimer', danger: true });
    if (!ok) return;
    lignes.forEach(tr => {
      tr.dataset.archive = '1';
      tr.querySelector('td.fin').innerHTML = ACTIONS_ARCHIVE;
      const c = tr.querySelector('input[type=checkbox]');
      if (c) c.checked = false;
    });
    majTout();
    avis(n === 1 ? 'Type de document supprimé.' : `${n} type(s) supprimé(s).`, 'ok');
  }

  /** Restauration d'une ligne : le produit ne demande rien (l. 225-230). */
  function restaurer(tr) {
    delete tr.dataset.archive;
    tr.querySelector('td.fin').innerHTML = ACTIONS_ACTIF;
    const c = tr.querySelector('input[type=checkbox]');
    if (c) c.checked = false;
    majTout();
    avis('Type de document restauré.', 'ok');
  }

  /** Restauration en lot : elle, le produit la confirme. */
  async function restaurerLot(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander({
      titre: 'Restaurer la sélection',
      message: `Voulez-vous restaurer ${n} type(s) ?`,
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
    avis(`${n} type(s) restauré(s).`, 'ok');
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
    boutonArchive.title = arch ? 'Revenir aux types actifs' : 'Voir les types archivés';
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
        else if (titre === 'Ouvrir la fiche') fiche(tr);
        return;
      }
      const cta = e.target.closest('.head .cta');
      if (cta) { e.preventDefault(); creer(); }
    });

    document.addEventListener('change', e => { if (e.target.closest(SEL)) majSelection(); });

    document.querySelectorAll('[data-filtre]').forEach(ch => ch.addEventListener('input', majTout));

    document.querySelectorAll('[data-bulk] [data-bulk-action]').forEach(b => b.addEventListener('click', () => {
      const lignes = tous().filter(tr => dansLaVue(tr) && tr.querySelector('input:checked'));
      (vue === 'archive' ? restaurerLot : supprimer)(lignes);
    }));

    boutonArchive?.addEventListener('click', () => {
      vue = vue === 'archive' ? 'actifs' : 'archive';
      majBasculeArchive();
      majTout();
      avis(vue === 'archive' ? 'Archive affichée.' : 'Types actifs affichés.', '');
    });

    boutonNomme('Colonnes')?.addEventListener('click', colonnes);
    boutonNomme('Rafraîchir')?.addEventListener('click', () => { majTout(); avis('Liste à jour.', ''); });
    boutonNomme('Sélectionner')?.addEventListener('click', () => {
      const lignes = tous().filter(tr => dansLaVue(tr) && !tr.hidden);
      const toutes = lignes.every(tr => tr.querySelector('input[type=checkbox]')?.checked);
      lignes.forEach(tr => { const c = tr.querySelector('input[type=checkbox]'); if (c) c.checked = !toutes; });
      majSelection();
    });
  });
})();
