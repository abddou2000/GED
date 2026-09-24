/* ============================================================
   DOCUMENTS DÉPOSÉS — la mecanique de l'ecran
   ------------------------------------------------------------
   C'est l'ecran le plus consulte du produit, et le seul geste qu'il
   savait faire etait de charger sa coque : la fiche ne s'ouvrait pas,
   le telechargement ne repondait pas, la corbeille ne supprimait rien,
   et le bandeau d'indicateurs affichait un total que rien ne mettait
   plus a jour.

   Ce qui se joue desormais dans le DOM : ouvrir la fiche, telecharger,
   supprimer vers l'archive, restaurer, supprimer en lot, basculer
   Archive / Actifs, selectionner, rafraochir.

   Le bandeau SUIT le tableau : « Documents » est recalcule depuis le
   nombre de lignes actives. Un indicateur fige a cote d'une liste qui
   bouge est un mensonge qui se voit.

   Toutes les popups passent par `demander()` de shell.js, toutes les
   confirmations par `avis()`.
   ============================================================ */

(() => {
  const SEL = '#reg-documents';
  const table = () => document.querySelector(SEL);
  const corps = () => document.querySelector(SEL + ' tbody');
  const tous  = () => [...corps().querySelectorAll('tr')];

  const IC = {
    oeil: `<svg class="lucide lucide-eye" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"/><circle cx="12" cy="12" r="3"/></svg>`,
    telecharger: `<svg class="lucide lucide-download" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 15V3"/><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5"/></svg>`,
    poubelle: `<svg class="lucide lucide-trash-2" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 11v6"/><path d="M14 11v6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
    restaurer: `<svg class="lucide lucide-refresh-cw" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/></svg>`,
  };

  const ACTIONS_ACTIF = `<button class="act" type="button" title="Ouvrir la fiche" aria-label="Ouvrir la fiche">${IC.oeil}</button>`
                      + `<button class="act accent" type="button" title="Télécharger" aria-label="Télécharger">${IC.telecharger}</button>`
                      + `<button class="act danger" type="button" title="Supprimer" aria-label="Supprimer">${IC.poubelle}</button>`;
  const ACTIONS_ARCHIVE = `<button class="act" type="button" title="Restaurer" aria-label="Restaurer">${IC.restaurer}</button>`;

  /* ------------------------------------------------------------- lecture */

  const lire = tr => {
    const c = tr.querySelectorAll('td');
    return {
      tr,
      nom:     c[1].querySelector('.nom')?.textContent.trim() ?? '',
      sous:    c[1].querySelector('.sous')?.textContent.trim() ?? '',
      type:    c[2].textContent.trim(),
      espace:  c[3].textContent.trim(),
      date:    c[4].textContent.trim(),
      createur: c[5].textContent.trim(),
    };
  };

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
      ? "Aucun document supprimé n'attend d'être restauré."
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
    majBoutonLot();
    const barre = document.querySelector('[data-bulk]');
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

  /**
   * Le bandeau suit le tableau.
   *
   * <p>« Documents » compte les lignes NON archivees, quelle que soit la vue
   * ouverte : l'indicateur decrit le fonds documentaire, pas l'ecran. La
   * jauge suit dans la meme proportion, sinon elle contredit son chiffre.</p>
   */
  function majBandeau() {
    const tuile = [...document.querySelectorAll('.metric')]
      .find(m => m.querySelector('.k')?.textContent.trim() === 'Documents');
    if (!tuile) return;
    const v = tuile.querySelector('.v');
    const depart = +(tuile.dataset.depart || v.dataset.count || v.textContent) || 1;
    if (!tuile.dataset.depart) {
      tuile.dataset.depart = depart;
      tuile.dataset.jauge = parseFloat(tuile.querySelector('.bar i').style.width) || 70;
    }
    const n = tous().filter(tr => !archivee(tr)).length;
    v.textContent = n;
    v.dataset.count = n;
    const part = Math.max(0, Math.min(100, (+tuile.dataset.jauge) * n / depart));
    tuile.querySelector('.bar i').style.width = part + '%';
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
    majBandeau();
  }

  /* ------------------------------------------------------------ decisions */

  /* Fiche en lecture. Le produit ouvre un detail : on montre ce que la reponse
     d'API porte reellement — ni reference, ni etat « Indexe / A indexer », qui
     n'existent nulle part. */
  async function fiche(tr) {
    const e = lire(tr);
    await demander({
      titre: e.nom,
      message: (e.sous ? `Fichier : ${e.sous}\n` : '')
             + `Type de document : ${e.type}`
             + `\nEspace de travail : ${e.espace}`
             + `\nDate de création : ${e.date}`
             + `\nCréateur : ${e.createur}`
             + (archivee(tr) ? `\n\nCe document est à l'archive : il peut être restauré.` : ''),
      confirmer: 'Fermer',
    });
  }

  /* Aucun reseau dans la maquette : on ne fabrique pas un faux fichier, on dit
     ce que le produit ferait. */
  function telecharger(tr) {
    avis(`Téléchargement de « ${lire(tr).nom} ».`, '');
  }

  /* « Supprimer ce document » pour une ligne, « Supprimer la sélection » pour
     un lot (document-list.ts l. 205-206 et l. 262-264). Les deux phrases
     supplementaires — le fichier conserve, les circuits interrompus — etaient
     inventees : le produit n'en ecrit qu'une. */
  async function supprimer(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander(n === 1
      ? { titre: 'Supprimer ce document',
          message: `« ${lire(lignes[0]).nom} » sera déplacé vers la corbeille.`,
          confirmer: 'Supprimer', danger: true }
      : { titre: 'Supprimer la sélection',
          message: `Voulez-vous supprimer ${n} document(s) ?`,
          confirmer: 'Supprimer', danger: true });
    if (!ok) return;
    lignes.forEach(tr => {
      tr.dataset.archive = '1';
      tr.querySelector('td.fin').innerHTML = ACTIONS_ARCHIVE;
      const c = tr.querySelector('input[type=checkbox]');
      if (c) c.checked = false;
    });
    majTout();
    avis(n === 1 ? 'Document supprimé.' : `${n} document(s) supprimé(s).`, 'ok');
  }

  /** Restauration d'une ligne : le produit ne demande rien (l. 274-278). */
  function restaurer(tr) {
    delete tr.dataset.archive;
    tr.querySelector('td.fin').innerHTML = ACTIONS_ACTIF;
    const c = tr.querySelector('input[type=checkbox]');
    if (c) c.checked = false;
    majTout();
    avis('Document restauré.', 'ok');
  }

  /** Restauration en lot : elle, le produit la confirme (l. 205-213). */
  async function restaurerLot(lignes) {
    if (!lignes.length) return;
    const n = lignes.length;
    const ok = await demander({
      titre: 'Restaurer la sélection',
      message: `Voulez-vous restaurer ${n} document(s) ?`,
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
    avis(`${n} document(s) restauré(s).`, 'ok');
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
    boutonArchive.title = arch ? 'Revenir aux documents actifs' : 'Voir les documents archivés';
  }

  document.addEventListener('DOMContentLoaded', () => {
    if (!corps()) return;

    boutonArchive = boutonNomme('Archive');
    majBasculeArchive();
    majTout();

    document.addEventListener('click', e => {
      const act = e.target.closest(SEL + ' button.act');
      if (!act) return;
      const tr = act.closest('tr');
      const titre = act.getAttribute('title');
      if (titre === 'Ouvrir la fiche') fiche(tr);
      else if (titre === 'Télécharger') telecharger(tr);
      else if (titre === 'Supprimer') supprimer([tr]);
      else if (titre === 'Restaurer') restaurer(tr);
    });

    document.addEventListener('change', e => { if (e.target.closest(SEL)) majSelection(); });
    document.querySelectorAll('[data-filtre]').forEach(ch => ch.addEventListener('input', majTout));

    document.querySelectorAll('[data-bulk] [data-bulk-action]').forEach(b => b.addEventListener('click', () => {
      if (vue === 'archive') {
        restaurerLot(tous().filter(tr => dansLaVue(tr) && tr.querySelector('input:checked')));
        return;
      }
      supprimer(tous().filter(tr => dansLaVue(tr) && !archivee(tr) && tr.querySelector('input:checked')));
    }));

    boutonArchive?.addEventListener('click', () => {
      vue = vue === 'archive' ? 'actifs' : 'archive';
      majBasculeArchive();
      majTout();
      avis(vue === 'archive' ? 'Archive affichée.' : 'Documents actifs affichés.', '');
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
