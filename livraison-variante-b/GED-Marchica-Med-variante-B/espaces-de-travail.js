/* ============================================================
   ESPACES DE TRAVAIL — la mecanique de l'ecran
   ------------------------------------------------------------
   POURQUOI CE FICHIER EXISTE
   Trois manques, tous releves par le client :

   1. La vue ARBRE n'existait pas. Le produit ouvre pourtant cet ecran
      sur l'arborescence, pas sur un tableau a plat — et un classement
      documentaire SE LIT en arbre. Une maquette qui n'en montre qu'une
      liste plate raconte un autre produit.
   2. « Creer un espace » ne creait rien.
   3. Rien ne bougeait : modifier, supprimer, archiver, restaurer.

   L'arbre est CONSTRUIT depuis les lignes du tableau, jamais ecrit a la
   main : deux vues, une seule verite. Ecrites separement, elles auraient
   diverge des la premiere modification — c'est exactement le defaut qu'on
   vient de corriger ailleurs dans ce projet.

   Aucun reseau, aucun cadre applicatif : tout se passe dans le DOM.
   ============================================================ */

(() => {
  const corps = () => document.querySelector('#reg-espaces tbody');
  const arbre = () => document.querySelector('.arbre');

  /* Les proprietaires et les circuits proposes a la creation viennent de ce qui
     existe deja dans le tableau : la maquette ne doit pas inventer des noms que
     le client ne retrouverait nulle part. */
  const valeursDe = colonne => [...new Set([...corps().querySelectorAll('tr')]
    .map(tr => tr.querySelectorAll('td')[colonne]?.textContent.trim())
    .filter(Boolean))];

  const IC = {
    dossier: `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/></svg>`,
    chevron: `<svg class="chev" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="m9 18 6-6-6-6"/></svg>`,
    menu: `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/></svg>`,
    crayon: `<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"/><path d="m15 5 4 4"/></svg>`,
    poubelle: `<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 11v6"/><path d="M14 11v6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
    restaurer: `<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/></svg>`,
  };

  /* ------------------------------------------------------ lecture du tableau */

  const lire = tr => {
    const c = tr.querySelectorAll('td');
    return {
      tr,
      code:    c[1].textContent.trim(),
      nom:     c[2].querySelector('.nom')?.textContent.trim() ?? '',
      sous:    c[2].querySelector('.sous')?.textContent.trim() ?? '',
      owner:   c[3].textContent.trim(),
      circuit: c[4].textContent.trim(),
      parent:  c[5].textContent.trim(),
      statut:  c[6].querySelector('.etat')?.textContent.trim() ?? 'Actif',
    };
  };

  const tous = () => [...corps().querySelectorAll('tr')].map(lire);
  const actifs = () => tous().filter(e => e.statut !== 'Archivé');

  /* ------------------------------------------------------------------ arbre */

  /**
   * Batit l'arbre depuis les lignes actives.
   *
   * <p>La hierarchie est portee par la colonne « Dossier parent » : elle nomme
   * le parent, ou « Racine ». On ne s'appuie pas sur l'ordre des lignes — un
   * tri par nom le casserait aussitot.</p>
   */
  function rebatirArbre() {
    const liste = actifs();
    const enfantsDe = nom => liste.filter(e => e.parent === nom);
    const racines = liste.filter(e => e.parent === 'Racine' || !liste.some(x => x.nom === e.parent));

    const noeud = (e, profondeur) => {
      const enfants = enfantsDe(e.nom);
      const li = document.createElement('li');
      li.className = 'noeud';
      li.dataset.code = e.code;
      li.setAttribute('role', 'treeitem');
      if (enfants.length) li.setAttribute('aria-expanded', 'true');
      /* Le retrait ne passe plus par une variable de profondeur : ce sont les
         listes imbriquees qui le portent, et avec elles les filets de parente.
         Un retrait calcule aurait laisse les guides derriere lui. */
      li.innerHTML = `
        <div class="noeud-ligne">
          <button class="plier${enfants.length ? '' : ' sans-enfant'}" type="button"
                  aria-label="${enfants.length ? 'Plier ou déplier' : ''}">${enfants.length ? IC.chevron : ''}</button>
          <span class="ic-dossier">${IC.dossier}</span>
          <span class="noeud-nom"></span>
          ${enfants.length ? `<span class="noeud-compte">${enfants.length}</span>` : ''}
          <span class="noeud-meta"></span>
          <button class="act menu-noeud" type="button" title="Actions sur ce dossier"
                  aria-label="Actions sur ce dossier">${IC.menu}</button>
        </div>`;
      li.querySelector('.noeud-nom').textContent = e.nom;
      li.querySelector('.noeud-meta').textContent = e.code + ' · ' + e.owner;
      const compte = li.querySelector('.noeud-compte');
      if (compte) compte.title = `${enfants.length} sous-dossier(s)`;
      if (enfants.length) {
        const ul = document.createElement('ul');
        ul.setAttribute('role', 'group');
        enfants.forEach(f => ul.appendChild(noeud(f, profondeur + 1)));
        li.appendChild(ul);
      }
      return li;
    };

    const cible = arbre();
    cible.innerHTML = '';
    racines.forEach(e => cible.appendChild(noeud(e, 0)));

    const vide = document.querySelector('[data-vide-arbre]');
    if (vide) { vide.hidden = liste.length > 0; cible.hidden = liste.length === 0; }
  }

  /* -------------------------------------------------------------- compteurs */

  function majTout() {
    rebatirArbre();
    const n = corps().querySelectorAll('tr:not([hidden])').length;
    document.querySelectorAll('.pied[data-volet="tableau"] [data-compte]')
      .forEach(e => { e.textContent = n; });
    const enveloppe = document.querySelector('.registre-wrap[data-volet="tableau"]');
    const vierge = enveloppe?.querySelector('.vide.vierge');
    if (vierge) {
      const rien = corps().querySelectorAll('tr').length === 0;
      document.querySelector('#reg-espaces').hidden = rien;
      vierge.hidden = !rien;
    }
  }

  /* ------------------------------------------------------------- formulaire */

  /* Libelles, exemples et messages du formulaire du produit
     (workspace-form.html l. 8-76) : « Nom du Workspace », « Règles de
     Workflow », « Utilisateur Propriétaire », « Espace de travail parent »
     avec son option « — Aucun (racine) — », et le « Statut ». La description
     n'y porte aucune obligation. */
  const RACINE = '— Aucun (racine) —';
  const champsEspace = (valeurs = {}) => [
    { nom: 'code',    label: 'Code', exemple: 'Ex. WS-COMPTA', valeur: valeurs.code,
      erreur: 'Le code est obligatoire' },
    { nom: 'nom',     label: 'Nom du Workspace', exemple: 'Entrez le nom du Workspace', valeur: valeurs.nom,
      erreur: 'Le nom est obligatoire' },
    { nom: 'sous',    label: 'Description', exemple: 'Entrez une description', requis: false, valeur: valeurs.sous },
    { nom: 'circuit', label: 'Règles de Workflow', type: 'liste', options: valeursDe(4), valeur: valeurs.circuit,
      erreur: 'La règle de workflow est obligatoire' },
    { nom: 'owner',   label: 'Utilisateur Propriétaire', type: 'liste', options: valeursDe(3), valeur: valeurs.owner,
      erreur: 'Le propriétaire est obligatoire' },
    { nom: 'parent',  label: 'Espace de travail parent', type: 'liste', requis: false,
      options: [RACINE, ...actifs().map(e => e.nom).filter(x => x !== valeurs.nom)],
      valeur: !valeurs.parent || valeurs.parent === 'Racine' ? RACINE : valeurs.parent },
    { nom: 'statut',  label: 'Statut', type: 'liste', options: ['Actif', 'Inactif', 'Archivé'],
      valeur: valeurs.statut || 'Actif' },
  ];

  /** Le tableau ecrit « Racine » ; le formulaire dit « — Aucun (racine) — ». */
  const normaliserParent = p => (!p || p === RACINE ? 'Racine' : p);

  /** Construit une ligne de tableau. Une seule fabrique, pour creation et copie. */
  function ligne(e) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><input type="checkbox" aria-label="Sélectionner" /></td>
      <td class="num"></td>
      <td><span class="nom"></span><span class="sous"></span></td>
      <td></td><td></td><td></td>
      <td><span class="etat ok">Actif</span></td>
      <td class="fin">
        <button class="act" type="button" title="Modifier" aria-label="Modifier">${IC.crayon}</button>
        <button class="act danger" type="button" title="Supprimer" aria-label="Supprimer">${IC.poubelle}</button>
      </td>`;
    const c = tr.querySelectorAll('td');
    c[1].textContent = e.code;
    c[2].querySelector('.nom').textContent = e.nom;
    c[2].querySelector('.sous').textContent = e.sous || '';
    c[3].textContent = e.owner;
    c[4].textContent = e.circuit;
    c[5].textContent = e.parent;
    return tr;
  }

  /* -------------------------------------------------------------- decisions */

  async function creer(parentImpose) {
    const champs = champsEspace(parentImpose ? { parent: parentImpose } : {});
    const v = await demander({
      titre: parentImpose ? 'Créer un sous-dossier' : 'Créer un espace de travail',
      message: parentImpose ? `Le nouveau dossier sera rangé sous « ${parentImpose} ».` : '',
      confirmer: 'Ajouter',
      champs,
    });
    if (!v) return;
    v.parent = parentImpose || normaliserParent(v.parent);

    // Le code identifie l'espace : deux fois le meme rendrait la liste illisible.
    if (tous().some(e => e.code.toLowerCase() === v.code.toLowerCase())) {
      avis(`Le code « ${v.code} » est déjà utilisé.`, 'ko');
      return;
    }
    corps().prepend(ligne(v));
    majTout();
    avis('Espace de travail créé.', 'ok');
  }

  async function modifier(tr) {
    const e = lire(tr);
    const v = await demander({
      titre: 'Modifier l\'espace de travail',
      confirmer: 'Mettre à jour',
      champs: champsEspace(e),
    });
    if (!v) return;
    const c = tr.querySelectorAll('td');
    c[1].textContent = v.code;
    c[2].querySelector('.nom').textContent = v.nom;
    c[2].querySelector('.sous').textContent = v.sous || '';
    c[3].textContent = v.owner;
    c[4].textContent = v.circuit;
    c[5].textContent = normaliserParent(v.parent);
    ecrireStatut(tr, v.statut || 'Actif');
    majTout();
    avis('Espace de travail modifié.', 'ok');
  }

  const ACTIONS_ACTIF =
    `<button class="act" type="button" title="Modifier" aria-label="Modifier">${IC.crayon}</button>` +
    `<button class="act danger" type="button" title="Supprimer" aria-label="Supprimer">${IC.poubelle}</button>`;
  const ACTIONS_ARCHIVE =
    `<button class="act" type="button" title="Restaurer" aria-label="Restaurer">${IC.restaurer}</button>`;

  /** Ecrit le statut d'une ligne et met ses actions en accord. */
  function ecrireStatut(tr, statut) {
    const etat = tr.querySelectorAll('td')[6].querySelector('.etat');
    etat.className = 'etat ' + (statut === 'Actif' ? 'ok' : 'arret');
    etat.textContent = statut;
    tr.querySelector('td.fin').innerHTML = statut === 'Archivé' ? ACTIONS_ARCHIVE : ACTIONS_ACTIF;
    const c = tr.querySelector('input[type=checkbox]');
    if (c) c.checked = false;
  }

  /* « Supprimer ce dossier » pour une ligne, « Supprimer la sélection » pour un
     lot (workspace-list.ts l. 242-243 et l. 339-341). Le produit tient en une
     phrase : rien sur les sous-dossiers, rien sur la reversibilite. */
  async function supprimer(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander(n === 1
      ? { titre: 'Supprimer ce dossier',
          message: `« ${lire(lignes[0]).nom} » sera déplacé vers la corbeille.`,
          confirmer: 'Supprimer', danger: true }
      : { titre: 'Supprimer la sélection',
          message: `Voulez-vous supprimer ${n} dossier(s) ?`,
          confirmer: 'Supprimer', danger: true });
    if (!ok) return;
    lignes.forEach(tr => ecrireStatut(tr, 'Archivé'));
    majTout();
    avis(n === 1 ? 'Dossier supprimé.' : `${n} dossier(s) supprimé(s).`, 'ok');
  }

  /** Restauration d'une ligne : le produit ne demande rien (l. 350-355). */
  function restaurer(tr) {
    ecrireStatut(tr, 'Actif');
    majTout();
    avis('Dossier restauré.', 'ok');
  }

  /** Restauration en lot : elle, le produit la confirme (l. 242-250). */
  async function restaurerLot(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander({
      titre: 'Restaurer la sélection',
      message: `Voulez-vous restaurer ${n} dossier(s) ?`,
      confirmer: 'Restaurer',
    });
    if (!ok) return;
    lignes.forEach(tr => ecrireStatut(tr, 'Actif'));
    majTout();
    avis(`${n} dossier(s) restauré(s).`, 'ok');
  }

  /** Archiver / Désarchiver, action propre du produit (l. 356-361). */
  function archiver(tr) {
    const e = lire(tr);
    ecrireStatut(tr, e.statut === 'Archivé' ? 'Actif' : 'Archivé');
    majTout();
    avis('Dossier archivé.', 'ok');
  }

  /** Déplacer sous… — le produit propose « Racine » puis les autres dossiers. */
  async function deplacer(tr) {
    const e = lire(tr);
    const v = await demander({
      titre: 'Déplacer sous…',
      confirmer: 'Déplacer',
      champs: [{ nom: 'parent', label: 'Espace de travail parent', type: 'liste', requis: false,
                 options: ['Racine', ...actifs().map(x => x.nom).filter(x => x !== e.nom)],
                 valeur: e.parent }],
    });
    if (!v) return;
    tr.querySelectorAll('td')[5].textContent = v.parent || 'Racine';
    majTout();
    avis('Dossier déplacé.', 'ok');
  }

  /* Menu d'un noeud de l'arbre. Les entrees et leur ordre sont celles du menu
     du produit (workspace-list.html l. 216-223) ; « Ouvrir le dossier » n'y
     figure pas, la maquette n'ayant pas de page de dossier. */
  async function menuNoeud(code) {
    const tr = [...corps().querySelectorAll('tr')]
      .find(t => t.querySelectorAll('td')[1].textContent.trim() === code);
    if (!tr) return;
    const e = lire(tr);
    const choix = await demander({
      titre: e.nom,
      message: `${e.code} · ${e.owner}`,
      confirmer: 'Fermer',
      champs: [{ nom: 'action', label: 'Action', type: 'liste',
                 options: ['Créer un sous-dossier', 'Modifier', 'Archiver / Désarchiver',
                           'Déplacer sous…', 'Supprimer'] }],
    });
    if (!choix) return;
    if (choix.action === 'Créer un sous-dossier') creer(e.nom);
    else if (choix.action === 'Modifier') modifier(tr);
    else if (choix.action === 'Archiver / Désarchiver') archiver(tr);
    else if (choix.action === 'Déplacer sous…') deplacer(tr);
    else supprimer([tr]);
  }

  /* ------------------------------------------------------------ branchement */

  document.addEventListener('DOMContentLoaded', () => {
    if (!corps() || !arbre()) return;

    majTout();

    document.addEventListener('click', e => {
      // Plier / deplier un noeud
      const plier = e.target.closest('.plier:not(.vide)');
      if (plier) {
        const li = plier.closest('.noeud');
        const ouvert = li.getAttribute('aria-expanded') !== 'false';
        li.setAttribute('aria-expanded', String(!ouvert));
        return;
      }
      const menu = e.target.closest('.menu-noeud');
      if (menu) { menuNoeud(menu.closest('.noeud').dataset.code); return; }

      const act = e.target.closest('#reg-espaces button.act');
      if (act) {
        const tr = act.closest('tr');
        const titre = act.getAttribute('title');
        if (titre === 'Modifier') modifier(tr);
        else if (titre === 'Supprimer') supprimer([tr]);
        else if (titre === 'Restaurer') restaurer(tr);
        return;
      }

      const cta = e.target.closest('.head .cta');
      if (cta) { e.preventDefault(); creer(null); }
    });

    // Action groupee : « Supprimer (n) » sur les dossiers actifs,
    // « Restaurer (n) » des que la selection ne contient que des archives.
    document.querySelectorAll('[data-bulk] [data-bulk-action]').forEach(b => {
      b.addEventListener('click', () => {
        const lignes = [...corps().querySelectorAll('input[type=checkbox]:checked')]
          .map(c => c.closest('tr'));
        const archivees = lignes.filter(tr => lire(tr).statut === 'Archivé');
        if (lignes.length && archivees.length === lignes.length) restaurerLot(lignes);
        else supprimer(lignes.filter(tr => lire(tr).statut !== 'Archivé'));
      });
    });

    /* Le verbe du bouton groupe suit la selection. */
    document.addEventListener('change', () => {
      const barre = document.querySelector('[data-bulk]');
      const b = barre?.querySelector('[data-bulk-action]');
      if (!b) return;
      const lignes = [...corps().querySelectorAll('input[type=checkbox]:checked')].map(c => c.closest('tr'));
      const toutArchive = lignes.length > 0 && lignes.every(tr => lire(tr).statut === 'Archivé');
      b.querySelector('[data-bulk-label]').textContent = toutArchive ? 'Restaurer' : 'Supprimer';
      b.classList.toggle('danger', !toutArchive);
      const note = barre.querySelector('.muet');
      if (note) note.hidden = toutArchive;
    });

    // Rafraichir : la maquette n'a rien a recharger, mais le bouton doit repondre.
    [...document.querySelectorAll('.outils .droite .bouton')]
      .filter(b => b.textContent.trim() === 'Rafraîchir')
      .forEach(b => b.addEventListener('click', () => { majTout(); avis('Liste à jour.', ''); }));
  });
})();
