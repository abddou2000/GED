/* ============================================================
   GROUPE D'ACCÈS — la mecanique de l'ecran
   ------------------------------------------------------------
   Un groupe rattache des utilisateurs a des espaces de travail. Il
   ORGANISE, il ne restreint rien : aucun dialogue de cet ecran ne doit
   parler de droit, de role ou de permission — c'est la faute deja
   payee deux fois sur cette page.

   Ce qui se joue desormais dans le DOM : creer, consulter la fiche,
   modifier, supprimer vers l'archive, restaurer, selectionner, rafraochir. Les compteurs et les deux etats vides
   suivent.

   Toutes les popups passent par `demander()` de shell.js, toutes les
   confirmations par `avis()`.
   ============================================================ */

(() => {
  const SEL = '#reg-groupes';
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

  const lire = tr => {
    const c = tr.querySelectorAll('td');
    return {
      tr,
      code:    c[1].textContent.trim(),
      nom:     c[2].querySelector('.nom')?.textContent.trim() ?? '',
      sous:    c[2].querySelector('.sous')?.textContent.trim() ?? '',
      espaces: c[3].textContent.trim(),
      membres: c[4].textContent.trim(),
    };
  };

  function ecrire(tr, v) {
    const c = tr.querySelectorAll('td');
    c[1].textContent = v.code;
    c[2].querySelector('.nom').textContent = v.nom;
    c[2].querySelector('.sous').textContent = v.sous || '';
    c[3].textContent = v.espaces;
    c[4].textContent = v.membres;
  }

  function ligne(v) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><input type="checkbox" aria-label="Sélectionner" /></td>
      <td class="num"></td>
      <td><span class="nom"></span><span class="sous"></span></td>
      <td></td>
      <td class="num"></td>
      <td class="fin">${ACTIONS_ACTIF}</td>`;
    ecrire(tr, v);
    return tr;
  }

  /* ------------------------------------------------- vue actifs / archive */

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
      ? "Aucun groupe supprimé n'attend d'être restauré."
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

  /* Libelles du formulaire du produit (access-group-form.html l. 8-44) :
     « Code », « Nom de group » (sic, c'est le libelle du produit) et
     « Liste des WorkSpaces », qui n'y porte aucune obligation
     (access-group-form.ts l. 45-50). */
  const champsGroupe = (v = {}) => [
    { nom: 'code',    label: 'Code', exemple: 'Ex. AG-ADMIN', valeur: v.code,
      erreur: 'Le code est obligatoire' },
    { nom: 'nom',     label: 'Nom de group', exemple: 'Entrez le nom du groupe', valeur: v.nom,
      erreur: 'Le nom est obligatoire' },
    /* Aucun sous-titre ne doit decrire ce qu'un groupe a le DROIT de faire :
       il organise, il ne restreint rien. L'exemple le montre. */
    { nom: 'sous',    label: 'Description', exemple: 'Ex. Suivi de l\'ensemble des espaces',
      requis: false, valeur: v.sous },
    { nom: 'espaces', label: 'Liste des WorkSpaces', requis: false,
      exemple: 'Ex. Comptabilité, Direction', valeur: v.espaces },
    { nom: 'membres', label: 'Membres', exemple: 'Ex. 4', requis: false, valeur: v.membres },
  ];

  /* ------------------------------------------------------------ decisions */

  async function creer() {
    const v = await demander({ titre: "Créer un groupe d'accès", confirmer: 'Ajouter', champs: champsGroupe() });
    if (!v) return;
    if (tous().some(tr => lire(tr).code.toLowerCase() === v.code.toLowerCase())) {
      avis(`Le code « ${v.code} » est déjà utilisé.`, 'ko');
      return;
    }
    vue = 'actifs';
    majBasculeArchive();
    corps().prepend(ligne(v));
    majTout();
    avis("Groupe d'accès créé.", 'ok');
  }

  async function modifier(tr) {
    const v = await demander({
      titre: "Modifier le groupe d'accès",
      confirmer: 'Mettre à jour',
      champs: champsGroupe(lire(tr)),
    });
    if (!v) return;
    ecrire(tr, v);
    majTout();
    avis("Groupe d'accès modifié.", 'ok');
  }

  /**
   * Fiche en lecture. Libelles et ordre de la page de detail du produit
   * (access-group-detail.html l. 32-34 puis l. 41-57) : Code, Espaces
   * couverts, Membres, puis la liste des espaces de travail. La phrase de
   * conclusion sur ce qu'un groupe restreint ou non a ete retiree : c'est un
   * commentaire de code, la fiche du produit ne l'affiche pas.
   */
  async function fiche(tr) {
    const e = lire(tr);
    const espaces = e.espaces.split(',').map(x => x.trim()).filter(Boolean);
    await demander({
      titre: e.nom,
      message: `Code : ${e.code}`
             + `\nEspaces couverts : ${espaces.length}`
             + `\nMembres : ${e.membres}`
             + `\n\nEspaces de travail : ${espaces.length ? espaces.join(', ') : 'Aucun espace de travail assigné.'}`
             + (e.sous ? `\n\n${e.sous}` : ''),
      confirmer: 'Fermer',
    });
  }

  /* « Supprimer ce groupe » pour une ligne, « Supprimer la sélection » pour un
     lot (access-group-list.ts l. 170-171 et l. 217-219). */
  async function supprimer(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander(n === 1
      ? { titre: 'Supprimer ce groupe',
          message: `« ${lire(lignes[0]).nom} » sera déplacé vers la corbeille.`,
          confirmer: 'Supprimer', danger: true }
      : { titre: 'Supprimer la sélection',
          message: `Voulez-vous supprimer ${n} groupe(s) ?`,
          confirmer: 'Supprimer', danger: true });
    if (!ok) return;
    lignes.forEach(tr => {
      tr.dataset.archive = '1';
      tr.querySelector('td.fin').innerHTML = ACTIONS_ARCHIVE;
      const c = tr.querySelector('input[type=checkbox]');
      if (c) c.checked = false;
    });
    majTout();
    avis(n === 1 ? "Groupe d'accès supprimé." : `${n} groupe(s) supprimé(s).`, 'ok');
  }

  /** Restauration d'une ligne : le produit ne demande rien (l. 230-235). */
  function restaurer(tr) {
    delete tr.dataset.archive;
    tr.querySelector('td.fin').innerHTML = ACTIONS_ACTIF;
    const c = tr.querySelector('input[type=checkbox]');
    if (c) c.checked = false;
    majTout();
    avis("Groupe d'accès restauré.", 'ok');
  }

  /** Restauration en lot : elle, le produit la confirme. */
  async function restaurerLot(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander({
      titre: 'Restaurer la sélection',
      message: `Voulez-vous restaurer ${n} groupe(s) ?`,
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
    avis(`${n} groupe(s) restauré(s).`, 'ok');
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
    boutonArchive.title = arch ? 'Revenir aux groupes actifs' : 'Voir les groupes archivés';
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
      avis(vue === 'archive' ? 'Archive affichée.' : 'Groupes actifs affichés.', '');
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
