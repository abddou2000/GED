/* ============================================================
   PLAN D'INDEXATION — la mecanique de l'ecran
   ------------------------------------------------------------
   Un plan regroupe des index en une fiche reutilisable, et decide de
   la CHARTE DE NOMMAGE : le nom compose que le serveur donnera au
   document. C'est le seul ecran ou le client peut voir cette regle se
   fabriquer — elle etait figee dans deux cellules de tableau.

   Ce qui se joue desormais dans le DOM : creer, modifier, supprimer
   vers l'archive, restaurer, selectionner,
   rafraochir. La charte n'est JAMAIS saisie : elle est RECOMPOSEE a
   partir des index et du separateur — ecrite a la main, elle aurait
   diverge de la liste des index des la premiere modification.

   Le piege du separateur (une valeur qui contient deja le separateur)
   est annonce au moment ou il se cree, pas seulement en bas de page.

   Toutes les popups passent par `demander()` de shell.js, toutes les
   confirmations par `avis()`.
   ============================================================ */

(() => {
  const SEL = '#reg-plans';
  const table = () => document.querySelector(SEL);
  const corps = () => document.querySelector(SEL + ' tbody');
  const tous  = () => [...corps().querySelectorAll('tr')];

  const IC = {
    crayon: `<svg class="lucide lucide-pencil" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"/><path d="m15 5 4 4"/></svg>`,
    poubelle: `<svg class="lucide lucide-trash-2" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 11v6"/><path d="M14 11v6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
    restaurer: `<svg class="lucide lucide-refresh-cw" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/></svg>`,
  };

  const ACTIONS_ACTIF = `<button class="act" type="button" title="Modifier" aria-label="Modifier">${IC.crayon}</button>`
                      + `<button class="act danger" type="button" title="Supprimer" aria-label="Supprimer">${IC.poubelle}</button>`;
  const ACTIONS_ARCHIVE = `<button class="act" type="button" title="Restaurer" aria-label="Restaurer">${IC.restaurer}</button>`;

  const decouper = s => String(s || '').split(',').map(x => x.trim()).filter(Boolean);

  /* ------------------------------------------------------------- lecture */

  const lire = tr => {
    const c = tr.querySelectorAll('td');
    const charte = c[4].querySelector('.etat') ? '' : c[4].textContent.trim();
    return {
      tr,
      code:   c[1].textContent.trim(),
      nom:    c[2].querySelector('.nom')?.textContent.trim() ?? '',
      index:  [...c[3].querySelectorAll('.jeton')].map(j => j.textContent.trim()).join(', '),
      mode:   c[4].querySelector('.etat') ? 'Manuel' : 'Auto',
      /* Le separateur n'a pas de colonne : il se relit dans la charte. Deux
         valeurs possibles seulement, `_` ou `-`. */
      separateur: charte.includes('_') ? '_' : (charte.includes('-') ? '-' : '_'),
      casse: charte && charte === charte.toUpperCase() && /[A-ZÀ-Þ]/.test(charte) ? 'Majuscule' : 'Minuscule',
    };
  };

  /** La charte n'est pas saisie : elle se recompose. Une seule verite.
   *  La casse suit « Convertir en » (plan-indexation-form.ts l. 118-122). */
  const composer = (index, separateur, casse) => {
    const joint = decouper(index).join(separateur);
    return casse === 'Majuscule' ? joint.toUpperCase() : joint.toLowerCase();
  };

  function ecrire(tr, v) {
    const c = tr.querySelectorAll('td');
    c[1].textContent = v.code;
    c[2].querySelector('.nom').textContent = v.nom;

    c[3].textContent = '';
    decouper(v.index).forEach(x => {
      const s = document.createElement('span');
      s.className = 'jeton';
      s.textContent = x;
      c[3].appendChild(s);
    });

    /* Quand un plan est Manuel, TOUTE la section de nommage disparaot : pas de
       charte, pas de separateur. La cellule ne porte alors que le mot. */
    c[4].textContent = '';
    c[4].className = '';
    if (v.mode === 'Manuel') {
      const s = document.createElement('span');
      s.className = 'etat arret';
      s.textContent = 'Manuel';
      c[4].appendChild(s);
    } else {
      c[4].className = 'mono';
      c[4].textContent = composer(v.index, v.separateur, v.casse);
    }
  }

  function ligne(v) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><input type="checkbox" aria-label="Sélectionner" /></td>
      <td class="num"></td>
      <td><span class="nom"></span></td>
      <td class="c-etapes"></td>
      <td></td>
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
      ? "Aucun plan supprimé n'attend d'être restauré."
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

  /* Les index proposes sont ceux que la page montre deja : la maquette ne doit
     pas inventer un champ que le client ne retrouverait pas sur l'ecran Index. */
  const indexConnus = () => [...new Set(tous()
    .flatMap(tr => [...tr.querySelectorAll('td')[3].querySelectorAll('.jeton')].map(j => j.textContent.trim())))];

  /* Libelles du formulaire du produit (plan-indexation-form.html l. 10-62) :
     « Liste des index », « Charte de nommage », « Séparateur », « Convertir
     en ». La liste des index n'y porte AUCUNE obligation
     (plan-indexation-form.ts l. 81) : la maquette refusait un plan sans index,
     le produit l'accepte. */
  const champsPlan = (v = {}) => [
    { nom: 'code',  label: 'Code', exemple: 'Ex. PLAN-FACT', valeur: v.code,
      erreur: 'Le code est obligatoire' },
    { nom: 'nom',   label: 'Nom du plan', exemple: 'Ex. Fiche Facture', valeur: v.nom,
      erreur: 'Le nom du plan est obligatoire' },
    { nom: 'index', label: 'Liste des index', requis: false,
      exemple: 'Ex. ' + (indexConnus().slice(0, 3).join(', ') || "Numéro de facture, Date d'émission"),
      valeur: v.index },
    { nom: 'mode',  label: 'Charte de nommage', type: 'liste', options: ['Auto', 'Manuel'], valeur: v.mode },
    { nom: 'separateur', label: 'Séparateur', type: 'liste', options: ['-', '_'], valeur: v.separateur || '_' },
    { nom: 'casse', label: 'Convertir en', type: 'liste', options: ['Majuscule', 'Minuscule'],
      valeur: v.casse || 'Minuscule' },
  ];

  /**
   * Le piege du separateur, annonce au moment ou il se cree.
   *
   * <p>Un plan qui utilise `-` alors qu'il porte une date ne peut plus etre
   * redecoupe : la GED renonce a recomposer le nom, et « Nom du document »
   * reste vide. On ne bloque pas — le produit ne bloque pas — mais on le dit.</p>
   */
  function avertirSeparateur(v) {
    if (v.mode !== 'Auto' || v.separateur !== '-') return;
    if (!decouper(v.index).some(x => /date/i.test(x))) return;
    avis('Séparateur « - » avec une date : le nom composé ne pourra pas être redécoupé.', 'ko');
  }

  /* ------------------------------------------------------------ decisions */

  async function creer() {
    const v = await demander({ titre: "Créer un plan d'indexation", confirmer: 'Ajouter', champs: champsPlan() });
    if (!v) return;
    if (tous().some(tr => lire(tr).code.toLowerCase() === v.code.toLowerCase())) {
      avis(`Le code « ${v.code} » est déjà utilisé.`, 'ko');
      return;
    }
    vue = 'actifs';
    majBasculeArchive();
    corps().prepend(ligne(v));
    majTout();
    avis("Plan d'indexation créé.", 'ok');
    avertirSeparateur(v);
  }

  async function modifier(tr) {
    const v = await demander({
      titre: "Modifier le plan d'indexation",
      confirmer: 'Mettre à jour',
      champs: champsPlan(lire(tr)),
    });
    if (!v) return;
    ecrire(tr, v);
    majTout();
    avis("Plan d'indexation modifié.", 'ok');
    avertirSeparateur(v);
  }

  /* « Supprimer ce plan » pour une ligne, « Supprimer la sélection » pour un
     lot (plan-indexation-list.ts l. 162-163 et l. 190-192). */
  async function supprimer(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander(n === 1
      ? { titre: 'Supprimer ce plan',
          message: `« ${lire(lignes[0]).nom} » sera déplacé vers la corbeille.`,
          confirmer: 'Supprimer', danger: true }
      : { titre: 'Supprimer la sélection',
          message: `Voulez-vous supprimer ${n} plan(s) ?`,
          confirmer: 'Supprimer', danger: true });
    if (!ok) return;
    lignes.forEach(tr => {
      tr.dataset.archive = '1';
      tr.querySelector('td.fin').innerHTML = ACTIONS_ARCHIVE;
      const c = tr.querySelector('input[type=checkbox]');
      if (c) c.checked = false;
    });
    majTout();
    avis(n === 1 ? "Plan d'indexation supprimé." : `${n} plan(s) supprimé(s).`, 'ok');
  }

  /** Restauration d'une ligne : le produit ne demande rien (l. 203-208). */
  function restaurer(tr) {
    delete tr.dataset.archive;
    tr.querySelector('td.fin').innerHTML = ACTIONS_ACTIF;
    const c = tr.querySelector('input[type=checkbox]');
    if (c) c.checked = false;
    majTout();
    avis("Plan d'indexation restauré.", 'ok');
  }

  /** Restauration en lot : elle, le produit la confirme. */
  async function restaurerLot(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander({
      titre: 'Restaurer la sélection',
      message: `Voulez-vous restaurer ${n} plan(s) ?`,
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
    avis(`${n} plan(s) restauré(s).`, 'ok');
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
    boutonArchive.title = arch ? 'Revenir aux plans actifs' : 'Voir les plans archivés';
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
      avis(vue === 'archive' ? 'Archive affichée.' : 'Plans actifs affichés.', '');
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
