/* ============================================================
   ACCUEIL — la mecanique de l'ecran
   ------------------------------------------------------------
   Le tableau de bord montrait trois documents « À valider » qu'on ne
   pouvait ni signer ni refuser, et un bouton « Personnaliser » qui ne
   personnalisait rien. Les quatre indicateurs etaient des nombres
   graves : ils annonçaient 3 signatures en attente meme apres qu'on
   ait signe les trois.

   Ce qui se joue desormais dans le DOM :
     - signer ou rejeter depuis l'encart, avec la MÊME confirmation et
       le MÊME motif obligatoire que « Mes workflow » ;
     - la ligne quitte l'encart, l'indicateur « En attente de
       signature » et la pastille du menu suivent a la meme seconde ;
     - l'encart vide le dit, au lieu de rester blanc ;
     - « Personnaliser » affiche ou masque un encart, comme le tableau
       de bord du produit qui se compose.

   Toutes les popups passent par `demander()` de shell.js, toutes les
   confirmations par `avis()`.
   ============================================================ */

(() => {
  /* Tracés repris a l'identique de `mes-workflow.js` : signer et rejeter
     doivent porter la meme icone ici et la-bas. */
  const IC = {
    signer: `<svg class="lucide lucide-circle-check" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/></svg>`,
    rejeter: `<svg class="lucide lucide-circle-x" xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg>`,
  };

  const panneau = titre => [...document.querySelectorAll('.grid .panel')]
    .find(p => p.querySelector('.panel-h h2')?.textContent.trim() === titre);

  const aValider = () => panneau('À valider');
  const listeAValider = () => aValider()?.querySelector('.rows');

  /* ----------------------------------------------------------- compteurs */

  function majCompteurs() {
    const liste = listeAValider();
    if (!liste) return;
    const n = liste.querySelectorAll('li').length;

    /* Les rangs se relisent apres chaque decision : « 01, 03 » raconterait
       qu'une ligne manque, alors qu'elle a ete traitee. */
    [...liste.querySelectorAll('li .ref')].forEach((r, i) => {
      r.textContent = String(i + 1).padStart(2, '0');
    });

    const tuile = [...document.querySelectorAll('.metric')]
      .find(m => m.querySelector('.k')?.textContent.trim() === 'En attente de signature');
    if (tuile) {
      const v = tuile.querySelector('.v');
      if (!tuile.dataset.depart) {
        tuile.dataset.depart = +(v.dataset.count || v.textContent) || 1;
        tuile.dataset.jauge = parseFloat(tuile.querySelector('.bar i').style.width) || 48;
      }
      v.textContent = n;
      v.dataset.count = n;
      const part = Math.max(0, Math.min(100, (+tuile.dataset.jauge) * n / (+tuile.dataset.depart)));
      tuile.querySelector('.bar i').style.width = part + '%';
    }

    /* La pastille du menu compte la meme chose que l'encart : elles avaient
       deja diverge une fois dans ce projet. */
    const pastille = document.querySelector('.tabs .compte');
    if (pastille) {
      pastille.textContent = n;
      pastille.setAttribute('aria-label', `${n} document(s) en attente`);
      pastille.hidden = n === 0;
    }

    majVide(n);
  }

  /** Un encart vide doit le dire, sinon il ressemble a un chargement bloque. */
  function majVide(n) {
    const corps = aValider()?.querySelector('.panel-b');
    if (!corps) return;
    let vide = corps.querySelector('.vide');
    if (!vide) {
      vide = document.createElement('div');
      vide.className = 'vide';
      vide.innerHTML = '<div class="t">Rien à valider</div>'
                     + '<div class="s">Toutes les signatures qui vous revenaient sont traitées.</div>';
      corps.appendChild(vide);
    }
    vide.hidden = n > 0;
    const liste = listeAValider();
    if (liste) liste.hidden = n === 0;
  }

  /* ------------------------------------------------------------ decisions */

  const lireLigne = li => ({
    nom:  li.querySelector('.ttl')?.textContent.trim() ?? '',
    meta: li.querySelector('.meta')?.textContent.trim() ?? '',
  });

  async function signer(li) {
    const e = lireLigne(li);
    const ok = await demander({
      titre: 'Signer le document',
      message: `Confirmez-vous la signature de « ${e.nom} » ?\n${e.meta}`,
      confirmer: 'Signer',
    });
    if (!ok) return;
    li.remove();
    majCompteurs();
    avis('Document signé.', 'ok');
  }

  async function rejeter(li) {
    const e = lireLigne(li);
    /* Le motif est OBLIGATOIRE, comme dans « Mes workflow » et dans le
       produit : un refus sans raison renvoie le deposant a ses suppositions. */
    const motif = await demander({
      titre: 'Rejeter la signature',
      message: `Indiquez le motif du rejet de « ${e.nom} ».`,
      confirmer: 'Rejeter',
      danger: true,
      champ: { label: 'Motif du rejet', exemple: 'Ex. montant erroné, pièce manquante…' },
    });
    if (motif == null) return;
    li.remove();
    majCompteurs();
    avis('Signature rejetée.', 'ko');
  }

  /* ------------------------------------------------- tableau de bord
     Le tableau de bord du produit se compose : les encarts s'ajoutent et se
     deplacent. La maquette en montre la moitie utile — afficher ou masquer —
     sans inventer un glisser-deposer que le client ne pourrait pas essayer. */

  const ENCARTS = ['À valider', 'Raccourcis', 'Documents récents'];

  /* Une grille de deux colonnes dont une seule est occupee laisse un blanc
     large comme la moitie de l'ecran : c'est le reproche deja fait sur cette
     page. La grille se resserre quand il ne reste qu'un encart. */
  function majGrilles() {
    document.querySelectorAll('.page > .grid').forEach(grille => {
      const visibles = [...grille.children].filter(e => !e.hidden);
      grille.hidden = visibles.length === 0;
      grille.style.gridTemplateColumns = visibles.length === 1 ? '1fr' : '';
    });
  }

  async function personnaliser() {
    const etat = nom => (panneau(nom)?.hidden ? 'Masqué' : 'Affiché');
    const v = await demander({
      titre: 'Personnaliser le tableau de bord',
      message: 'Choisissez l\'encart à afficher ou à masquer.\n'
             + ENCARTS.map(n => `· ${n} : ${etat(n).toLowerCase()}`).join('\n'),
      confirmer: 'Appliquer',
      champs: [
        { nom: 'encart', label: 'Encart', type: 'liste', options: ENCARTS },
        { nom: 'affichage', label: 'Affichage', type: 'liste', options: ['Afficher', 'Masquer'] },
      ],
    });
    if (!v) return;
    const cible = panneau(v.encart);
    if (!cible) return;
    cible.hidden = v.affichage === 'Masquer';
    majGrilles();
    avis(`Encart « ${v.encart} » ${v.affichage === 'Masquer' ? 'masqué' : 'affiché'}.`, '');
  }

  /* ------------------------------------------------------------ branchement */

  document.addEventListener('DOMContentLoaded', () => {
    const liste = listeAValider();
    if (!liste) return;

    /* Les actions sont posees ici et non dans le HTML : l'encart est une liste
       qui se vide, et son balisage doit rester le meme sur les deux ecrans qui
       l'affichent. */
    liste.querySelectorAll('li').forEach(li => {
      if (li.querySelector('.rows-act')) return;
      const bloc = document.createElement('span');
      bloc.className = 'rows-act';
      bloc.innerHTML =
        `<button class="act" type="button" title="Signer" aria-label="Signer">${IC.signer}</button>` +
        `<button class="act danger" type="button" title="Rejeter" aria-label="Rejeter">${IC.rejeter}</button>`;
      li.appendChild(bloc);
    });

    majCompteurs();
    majGrilles();

    document.addEventListener('click', e => {
      const bouton = e.target.closest('.rows-act .act');
      if (bouton) {
        const li = bouton.closest('li');
        if (bouton.getAttribute('title') === 'Signer') signer(li); else rejeter(li);
        return;
      }
      const perso = [...document.querySelectorAll('.head-actions .bouton')]
        .find(b => b === e.target.closest('.head-actions .bouton'));
      if (perso && perso.textContent.trim() === 'Personnaliser') personnaliser();
    });
  });
})();
