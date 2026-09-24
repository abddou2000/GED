/* ============================================================
   MES WORKFLOW — la mecanique de l'ecran
   ------------------------------------------------------------
   POURQUOI CE FICHIER EXISTE
   La maquette montrait deux tableaux figes : on cliquait « Signer »,
   rien ne bougeait. Or c'est ICI que se joue le seul geste engageant
   du produit — donner ou refuser un visa — et une demonstration qui
   ne le joue pas ne montre rien du tout.

   Ce qui est reproduit, du produit reel :
     - signer demande une confirmation qui NOMME le document ;
     - rejeter EXIGE un motif, et ce motif est conserve ;
     - la ligne quitte « A traiter » et paraot dans « Historique » ;
     - un rejet n'est pas definitif : il se relance depuis l'historique ;
     - les compteurs suivent, y compris la pastille du menu.

   Aucun reseau, aucun cadre applicatif : tout se passe dans le DOM.
   C'est une maquette, elle doit rester lisible sans outillage.
   ============================================================ */

(() => {
  const aTraiter   = () => document.querySelector('#reg-a-traiter tbody');
  const historique = () => document.querySelector('#reg-historique tbody');

  const auFormatDuJour = () => {
    const d = new Date();
    const deuxChiffres = n => String(n).padStart(2, '0');
    return `${deuxChiffres(d.getDate())}/${deuxChiffres(d.getMonth() + 1)}/${d.getFullYear()}`;
  };

  /** Lit une ligne « a traiter » : le tableau est la seule source de verite. */
  const lireLigne = tr => {
    const c = tr.querySelectorAll('td');
    return {
      nom:    c[1].querySelector('.nom')?.textContent.trim() ?? '',
      sous:   c[1].querySelector('.sous')?.textContent.trim() ?? '',
      type:   c[2].textContent.trim(),
      espace: c[3].textContent.trim(),
      etape:  c[4].textContent.trim(),
    };
  };

  const ICONE_RELANCE = `<svg class="lucide lucide-refresh-cw" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"> <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" /> <path d="M21 3v5h-5" /> <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" /> <path d="M8 16H3v5" /> </svg>`;

  const ICONE_SIGNER = `<svg class="lucide lucide-circle-check" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"> <circle cx="12" cy="12" r="10" /> <path d="m9 12 2 2 4-4" /> </svg>`;
  const ICONE_REJETER = `<svg class="lucide lucide-circle-x" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"> <circle cx="12" cy="12" r="10" /> <path d="m15 9-6 6" /> <path d="m9 9 6 6" /> </svg>`;

  /** Construit la ligne d'historique correspondant a une decision. */
  function ligneHistorique(donnees, statut, motif) {
    const tr = document.createElement('tr');
    const refusee = statut === 'Rejeté';
    tr.innerHTML = `
      <td><span class="nom"></span><span class="sous"></span></td>
      <td></td>
      <td></td>
      <td><span class="etat ${refusee ? 'ko' : 'ok'}"></span></td>
      <td>${auFormatDuJour()}</td>
      <td></td>
      <td class="fin">${refusee
        ? `<button class="act" type="button" title="Relancer la validation" aria-label="Relancer la validation">${ICONE_RELANCE}</button>`
        : ''}</td>`;
    const c = tr.querySelectorAll('td');
    c[0].querySelector('.nom').textContent  = donnees.nom;
    c[0].querySelector('.sous').textContent = donnees.sous;
    c[1].textContent = donnees.type;
    c[2].textContent = donnees.etape;
    c[3].querySelector('.etat').textContent = statut;
    /* Le motif n'existe que pour un refus. Une signature accordee n'a rien a
       justifier — le tiret le dit mieux qu'une cellule vide, qui se lirait
       comme une donnee manquante. */
    if (motif) c[5].textContent = motif;
    else c[5].innerHTML = '<span style="color:var(--encre-3)">—</span>';
    // L'espace de travail suit la ligne : l'historique ne l'affiche pas, mais
    // il faut le retrouver si la validation est relancee.
    tr.dataset.espace = donnees.espace;
    return tr;
  }

  /** Rebatit une ligne « a traiter » depuis une ligne d'historique relancee. */
  function ligneATraiter(donnees) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><input type="checkbox" aria-label="Sélectionner" /></td>
      <td><span class="nom"></span><span class="sous"></span></td>
      <td></td>
      <td></td>
      <td></td>
      <td class="fin">
        <button class="act" type="button" title="Signer" aria-label="Signer">${ICONE_SIGNER}</button>
        <button class="act danger" type="button" title="Rejeter" aria-label="Rejeter">${ICONE_REJETER}</button>
      </td>`;
    const c = tr.querySelectorAll('td');
    c[1].querySelector('.nom').textContent  = donnees.nom;
    c[1].querySelector('.sous').textContent = donnees.sous;
    c[2].textContent = donnees.type;
    c[3].textContent = donnees.espace;
    c[4].textContent = donnees.etape;
    return tr;
  }

  /**
   * Remet les compteurs d'aplomb apres chaque decision.
   *
   * <p>Le produit tient trois nombres qui disent la meme chose : la pastille de
   * l'onglet, celle du menu, et le pied du tableau. Ils avaient deja diverge
   * une fois — un badge fige a 32 quand la liste en montrait 31. Ici ils sont
   * tous recalcules a la meme seconde, depuis la seule source qui vaille : le
   * nombre de lignes.</p>
   */
  function majCompteurs() {
    const n = aTraiter().querySelectorAll('tr').length;
    const h = historique().querySelectorAll('tr').length;

    const pastilleOnglet = document.querySelector('[data-onglet="traiter"] .compte');
    if (pastilleOnglet) pastilleOnglet.textContent = n;

    const pastilleMenu = document.querySelector('.tabs .pastille, .tabs .compte');
    if (pastilleMenu) pastilleMenu.textContent = n;

    majPied('traiter', n);
    majPied('historique', h);
    majEtatsVides();
  }

  function majPied(volet, n) {
    const pied = document.querySelector(`.pied[data-volet="${volet}"]`);
    if (!pied) return;
    pied.querySelectorAll('[data-compte]').forEach(e => { e.textContent = n; });
  }

  /** Un tableau vide doit le dire, sinon il ressemble a un chargement bloque. */
  function majEtatsVides() {
    [['#reg-a-traiter', 'traiter'], ['#reg-historique', 'historique']].forEach(([sel, volet]) => {
      const table = document.querySelector(sel);
      const enveloppe = document.querySelector(`.registre-wrap[data-volet="${volet}"]`);
      const vierge = enveloppe?.querySelector('.vide.vierge');
      if (!table || !vierge) return;
      /* On ne touche pas a l'etat « aucun resultat » de la recherche : il est
         pilote par le filtre, et deux mecanismes sur le meme element se
         voleraient la main. */
      const rien = table.querySelectorAll('tbody tr').length === 0;
      table.hidden = rien;
      vierge.hidden = !rien;
    });
  }

  /* ---------------------------------------------------------- decisions */

  /**
   * Rang et libelle d'une etape, a la forme du produit : « 2 · Validation
   * comptable » (mes-workflow.ts l. 295).
   */
  const etapeLisible = t => String(t).replace(/^(\d+)\s*/, '$1 · ');

  /**
   * Liste d'un lot : cinq noms au plus, puces « • », puis un decompte
   * (mes-workflow.ts l. 262-266). La maquette listait tout, avec « · ».
   */
  function listerLot(donnees) {
    const noms = donnees.slice(0, 5).map(d => `• ${d.nom}`);
    if (donnees.length > 5) noms.push(`• … et ${donnees.length - 5} autre(s)`);
    return noms.join('\n');
  }

  async function signer(lignes) {
    if (!lignes.length) return;
    const donnees = lignes.map(lireLigne);
    const message = lignes.length === 1
      ? `Confirmez-vous la signature de « ${donnees[0].nom} » (étape ${etapeLisible(donnees[0].etape)}) ?`
      : `Confirmez-vous la signature de :\n${listerLot(donnees)}`;

    const ok = await demander({
      titre: lignes.length === 1 ? 'Signer le document' : `Signer ${lignes.length} document(s)`,
      message,
      confirmer: 'Signer',
    });
    if (!ok) return;

    lignes.forEach((tr, i) => {
      historique().prepend(ligneHistorique(donnees[i], 'Signé', null));
      tr.remove();
    });
    majCompteurs();
    avis(lignes.length === 1 ? 'Document signé.' : `${lignes.length} document(s) signé(s).`, 'ok');
  }

  async function rejeter(lignes) {
    if (!lignes.length) return;
    const donnees = lignes.map(lireLigne);
    /* Le motif est OBLIGATOIRE, comme dans le produit : un refus sans raison
       renvoie le deposant a ses suppositions. */
    const motif = await demander({
      titre: lignes.length === 1 ? 'Rejeter la signature' : `Rejeter ${lignes.length} document(s)`,
      message: lignes.length === 1
        ? `Indiquez le motif du rejet de « ${donnees[0].nom} ».`
        : `Indiquez le motif du rejet de :\n${listerLot(donnees)}`,
      confirmer: 'Rejeter',
      danger: true,
      champ: { label: 'Motif du rejet', exemple: 'Ex. montant erroné, pièce manquante…' },
    });
    if (motif == null) return;

    lignes.forEach((tr, i) => {
      historique().prepend(ligneHistorique(donnees[i], 'Rejeté', motif));
      tr.remove();
    });
    majCompteurs();
    /* Le produit compte des DOCUMENTS, pas des signatures, dans le retour
       d'une action groupee (mes-workflow.ts l. 279). */
    avis(lignes.length === 1 ? 'Signature rejetée.' : `${lignes.length} document(s) rejeté(s).`, 'ko');
  }

  async function relancer(tr) {
    const c = tr.querySelectorAll('td');
    const donnees = {
      nom:    c[0].querySelector('.nom')?.textContent.trim() ?? '',
      sous:   c[0].querySelector('.sous')?.textContent.trim() ?? '',
      type:   c[1].textContent.trim(),
      etape:  c[2].textContent.trim(),
      espace: tr.dataset.espace || '—',
    };
    const ok = await demander({
      titre: 'Relancer la validation',
      message: `Le circuit de « ${donnees.nom} » est arrêté par un rejet.\n`
             + `Le relancer remet l'étape refusée à traiter ; les étapes déjà signées le restent.`,
      confirmer: 'Relancer',
    });
    if (!ok) return;

    aTraiter().prepend(ligneATraiter(donnees));
    tr.remove();
    majCompteurs();
    avis('Circuit relancé.', 'ok');
  }

  /* ------------------------------------------------------------ branchement */

  document.addEventListener('DOMContentLoaded', () => {
    if (!aTraiter() || !historique()) return;   // page sans ces tableaux

    // Delegation : les lignes sont creees et detruites en permanence, un
    // ecouteur pose sur chacune serait perdu au premier deplacement.
    document.addEventListener('click', e => {
      const bouton = e.target.closest('button.act');
      if (!bouton) return;
      const tr = bouton.closest('tr');
      const titre = bouton.getAttribute('title') || '';
      if (titre === 'Signer')  signer([tr]);
      else if (titre === 'Rejeter') rejeter([tr]);
      else if (titre.startsWith('Relancer')) relancer(tr);
    });

    // Actions groupees : elles portent sur les lignes cochees, et sur elles seules.
    const cochees = () => [...aTraiter().querySelectorAll('input[type=checkbox]:checked')]
      .map(c => c.closest('tr'));
    document.querySelectorAll('[data-bulk] .bouton').forEach(b => {
      b.addEventListener('click', () => {
        const lignes = cochees();
        if (!lignes.length) return;
        (b.classList.contains('danger') ? rejeter : signer)(lignes);
      });
    });

    /* Le produit CITE la recherche dans son etat vide : « Aucun document à
       traiter ne correspond à « facture ». » Le filtre de shell.js se contente
       de montrer le bloc ; on en reecrit la phrase apres lui. */
    document.querySelectorAll('[data-filtre]').forEach(champ => {
      champ.addEventListener('input', () => {
        const q = champ.value.trim();
        document.querySelectorAll('[data-vide-recherche]').forEach(el => {
          const debut = el.dataset.videRecherche;
          el.textContent = q ? `${debut} « ${q} ».` : `${debut.replace(/ à$/, '')}.`;
        });
      });
    });

    majCompteurs();
  });
})();
