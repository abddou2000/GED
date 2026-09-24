/* ============================================================
   INDEX — la mecanique de l'ecran
   ------------------------------------------------------------
   Un index est un champ de description : son type commande ce qu'on
   peut y saisir, « Obligatoire » bloque le depot tant qu'il est vide.
   L'ecran listait tout cela sans qu'on puisse en creer un seul.

   Ce qui se joue desormais dans le DOM : creer, modifier, supprimer
   vers l'archive, restaurer, selectionner,
   rafraochir. La colonne « Valeurs » n'apparaot que pour un index de
   type Liste — c'est la regle du produit, et le formulaire la tient.

   Toutes les popups passent par `demander()` de shell.js, toutes les
   confirmations par `avis()`.
   ============================================================ */

(() => {
  const SEL = '#reg-index';
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

  const VIDE = '—';
  const decouper = s => String(s || '').split(',').map(x => x.trim()).filter(Boolean);

  /* ------------------------------------------------------------- lecture */

  const lire = tr => {
    const c = tr.querySelectorAll('td');
    const jetons = [...c[4].querySelectorAll('.jeton')].map(j => j.textContent.trim());
    return {
      tr,
      code:        c[1].textContent.trim(),
      nom:         c[2].querySelector('.nom')?.textContent.trim() ?? '',
      type:        c[3].textContent.trim(),
      valeurs:     jetons.join(', '),
      defaut:      c[5].textContent.trim() === VIDE ? '' : c[5].textContent.trim(),
      obligatoire: c[6].textContent.trim(),
      recherche:   c[7].textContent.trim(),
      groupage:    c[8].textContent.trim(),
    };
  };

  function poserJetonsOuTiret(cellule, valeurs) {
    cellule.textContent = '';
    if (!valeurs.length) {
      const s = document.createElement('span');
      s.style.color = 'var(--encre-3)';
      s.textContent = VIDE;
      cellule.appendChild(s);
      return;
    }
    valeurs.forEach(v => {
      const s = document.createElement('span');
      s.className = 'jeton';
      s.textContent = v;
      cellule.appendChild(s);
    });
  }

  function poserOuTiret(cellule, valeur) {
    cellule.textContent = '';
    if (valeur) { cellule.textContent = valeur; return; }
    const s = document.createElement('span');
    s.style.color = 'var(--encre-3)';
    s.textContent = VIDE;
    cellule.appendChild(s);
  }

  function ecrire(tr, v) {
    const c = tr.querySelectorAll('td');
    /* Hors du type Liste, il n'y a pas de valeurs a enumerer ni de valeur par
       defaut a choisir : le produit laisse les deux cellules a « — ». */
    const liste = v.type === 'Liste';
    c[1].textContent = v.code;
    c[2].querySelector('.nom').textContent = v.nom;
    c[3].textContent = v.type;
    poserJetonsOuTiret(c[4], liste ? decouper(v.valeurs) : []);
    poserOuTiret(c[5], liste ? v.defaut : '');
    c[6].textContent = v.obligatoire;
    c[7].textContent = v.recherche;
    c[8].textContent = v.groupage;
  }

  function ligne(v) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><input type="checkbox" aria-label="Sélectionner" /></td>
      <td class="num"></td>
      <td><span class="nom"></span></td>
      <td></td><td></td><td></td><td></td><td></td><td></td>
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
      ? "Aucun index supprimé n'attend d'être restauré."
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

  const OUI_NON = ['Non', 'Oui'];

  /* Ordre et libelles du formulaire du produit (index-form.html l. 6-68) :
     « Nom de l'index » d'abord, puis « Code », « Type de champ »,
     « Valeurs (Options) », « Valeur par défaut », et les trois reglages.
     La colonne du tableau s'appelle « Nom index », le CHAMP s'appelle
     « Nom de l'index » : deux libelles distincts, tous deux du produit. */
  const champsIndex = (v = {}) => [
    { nom: 'nom',  label: "Nom de l'index", exemple: 'Ex. Numéro de facture', valeur: v.nom,
      erreur: 'Le nom est obligatoire' },
    { nom: 'code', label: 'Code', exemple: 'Ex. IDX-NUMFACT', valeur: v.code,
      erreur: 'Le code est obligatoire' },
    { nom: 'type', label: 'Type de champ', type: 'liste', options: ['Texte', 'Nombre', 'Date', 'Liste'], valeur: v.type },
    /* Les deux champs qui ne valent que pour une liste restent facultatifs :
       ils sont ignores pour les autres types plutot que d'imposer une saisie
       sans objet. */
    { nom: 'valeurs', label: 'Valeurs (Options)',
      exemple: 'Ex. Basse, Normale, Haute, Urgente', requis: false, valeur: v.valeurs },
    { nom: 'defaut', label: 'Valeur par défaut', requis: false, valeur: v.defaut },
    { nom: 'obligatoire', label: 'Obligatoire', type: 'liste', options: OUI_NON, valeur: v.obligatoire },
    { nom: 'recherche', label: 'Indexé pour recherche', type: 'liste', options: OUI_NON, valeur: v.recherche },
    { nom: 'groupage', label: 'Index de groupage', type: 'liste', options: OUI_NON, valeur: v.groupage },
  ];

  /* ------------------------------------------------------------ decisions */

  async function creer() {
    const v = await demander({ titre: 'Créer un index', confirmer: 'Ajouter', champs: champsIndex() });
    if (!v) return;
    if (tous().some(tr => lire(tr).code.toLowerCase() === v.code.toLowerCase())) {
      avis(`Le code « ${v.code} » est déjà utilisé.`, 'ko');
      return;
    }
    /* Formule du produit, mot pour mot (index-form.html l. 55). */
    if (v.type === 'Liste' && !decouper(v.valeurs).length) {
      avis('Indiquez au moins une valeur', 'ko');
      return;
    }
    vue = 'actifs';
    majBasculeArchive();
    corps().prepend(ligne(v));
    majTout();
    avis('Index créé.', 'ok');
  }

  async function modifier(tr) {
    const v = await demander({ titre: "Modifier l'index", confirmer: 'Mettre à jour', champs: champsIndex(lire(tr)) });
    if (!v) return;
    if (v.type === 'Liste' && !decouper(v.valeurs).length) {
      avis('Indiquez au moins une valeur', 'ko');
      return;
    }
    ecrire(tr, v);
    majTout();
    avis('Index modifié.', 'ok');
  }

  /* Deux dialogues, et deux seulement : la ligne seule (« Supprimer cet
     index », index-list.ts l. 218-222) et la selection (« Supprimer la
     sélection », l. 171-175). Les phrases sur les plans a revoir et la
     reversibilite etaient inventees. */
  async function supprimer(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander(n === 1
      ? { titre: 'Supprimer cet index',
          message: `« ${lire(lignes[0]).nom} » sera déplacé vers la corbeille.`,
          confirmer: 'Supprimer', danger: true }
      : { titre: 'Supprimer la sélection',
          message: `Voulez-vous supprimer ${n} index ?`,
          confirmer: 'Supprimer', danger: true });
    if (!ok) return;
    lignes.forEach(tr => {
      tr.dataset.archive = '1';
      tr.querySelector('td.fin').innerHTML = ACTIONS_ARCHIVE;
      const c = tr.querySelector('input[type=checkbox]');
      if (c) c.checked = false;
    });
    majTout();
    avis(n === 1 ? 'Index supprimé.' : `${n} index supprimé(s).`, 'ok');
  }

  /** Restauration d'une ligne : le produit ne demande rien (l. 231-236). */
  function restaurer(tr) {
    delete tr.dataset.archive;
    tr.querySelector('td.fin').innerHTML = ACTIONS_ACTIF;
    const c = tr.querySelector('input[type=checkbox]');
    if (c) c.checked = false;
    majTout();
    avis('Index restauré.', 'ok');
  }

  /** Restauration en lot : elle, le produit la confirme (l. 171-182). */
  async function restaurerLot(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander({
      titre: 'Restaurer la sélection',
      message: `Voulez-vous restaurer ${n} index ?`,
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
    avis(`${n} index restauré(s).`, 'ok');
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
    boutonArchive.title = arch ? 'Revenir aux index actifs' : 'Voir les index archivés';
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
      avis(vue === 'archive' ? 'Archive affichée.' : 'Index actifs affichés.', '');
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
