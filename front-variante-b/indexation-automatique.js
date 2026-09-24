/* ============================================================
   TÉLÉVERSER UN DOCUMENT — la mecanique de l'ecran
   ------------------------------------------------------------
   L'ecran etait une photographie : un depot de facture, fige, ou le
   type de document ne commandait rien, l'interrupteur d'indexation
   automatique ne coupait rien, la reference composee ne se recomposait
   pas, et « Téléverser » ne deposait pas.

   Or c'est l'ecran ou le produit montre sa seule vraie regle de
   composition : LE TYPE COMMANDE TOUT — le dossier, les formats
   acceptes, le plan d'indexation, donc les index a saisir, donc le nom
   compose. Une maquette qui ne le joue pas ne montre pas le produit.

   Ce qui se joue desormais dans le DOM :
     - changer de type refait la fiche d'index, la charte et le nom ;
     - un type SANS plan depose sans aucun index, et le dit ;
     - un plan « Manuel » rend le nom saisissable — c'est la difference
       entre les deux plans du referentiel ;
     - couper l'indexation automatique vide les valeurs deduites et
       change la provenance de chaque champ ;
     - la reference et le nom se recomposent a chaque frappe ;
     - le compteur d'obligatoires et le blocage de « Téléverser »
       suivent, c'est le garde-fou que l'ecran taisait ;
     - le fichier choisi est refuse si son extension n'est pas dans les
       types autorises du type de document.

   Les valeurs de reference viennent des autres ecrans de la maquette
   (types, plans, index) : rien n'est invente ici.

   Toutes les popups passent par `demander()` de shell.js, toutes les
   confirmations par `avis()`.
   ============================================================ */

(() => {
  const $ = s => document.querySelector(s);

  /* ------------------------------------------------------- le referentiel
     Recopie de ce que montrent « Type de document », « Plan d'indexation »
     et « Index ». Une valeur qui ne s'y retrouverait pas serait un mensonge
     de plus a corriger. */
  const TYPES = {
    'Facture': {
      espace: 'Comptabilité', plan: 'Fiche Facture', mode: 'Auto', separateur: '_',
      formats: ['pdf', 'docx'], taille: '10 Mo',
      index: [
        { nom: "Numéro de facture", type: 'Nombre', requis: true,  deduit: '2026999' },
        { nom: "Date d'émission",   type: 'Date',   requis: true,  deduit: '2026-06-22' },
        { nom: 'Fournisseur',       type: 'Texte',  requis: false, deduit: 'Nova Import' },
        { nom: 'Priorité',          type: 'Liste',  requis: false, deduit: 'Urgente',
          options: ['Basse', 'Normale', 'Haute', 'Urgente'] },
      ],
    },
    'Convention': {
      espace: 'Direction', plan: 'Fiche Convention', mode: 'Manuel', separateur: '_',
      formats: ['pdf'], taille: '10 Mo',
      index: [
        { nom: "Date d'émission", type: 'Date',  requis: true,  deduit: '2026-07-24' },
        { nom: 'Fournisseur',     type: 'Texte', requis: false, deduit: 'Cabinet Atlas' },
      ],
    },
    /* Deux types sans plan : le produit les depose SANS AUCUN INDEX. C'est la
       formule exacte de l'ecran de depot, et elle doit rester visible. */
    'Compte rendu': { espace: 'Direction', plan: null, formats: ['pdf', 'docx'], taille: '5 Mo', index: [] },
    'Contrat':      { espace: 'Ressources humaines', plan: null, formats: ['pdf'], taille: '20 Mo', index: [] },
  };

  const FICHIERS = ['scan0001.pdf', 'facture-nova.pdf', 'convention-atlas.pdf', 'proces-verbal.pdf', 'contrat-agent.docx'];
  const ETIQUETTES = ['Urgent', 'Confidentiel', 'À vérifier', 'Validé'];

  const etat = { type: 'Facture', fichier: 'scan0001.pdf', auto: true, valeurs: {} };

  const modele = () => TYPES[etat.type];
  const extension = f => (f || '').split('.').pop().toLowerCase();

  /* ------------------------------------------------------- la fiche d'index */

  /** Rebatit la colonne des index. Appele au changement de TYPE, jamais a la
      frappe : reconstruire pendant la saisie volerait le focus. */
  function batirIndex() {
    const corps = $('.propositions tbody');
    corps.textContent = '';
    modele().index.forEach((ix, i) => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><span class="nom"></span><span class="sous"></span></td>
        <td><span class="provenance"></span></td>`;
      const c = tr.querySelectorAll('td');
      const nom = c[0].querySelector('.nom');
      nom.textContent = ix.nom;
      if (ix.requis) {
        const et = document.createElement('span');
        et.className = 'p-requis';
        et.setAttribute('aria-label', 'obligatoire');
        et.textContent = '*';
        nom.appendChild(et);
      }
      c[0].querySelector('.sous').textContent = ix.type;

      const controle = ix.type === 'Liste'
        ? document.createElement('select')
        : document.createElement('input');
      controle.className = 'saisie';
      controle.dataset.index = String(i);
      controle.setAttribute('aria-label', ix.nom);
      if (ix.type === 'Liste') {
        ix.options.forEach(o => {
          const opt = document.createElement('option');
          opt.value = o; opt.textContent = o;
          controle.appendChild(opt);
        });
      } else if (ix.type === 'Date') {
        controle.type = 'date';
      } else {
        controle.type = 'text';
      }
      controle.value = etat.valeurs[ix.nom] ?? '';
      c[1].prepend(controle);
      corps.appendChild(tr);
    });
    majFiche();
  }

  /** L'etat vide de la fiche : un type sans plan n'a rien a indexer. */
  function majEtatVideIndex() {
    const bloc = $('.propositions').closest('.registre-wrap');
    let vide = bloc.querySelector('.vide');
    if (!vide) {
      vide = document.createElement('div');
      vide.className = 'vide';
      vide.innerHTML = "<div class=\"t\">Aucun index à renseigner</div>"
                     + "<div class=\"s\">Ce type de document n'a pas de plan d'indexation : le document sera déposé sans aucun index.</div>";
      bloc.appendChild(vide);
    }
    const rien = modele().index.length === 0;
    vide.hidden = !rien;
    $('.propositions').hidden = rien;
    /* La legende des obligatoires n'a plus d'objet sans index. */
    const legende = bloc.parentElement.querySelector('p.fil');
    if (legende) legende.hidden = rien;
  }

  /* --------------------------------------------------------- recomposition */

  const valeursSaisies = () => modele().index.map(ix => (etat.valeurs[ix.nom] ?? '').trim());

  const manquants = () => modele().index.filter(ix => ix.requis && !(etat.valeurs[ix.nom] ?? '').trim());

  /** Le nom compose : les valeurs, dans l'ordre du plan, liees par le separateur. */
  function composer() {
    const m = modele();
    if (!m.plan || m.mode !== 'Auto') return '';
    if (manquants().length) return '';
    return valeursSaisies().filter(Boolean).join(m.separateur);
  }

  function majFiche() {
    const m = modele();
    const auto = etat.auto && m.index.length > 0;

    /* Provenance : c'est la GED qui a lu, ou l'operateur qui a saisi. Le
       produit distingue les deux, et c'est ce qui justifie la relecture. */
    document.querySelectorAll('.propositions .provenance').forEach((p, i) => {
      const ix = m.index[i];
      const deduit = auto && ix && (etat.valeurs[ix.nom] ?? '') === ix.deduit && ix.deduit;
      p.className = 'provenance' + (deduit ? ' contenu' : '');
      p.textContent = deduit ? 'lu dans le document' : 'saisie manuelle';
    });

    majEtatVideIndex();

    // --- Bandeau de lecture et charte
    const lecture = $('.lecture');
    lecture.hidden = !auto;
    const deduits = auto ? m.index.filter(ix => (etat.valeurs[ix.nom] ?? '') === ix.deduit && ix.deduit).length : 0;
    const charte = $('.charte');
    charte.textContent = '';
    if (!m.plan) {
      charte.append("Aucun plan d'indexation — le document sera déposé ");
      const f = document.createElement('strong');
      f.textContent = 'sans aucun index';
      charte.append(f, '.');
    } else {
      charte.append('Charte ');
      const p = document.createElement('strong');
      p.textContent = m.plan;
      const n = document.createElement('strong');
      n.textContent = `${deduits}/${m.index.length}`;
      charte.append(p, ' — ', n, ' champ(s) déduits automatiquement');
    }

    // --- Interrupteur : son texte change selon sa position (textes du produit)
    $('.auto-aide').textContent = etat.auto
      ? 'La GED déduit les index du fichier. Vous relisez et corrigez avant de déposer.'
      : 'Vous saisissez les index vous-même. Activez pour que la GED tente de les remplir.';
    $('.bascule input').disabled = m.index.length === 0;
    $('.auto-bloc').style.opacity = m.index.length === 0 ? '.55' : '';

    // --- Référence composée
    const compose = composer();
    const ref = $('.reference');
    ref.hidden = !(m.plan && m.mode === 'Auto');
    $('.reference .rv').textContent = compose || '—';

    // --- Nom du document
    const nom = $('#nom-doc');
    const aide = nom.parentElement.querySelector('.aide');
    if (m.plan && m.mode === 'Auto') {
      /* Non saisissable : c'est le serveur qui compose. */
      nom.disabled = true;
      nom.style.background = 'var(--fond-2)';
      nom.style.color = 'var(--encre-2)';
      nom.value = compose;
      nom.placeholder = 'Composé au dépôt';
      aide.textContent = `Composé au dépôt d'après la charte du plan « ${m.plan} » et les index ci-dessus.`;
    } else {
      nom.disabled = false;
      nom.style.background = '';
      nom.style.color = '';
      if (!nom.dataset.saisi) nom.value = etat.fichier || '';
      nom.placeholder = 'Nom du document';
      aide.textContent = m.plan
        ? `Le plan « ${m.plan} » est en nommage Manuel : le nom reste celui que vous saisissez.`
        : "Sans plan d'indexation, le nom du fichier déposé est conservé.";
    }

    // --- Garde-fou : le compteur et le blocage du bouton
    const reste = manquants().length;
    const garde = $('.garde p');
    garde.textContent = etat.fichier
      ? `${reste} champ(s) obligatoire(s) à renseigner`
      : 'Aucun fichier choisi';
    const televerser = boutonTeleverser();
    const bloque = reste > 0 || !etat.fichier;
    televerser.disabled = bloque;
    televerser.style.opacity = bloque ? '.45' : '';
    televerser.style.cursor = bloque ? 'not-allowed' : '';
    televerser.style.pointerEvents = bloque ? 'none' : '';
    televerser.setAttribute('aria-disabled', String(bloque));

    // --- Rappel du fichier choisi
    $('.volet .muet').textContent = etat.fichier || 'Aucun fichier';
  }

  const boutonTeleverser = () => [...document.querySelectorAll('.garde .droite button')]
    .find(b => b.textContent.trim() === 'Téléverser');
  const boutonAnnuler = () => [...document.querySelectorAll('.garde .droite button')]
    .find(b => b.textContent.trim() === 'Annuler');

  /* ------------------------------------------------------------ decisions */

  /** Remplit (ou vide) les valeurs deduites par la GED. */
  function appliquerAuto() {
    modele().index.forEach(ix => {
      if (etat.auto) etat.valeurs[ix.nom] = ix.deduit || '';
      else if (etat.valeurs[ix.nom] === ix.deduit) etat.valeurs[ix.nom] = '';
    });
    /* Une liste garde toujours une valeur : un <select> ne peut pas etre vide.
       On retombe sur sa premiere option plutot que d'afficher un champ vide
       que le controle dementirait. */
    modele().index.forEach(ix => {
      if (ix.type === 'Liste' && !etat.valeurs[ix.nom]) etat.valeurs[ix.nom] = ix.options[0];
    });
  }

  function changerType(nouveau) {
    etat.type = nouveau;
    etat.valeurs = {};
    delete $('#nom-doc').dataset.saisi;
    appliquerAuto();
    batirIndex();
    avis(`Type « ${nouveau} » — ${modele().plan ? 'plan ' + modele().plan : "aucun plan d'indexation"}.`, '');
  }

  async function choisirFichier() {
    const m = modele();
    const v = await demander({
      titre: 'Choisir un fichier',
      message: `Types autorisés pour « ${etat.type} » : ${m.formats.join(', ')} · ${m.taille} maximum.`,
      confirmer: 'Choisir',
      champs: [{ nom: 'fichier', label: 'Fichier', type: 'liste', options: FICHIERS, valeur: etat.fichier }],
    });
    if (!v) return;
    /* Le type commande les formats : un fichier hors liste est refuse, comme
       au depot. Le taire ferait passer la regle pour une decoration. */
    if (!m.formats.includes(extension(v.fichier))) {
      avis(`« ${v.fichier} » n'est pas un format autorisé pour ce type (${m.formats.join(', ')}).`, 'ko');
      return;
    }
    etat.fichier = v.fichier;
    if (!m.plan || m.mode !== 'Auto') { delete $('#nom-doc').dataset.saisi; }
    majFiche();
    avis(`Fichier « ${v.fichier} » sélectionné.`, 'ok');
  }

  async function televerser() {
    const m = modele();
    const nom = $('#nom-doc').value.trim() || etat.fichier;
    const lignes = m.index.map(ix => `· ${ix.nom} : ${etat.valeurs[ix.nom] || '—'}`);
    const ok = await demander({
      titre: 'Téléverser le document',
      message: `« ${nom} » sera déposé dans ${m.espace}, sous le type ${etat.type}.`
             + `\nFichier : ${etat.fichier}`
             + (lignes.length ? `\n\nIndex enregistrés :\n${lignes.join('\n')}` : `\n\nAucun index : ce type n'a pas de plan d'indexation.`),
      confirmer: 'Téléverser',
    });
    if (!ok) return;
    avis('Document déposé.', 'ok');
    /* On revient a un ecran pret pour un nouveau depot : le fichier part, le
       bouton se reverrouille. Une maquette qui reste sur le depot accompli
       laisse croire qu'on peut le refaire deux fois. */
    etat.fichier = null;
    delete $('#nom-doc').dataset.saisi;
    majFiche();
  }

  async function annuler() {
    const ok = await demander({
      titre: 'Abandonner le dépôt',
      message: "Le fichier choisi et les index saisis seront oubliés. Rien n'a été enregistré.",
      confirmer: 'Abandonner',
      danger: true,
    });
    if (!ok) return;
    etat.fichier = null;
    etat.valeurs = {};
    appliquerAuto();
    delete $('#nom-doc').dataset.saisi;
    $('#etiquettes').value = '';
    $('#expiration').value = '';
    batirIndex();
    avis('Dépôt abandonné.', '');
  }

  /* ------------------------------------------------------------ branchement */

  document.addEventListener('DOMContentLoaded', () => {
    if (!$('.propositions')) return;

    /* Les etiquettes proposees sont celles du referentiel : la maquette ne
       doit pas laisser inventer un mot-cle qui n'existe sur aucun ecran. */
    const dl = document.createElement('datalist');
    dl.id = 'liste-etiquettes';
    ETIQUETTES.forEach(t => { const o = document.createElement('option'); o.value = t; dl.appendChild(o); });
    $('#etiquettes').after(dl);
    $('#etiquettes').setAttribute('list', 'liste-etiquettes');

    appliquerAuto();
    batirIndex();

    $('#type-doc').addEventListener('change', e => changerType(e.target.value));

    $('.bascule input').addEventListener('change', e => {
      etat.auto = e.target.checked;
      appliquerAuto();
      batirIndex();
      avis(etat.auto ? 'Indexation automatique activée.' : 'Indexation automatique coupée : saisissez les index.', '');
    });

    /* La saisie recompose, elle ne rebatit pas : le focus reste dans le champ. */
    document.addEventListener('input', e => {
      const c = e.target.closest('.propositions [data-index]');
      if (c) {
        etat.valeurs[modele().index[+c.dataset.index].nom] = c.value;
        majFiche();
        return;
      }
      if (e.target === $('#nom-doc') && !$('#nom-doc').disabled) $('#nom-doc').dataset.saisi = '1';
    });
    document.addEventListener('change', e => {
      const c = e.target.closest('.propositions select[data-index]');
      if (!c) return;
      etat.valeurs[modele().index[+c.dataset.index].nom] = c.value;
      majFiche();
    });

    $('#fichier').addEventListener('click', choisirFichier);
    boutonTeleverser()?.addEventListener('click', televerser);
    boutonAnnuler()?.addEventListener('click', annuler);
  });
})();
