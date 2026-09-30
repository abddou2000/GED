import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
// Directives autonomes, pas le module : libellés français du calendrier (ANO-F-024).
import { CHAMP_DATE } from '../../../core/calendrier-fr';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { DocumentService } from '../document.service';
import { IndexationService } from '../../indexation/indexation.service';
import { versDate, versIso } from '../../../core/dates';
import { TypeDocumentService } from '../../type-document/type-document.service';
import { SelectOption } from '../../type-document/type-document.model';
import { NIVEAUX_CONFIDENTIALITE } from '../document.model';
import { Apercu, Proposition, lireBooleen } from '../../indexation/indexation.model';
import { EtiquetteService } from '../../etiquette/etiquette.service';
import { Etiquette } from '../../etiquette/etiquette.model';
import { PlanIndexationService } from '../../plan-indexation/plan-indexation.service';
import { IndexService } from '../../index/index.service';
import { IndexField } from '../../index/index.model';

/**
 * Comment le dépôt s'est terminé — l'écran appelant en tire le message affiché.
 * `false` (fermeture sans dépôt) reste la valeur d'annulation.
 */
export type IssueDepot = 'indexe' | 'sans-plan' | 'a-indexer';

/** Un champ indexé du formulaire : la proposition du serveur, et rien d'autre. */
interface ChampApercu extends Proposition {}

/**
 * Une demande d'aperçu, ou `null` pour annuler celle qui court.
 *
 * <p>Le `null` n'est pas une commodité : sans lui, retirer le fichier ou tomber
 * sur un refus laisserait la requête précédente arriver et repeupler des champs
 * qui ne correspondent plus à rien.</p>
 */
type DemandeApercu = { typeId: string; fichier: File } | null;

/**
 * Dépôt d'un document — **une seule fenêtre, un seul bouton**.
 *
 * <p>Le type, le fichier, le réglage d'indexation, les champs indexés, le nom,
 * les étiquettes et l'échéance tiennent sur la même page. Rien n'est envoyé au
 * serveur tant que « Téléverser » n'a pas été cliqué.</p>
 *
 * <p><b>Pourquoi ce n'est plus en deux étapes.</b> L'écran enchaînait autrefois
 * sur une seconde fenêtre d'indexation, et cet enchaînement coûtait cher :
 * les index déduits étaient écrits AVANT toute confirmation — un document
 * abandonné en route restait renommé {@code 2026003_2026-02-02_ACME_?}, avec
 * des valeurs que personne n'avait validées — et la seconde fenêtre verrouillait
 * sa propre fermeture, si bien que la seule sortie était de recharger la page.
 * Une page unique supprime les deux d'un coup : il n'y a plus d'état
 * intermédiaire à écrire, ni de raison de retenir l'opérateur.</p>
 */
@Component({
  selector: 'app-document-upload',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, CHAMP_DATE, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule, MatTooltipModule, MatSlideToggleModule, MatCheckboxModule,
  ],
  templateUrl: './document-upload.html',
  styleUrl: './document-upload.scss',
})
export class DocumentUpload implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(DocumentService);
  private typeService = inject(TypeDocumentService);
  private etiquetteService = inject(EtiquetteService);
  private indexation = inject(IndexationService);
  private plans = inject(PlanIndexationService);
  private index = inject(IndexService);
  private ref = inject(MatDialogRef<DocumentUpload, IssueDepot | false>);
  private destroyRef = inject(DestroyRef);

  /** Types disponibles, avec leur plan — nécessaire pour prévenir quand il manque. */
  types = signal<SelectOption[]>([]);
  /** Type actuellement sélectionné dans la liste, ou null. */
  typeChoisi = signal<SelectOption | null>(null);
  selectedFile = signal<File | null>(null);
  loading = signal(false);
  serverError = signal<string | null>(null);

  /** L'aperçu en cours de calcul : le serveur lit le fichier sans le déposer. */
  apercuEnCours = signal(false);

  /** Demandes d'aperçu ; `null` annule celle qui court. Voir `brancherApercu`. */
  private readonly demandesApercu = new Subject<DemandeApercu>();

  /**
   * Index que l'opérateur a saisis ou corrigés lui-même.
   *
   * <p>On ne peut pas s'en remettre à `source` pour le savoir : ce champ décrit
   * la PROPOSITION du serveur et ne bouge pas quand quelqu'un écrit par-dessus.
   * S'y fier revenait à effacer une correction manuelle en croyant retirer une
   * suggestion de la machine.</p>
   */
  private readonly saisisALaMain = new Set<string>();

  /**
   * L'indexation automatique est un choix, pas un passage obligé : cet
   * interrupteur décide si la GED déduit les index du fichier, ou si l'opérateur
   * les saisit lui-même. Il est réglé sur la disponibilité réelle du moteur.
   */
  indexationAuto = signal(true);
  /** Le moteur est-il utilisable ici ? (coupé globalement, ou pour ce type) */
  lectureDisponible = signal(true);
  motifIndisponible = signal<string | null>(null);

  /**
   * Le document déposé, quand la création a réussi mais que l'indexation a
   * échoué juste après. Il existe alors côté serveur : on ne peut plus proposer
   * « Annuler » comme si de rien n'était.
   */
  private documentDepose = signal<string | null>(null);

  /** Étiquettes proposées au dépôt. Vide si le référentiel est vide ou
      inaccessible : le champ disparaît alors plutôt que d'afficher une liste
      creuse. */
  protected readonly etiquettes = signal<Etiquette[]>([]);

  /** Exposés au gabarit : le sélecteur manipule des dates, l'API des chaînes. */
  protected readonly versDate = versDate;
  protected readonly versIso = versIso;
  protected readonly niveaux = NIVEAUX_CONFIDENTIALITE;

  form: FormGroup = this.fb.group({
    typeDocumentId: [null as string | null, Validators.required],
    name: [''],
    /* Vide = niveau par défaut du type, fixé par le serveur (§12.3). */
    confidentialite: [null as string | null],
    expirationDate: [null as Date | null],
    /* Les étiquettes sont FACULTATIVES : aucun validateur. Elles classent un
       document en travers des dossiers et des types ; exiger un classement
       transversal au moment du dépôt bloquerait un opérateur qui veut
       seulement déposer un fichier. On peut toujours les poser après coup
       depuis la fiche du document. */
    etiquetteIds: [[] as string[]],
    /* Socle commun (§4.2.3, §12.7 ; ANO-F-006) : l'objet, et la date DU
       DOCUMENT — celle d'une facture, d'un courrier —, distincte de la date du
       dépôt. C'est elle que la recherche et les tris retiennent : sans ce champ,
       tout dépôt fait à l'écran datait le document du jour du dépôt. Vide = le
       serveur prend la date du dépôt. */
    objet: ['', Validators.maxLength(1000)],
    dateDocument: [null as Date | null],
  });

  ngOnInit(): void {
    this.brancherSortiesDialogue();
    this.brancherApercu();
    this.typeService.forSelect().subscribe(l => this.types.set(l));
    /* Une page large plutôt qu'une pagination : le sélecteur doit proposer
       toutes les étiquettes d'un coup. Si le référentiel dépassait ce volume,
       il faudrait un point d'API dédié — le signaler ici vaut mieux que de
       tronquer la liste en silence. */
    this.etiquetteService.list(0, 200).subscribe({
      next: p => this.etiquettes.set(p.content),
      error: () => this.etiquettes.set([]),
    });
    /* Nature des index (nombre, date, liste et ses options) : le plan ne
       renvoie que des libellés, et sans la nature on afficherait une simple
       zone de texte là où il faut un sélecteur ou un calendrier. */
    this.index.list(0, 500).subscribe({
      next: p => this.natures.set(new Map(p.content.map(i => [i.id, i]))),
      error: () => { /* on retombera sur du texte libre, jamais bloquant */ },
    });
    // L'indexation automatique ne lit plus que le nom du fichier (charte du
    // plan) : le contenu n'alimente aucun index (§4.3.3), elle ne dépend donc
    // plus de l'état de la chaîne OCR.
    // Le formulaire réactif n'est pas un signal : on suit le champ pour tenir
    // `typeChoisi` à jour et pouvoir avertir dès la sélection.
    this.form.get('typeDocumentId')!.valueChanges.subscribe(id => {
      this.typeChoisi.set(this.types().find(x => x.id === id) ?? null);
      /* Changer de type change de plan, donc de champs : les saisies notées
         portaient sur des index qui n'existent plus ici. */
      this.saisisALaMain.clear();
      /* Le contrôle est rejoué : choisir le fichier puis changer de type est un
         enchaînement courant, et le verdict dépend du type. */
      const fichier = this.selectedFile();
      this.refusFichier.set(fichier ? this.controler(fichier) : null);
      /* Les champs à renseigner s'affichent DÈS le choix du type, vides : on
         doit savoir ce qu'on aura à remplir avant d'aller chercher un fichier.
         Le fichier, lui, ne fait que les remplir. */
      this.chargerChampsDuType();
      // Et l'aperçu avec : le découpage dépend du plan, donc du type. Sans
      // cette ligne, choisir le fichier AVANT le type n'affichait jamais les
      // champs indexés.
      this.rafraichirApercu();

      /* Le champ « Nom » suit le type. `emitEvent: false` est indispensable :
         sans lui, désactiver le contrôle relancerait cet abonnement en boucle. */
      const nom = this.form.get('name')!;
      if (this.nomAutomatique()) {
        nom.reset('', { emitEvent: false });
        nom.disable({ emitEvent: false });
      } else if (nom.disabled) {
        nom.enable({ emitEvent: false });
      }
    });
  }

  /**
   * Échap et clic hors de la fenêtre passent par `cancel()`, jamais par la
   * fermeture native.
   *
   * <p>Angular Material ferme alors le dialogue sur `undefined`. Après création,
   * cela perdait l'issue {@code 'a-indexer'} : l'appelant ne rafraîchissait pas
   * sa liste, n'affichait aucun message, et l'opérateur repartait sans savoir
   * qu'un document venait d'être créé. On ne verrouille pas la fenêtre pour
   * autant — retenir quelqu'un devant un formulaire est précisément ce que la
   * page unique devait supprimer.</p>
   */
  private brancherSortiesDialogue(): void {
    this.ref.disableClose = true;
    this.ref.backdropClick().pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.cancel());
    this.ref.keydownEvents().pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(e => { if (e.key === 'Escape') this.cancel(); });
  }

  /**
   * File d'attente des aperçus — une seule réponse peut aboutir : la dernière
   * demandée.
   *
   * <p>Sans ce `switchMap`, un fichier lourd (aperçu long) suivi d'un autre
   * fichier faisait arriver la réponse ABANDONNÉE après la bonne, et elle
   * écrasait les champs ; `apercuEnCours` était déjà retombé, donc rien ne
   * signalait la substitution. Même course au changement de type : les champs
   * d'un autre plan restaient affichés. `switchMap` annule aussi la requête
   * HTTP encore en vol — un aperçu abandonné n'a plus à monter au serveur.</p>
   */
  private brancherApercu(): void {
    this.demandesApercu.pipe(
      switchMap(d => {
        if (!d) { this.apercuEnCours.set(false); return of(null); }
        this.apercuEnCours.set(true);
        /* L'erreur est absorbée ICI, dans l'observable interne : la laisser
           remonter terminerait le flux, et plus aucun aperçu ne partirait de
           la fenêtre. Un aperçu indisponible n'empêche pas de déposer. */
        return this.indexation.apercu(d.typeId, d.fichier).pipe(catchError(() => of(null)));
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(a => {
      this.apercuEnCours.set(false);
      if (!a) { this.retirerPropositions(); return; }
      this.apercu.set(a);
      this.champsIndex.set(a.champs);
      this.valeursIndex.set(this.valeursDepuis(a));
      this.analyserNomPropose(a);
      this.majNomAffiche();
    });
  }

  /* ---------- Contrôle du fichier, AVANT l'envoi ----------
     Le serveur refuse déjà les mauvais formats et les fichiers trop lourds,
     mais il ne peut le faire qu'une fois le corps de la requête reçu — et
     au-delà de la limite de Tomcat il coupe la connexion en cours de route.
     Un fichier de 2,6 Go partait donc sur le réseau pour ne produire qu'un
     « Failed to fetch » : le navigateur n'a même pas de réponse à lire. Le
     refus doit tomber ici, immédiatement, en nommant la contrainte. */

  /** Plafond du serveur (`spring.servlet.multipart.max-file-size`). Un type
      qui déclarerait davantage se heurterait quand même à lui. */
  private static readonly PLAFOND_SERVEUR_MO = 100;

  /**
   * Le nom sera composé par la GED : le plan du type porte une charte « Auto ».
   * Le champ est alors désactivé plutôt que masqué — on doit voir QUE le nom
   * existe et d'où il viendra, pas le découvrir après coup.
   */
  protected readonly nomAutomatique = computed(() => !!this.typeChoisi()?.charteAuto);

  /** Motif de refus du fichier retenu, ou `null` s'il est acceptable. */
  protected readonly refusFichier = signal<string | null>(null);

  /* ---------- Champs indexés, dès le choix du fichier ----------
     Le serveur découpe le nom du fichier selon le plan du type et, quand le nom
     ne suffit pas, lit le CONTENU du document — sans le déposer : il est
     recopié dans un temporaire, lu, puis supprimé.

     Ces valeurs sont MODIFIABLES : ce sont des propositions, et l'opérateur a
     le dernier mot. C'est ce qui permet de tout confirmer d'un seul geste. */
  protected readonly champsIndex = signal<ChampApercu[]>([]);
  protected readonly valeursIndex = signal<Record<string, string>>({});
  /** Ce que le serveur a répondu au dernier aperçu — sert aux compteurs. */
  protected readonly apercu = signal<Apercu | null>(null);

  /** Combien de champs restent vides — l'opérateur doit les voir avant de cliquer. */
  protected readonly manquants = computed(() =>
    this.champsIndex().filter(c => !(this.valeursIndex()[c.indexFieldId] ?? '').trim()).length);

  /**
   * Référence telle qu'elle se composerait maintenant, recalculée à chaque
   * frappe. Le serveur la recomposera de son côté à l'enregistrement, avec la
   * même règle : ce n'est qu'un miroir, jamais une source.
   */
  protected readonly reference = computed(() => {
    const a = this.apercu();
    if (!a || !this.champsIndex().length) return null;
    const joint = this.champsIndex()
      .map(c => (this.valeursIndex()[c.indexFieldId] ?? '').trim() || '?')
      .join(a.separateur);
    return joint;
  });

  /**
   * Valeur telle qu'elle s'affiche dans un champ date : l'API porte
   * « AAAA-MM-JJ », le sélecteur veut un objet Date.
   */
  protected valeurDe(champ: ChampApercu): string {
    return this.valeursIndex()[champ.indexFieldId] ?? '';
  }

  /**
   * Index booléen, saisi par une case à cocher (ANO-F-020) : cochée = oui,
   * décochée = non, indéterminée tant que rien n'est choisi (un index
   * obligatoire reste alors à renseigner).
   */
  protected booleenDe(champ: ChampApercu): boolean | null {
    return lireBooleen(this.valeurDe(champ));
  }

  /** L'opérateur corrige une proposition, ou saisit un champ que la GED n'a pas su lire. */
  majValeur(champ: ChampApercu, valeur: string): void {
    this.valeursIndex.update(v => ({ ...v, [champ.indexFieldId]: valeur }));
    // Vidé à la main : c'est un geste aussi délibéré qu'une saisie, la machine
    // n'a pas à le remplir de nouveau derrière l'opérateur.
    this.saisisALaMain.add(champ.indexFieldId);
    this.majNomAffiche();
  }

  /**
   * Gabarit du nom composé : un morceau par jeton de la charte, dans l'ordre.
   *
   * <p>Les jetons SYSTÈME (date, heure…) portent la valeur calculée par le
   * serveur — le navigateur ne saurait pas la reproduire. Les jetons d'INDEX
   * portent {@code null} : ils sont remplacés à chaque frappe par ce que
   * l'opérateur saisit.</p>
   *
   * <p>On ne devine pas la place des jetons système : la charte les met où elle
   * veut — au début pour un plan, à la fin pour un autre. C'est l'ordre déclaré
   * dans {@code charteIds} qui fait foi, et le nom rendu par le serveur qu'on
   * découpe en face. Vide = on ne sait pas recomposer, et on préfère alors
   * n'afficher aucun nom plutôt qu'un faux.</p>
   */
  private gabaritNom: (string | null)[] = [];
  private separateurNom = '_';
  /** La charte force-t-elle les majuscules ? Déduit du nom rendu par le serveur. */
  private nomEnMajuscules = false;
  /** Jetons de la charte du plan retenu : identifiant d'index, ou clé système. */
  private charteIds: string[] = [];

  private analyserNomPropose(a: Apercu): void {
    this.gabaritNom = [];
    this.nomEnMajuscules = false;
    this.separateurNom = a.separateur || '_';

    const nom = a.nomPropose;
    if (!nom || !this.charteIds.length) return;

    const morceaux = nom.split(this.separateurNom);
    /* Un décalage signifie qu'une valeur contenait le séparateur : le
       découpage ne correspond plus aux jetons, et recomposer produirait un nom
       faux. On renonce, c'est le cas rare. */
    if (morceaux.length !== this.charteIds.length) return;

    const idsIndex = new Set(this.champsIndex().map(c => String(c.indexFieldId)));
    this.gabaritNom = this.charteIds.map((jeton, i) => idsIndex.has(jeton) ? null : morceaux[i]);

    // Majuscules : le serveur a rendu une valeur d'index en capitales alors
    // que la proposition ne l'était pas.
    const premier = a.champs.find(c => (c.valeurProposee ?? '').trim());
    if (premier) {
      const brute = premier.valeurProposee!.trim();
      const position = this.charteIds.indexOf(String(premier.indexFieldId));
      if (position >= 0 && morceaux[position] === brute.toUpperCase() && brute !== brute.toUpperCase()) {
        this.nomEnMajuscules = true;
      }
    }
  }

  /**
   * Tient le champ « Nom du document » à jour pendant la saisie.
   *
   * <p>Il montrait auparavant la proposition figée du serveur, donc un nom
   * périmé dès la première correction. L'effacer réglait le mensonge mais en
   * créait un autre : quand la lecture ne reconnaît rien, l'opérateur saisit
   * ses trois champs et ne voit plus AUCUN nom — il dépose à l'aveugle.
   * Ici le préfixe système est conservé et la partie index suit la frappe.</p>
   */
  private majNomAffiche(): void {
    if (!this.nomAutomatique()) return;
    const champ = this.form.get('name')!;

    if (!this.gabaritNom.length) {
      // Impossible de recomposer sans risque d'afficher un faux : mieux vaut
      // le champ vide, l'aide sous le champ dit d'où viendra le nom.
      champ.setValue('', { emitEvent: false });
      return;
    }

    // Les jetons d'index sont consommés dans l'ordre de la charte.
    const restants = this.charteIds
      .filter(j => this.champsIndex().some(c => String(c.indexFieldId) === j));
    let rang = 0;
    const morceaux = this.gabaritNom.map(fixe => {
      if (fixe !== null) return fixe;
      const id = restants[rang++];
      const v = (this.valeursIndex()[id] ?? '').trim() || '?';
      return this.nomEnMajuscules ? v.toUpperCase() : v;
    });

    champ.setValue(morceaux.join(this.separateurNom), { emitEvent: false });
  }

  /**
   * Champs du plan attaché au type, AVANT tout fichier.
   *
   * <p>L'aperçu ne peut rien dire tant qu'il n'a pas de fichier à découper :
   * l'opérateur choisissait donc un type et ne voyait rien, sans savoir si le
   * type portait des index ni lesquels. On va les chercher au référentiel —
   * le type donne son plan, le plan donne ses index dans l'ordre, et le
   * référentiel des index donne leur nature (nombre, date, liste…), sans
   * laquelle on ne saurait pas quel contrôle afficher.</p>
   *
   * <p>Aucun échec n'est bloquant : les champs apparaîtront de toute façon
   * quand le fichier arrivera.</p>
   */
  private chargerChampsDuType(): void {
    const type = this.typeChoisi();
    if (!type?.plan) { this.viderApercu(); return; }

    this.typeService.get(type.id).subscribe({
      next: t => {
        const planId = t.planIndexation?.id;
        if (planId == null) return;
        this.plans.get(planId).subscribe({
          next: p => {
            // Le fichier a pu arriver entre-temps : ses propositions valent
            // mieux que des champs vides, on ne les écrase pas.
            /* La charte sert à recomposer le nom pendant la saisie : elle dit
               où se placent les jetons système, que seul le serveur calcule. */
            this.charteIds = p.charteIds ?? [];
            this.separateurNom = p.separateur || '_';

            /* Les deux appels partent ensemble et rien ne garantit leur ordre.
               Si l'aperçu est déjà revenu, il a été analysé SANS la charte,
               donc sans gabarit — et le nom serait resté vide. On le rejoue
               maintenant que la charte est là. Ses propositions valent mieux
               que des champs vides : on ne les écrase pas. */
            const dejaLa = this.apercu();
            if (dejaLa) {
              this.analyserNomPropose(dejaLa);
              this.majNomAffiche();
              return;
            }
            this.champsIndex.set(p.indices.map(ref => this.champVide(ref.id, ref.label)));
            this.valeursIndex.set(
              Object.fromEntries(p.indices.map(ref => [ref.id, ''])));
          },
          error: () => { /* sans conséquence : voir le commentaire de méthode */ },
        });
      },
      error: () => { /* idem */ },
    });
  }

  /** Nature des index, chargée une fois : le plan ne donne que des libellés. */
  /* Un signal, pas une simple carte : le compte des index obligatoires est un
     `computed` qui la lit. Avec une carte nue, il ne se recalculerait pas à
     l'arrivée du référentiel et le bouton resterait bloqué — ou pire, ouvert. */
  private natures = signal(new Map<string, IndexField>());

  /** Cet index doit-il être renseigné ? Lu au référentiel, pas deviné. */
  protected estObligatoire(champ: ChampApercu): boolean {
    return this.natures().get(champ.indexFieldId)?.obligatoire ?? false;
  }

  /**
   * Index obligatoires encore vides.
   *
   * <p>Ils bloquent le dépôt, et c'est délibéré : le serveur les refuse de
   * toute façon, mais il le fait APRÈS avoir créé le document — qui reste alors
   * en base, sans index, nommé avec des « ? ». Mieux vaut l'empêcher ici que
   * de le rattraper là-bas.</p>
   */
  protected readonly obligatoiresVides = computed(() =>
    this.champsIndex().filter(c =>
      this.estObligatoire(c) && !(this.valeursIndex()[c.indexFieldId] ?? '').trim()));

  /** Un champ sans proposition — la forme attendue par le gabarit. */
  private champVide(id: string, libelle: string): ChampApercu {
    const nature = this.natures().get(id);
    return {
      indexFieldId: id,
      code: nature?.code ?? '',
      libelle,
      fieldType: nature?.fieldType ?? 'TEXTE',
      options: (nature?.valeurs ?? '').split(',').map(o => o.trim()).filter(Boolean),
      valeurProposee: null,
      source: null,
      valeurActuelle: null,
      reconnue: false,
      motif: null,
    };
  }

  private viderApercu(): void {
    this.champsIndex.set([]);
    this.valeursIndex.set({});
    this.apercu.set(null);
    // Le gabarit appartenait à l'aperçu qu'on jette : le garder ferait
    // recomposer un nom sur l'horodatage d'un fichier précédent.
    this.gabaritNom = [];
    this.nomEnMajuscules = false;
    if (this.nomAutomatique()) this.form.get('name')!.setValue('', { emitEvent: false });
  }

  /**
   * Recharge l'aperçu. Appelé au choix du fichier ET au changement de type :
   * le découpage dépend du plan, donc du type.
   *
   * <p>Cet appel LIT, il n'écrit rien : le document n'existe pas encore. C'est
   * ce qui rend la page unique possible — on peut montrer les index déduits et
   * le nom composé avant le moindre dépôt.</p>
   */
  private rafraichirApercu(): void {
    const fichier = this.selectedFile();
    const type = this.typeChoisi();

    /* Un type SANS plan n'a aucun index à déduire : partir quand même ferait
       monter le fichier entier au serveur pour s'entendre répondre « aucun
       plan ». Sur un type qui plafonne à 20 Mo, c'est 20 Mo transférés pour
       rien, à chaque sélection de fichier. */
    if (!fichier || !type || !type.plan || this.refusFichier()) {
      this.demandesApercu.next(null);   // annule l'aperçu encore en vol
      this.viderApercu();
      return;
    }
    this.demandesApercu.next({ typeId: type.id, fichier });
  }

  /**
   * Retire ce que la GED avait proposé et GARDE ce que l'opérateur a saisi.
   *
   * <p>C'est la règle de la fenêtre : on ne détruit jamais une frappe. Une
   * version précédente vidait tout au basculement de l'interrupteur, dans les
   * deux sens — l'opérateur perdait son travail pour avoir touché un réglage.</p>
   */
  private retirerPropositions(): void {
    this.apercu.set(null);
    this.gabaritNom = [];
    this.valeursIndex.update(valeurs => {
      const gardees = { ...valeurs };
      for (const c of this.champsIndex()) {
        if (!this.saisisALaMain.has(c.indexFieldId)) gardees[c.indexFieldId] = '';
      }
      return gardees;
    });
    this.majNomAffiche();
  }

  /**
   * Valeurs de départ des champs.
   *
   * <p>Interrupteur coupé : tout est vide, l'opérateur saisit. C'est le sens
   * même du réglage — une proposition affichée alors qu'on a demandé la saisie
   * manuelle serait un contresens.</p>
   */
  private valeursDepuis(a: Apercu): Record<string, string> {
    const precedentes = this.valeursIndex();
    const valeurs: Record<string, string> = {};
    const aujourdhui = versIso(new Date()) ?? '';
    for (const c of a.champs) {
      /* Une saisie de l'opérateur n'est JAMAIS écrasée par une proposition :
         il a corrigé en connaissance de cause, la machine n'a pas à revenir
         dessus. Elle ne remplit que ce qu'il n'a pas touché. */
      if (this.saisisALaMain.has(c.indexFieldId)) {
        valeurs[c.indexFieldId] = precedentes[c.indexFieldId] ?? '';
        continue;
      }
      if (!this.indexationAuto()) { valeurs[c.indexFieldId] = ''; continue; }
      /* Une date que le nom du fichier ne donne pas prend la date du jour :
         c'est la valeur juste dans la quasi-totalité des dépôts, et elle reste
         corrigeable ici même, avant tout enregistrement. */
      valeurs[c.indexFieldId] = c.valeurProposee ?? (c.fieldType === 'DATE' ? aujourdhui : '');
    }
    return valeurs;
  }

  /**
   * L'opérateur active ou coupe l'indexation automatique.
   *
   * <p>En l'activant on redemande l'aperçu ; en la coupant on retire les
   * propositions de la GED — et <b>elles seules</b>. Ce que l'opérateur a tapé
   * survit dans les deux sens : toucher un réglage ne doit jamais coûter son
   * travail. Rien n'est envoyé au serveur quand on coupe : la lecture qu'on
   * refuse n'a pas à être demandée.</p>
   */
  basculerAuto(actif: boolean): void {
    this.indexationAuto.set(actif);
    if (actif) { this.rafraichirApercu(); return; }
    this.demandesApercu.next(null);   // une lecture en vol n'a plus lieu d'être
    this.retirerPropositions();
  }

  onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const fichier = input.files && input.files.length ? input.files[0] : null;
    this.selectedFile.set(fichier);
    this.refusFichier.set(fichier ? this.controler(fichier) : null);
    this.serverError.set(null);
    this.rafraichirApercu();
  }

  /** Limite applicable : celle du type, jamais au-delà de celle du serveur. */
  private plafondMo(): number {
    const duType = this.typeChoisi()?.tailleMaxMo;
    return duType && duType > 0
      ? Math.min(duType, DocumentUpload.PLAFOND_SERVEUR_MO)
      : DocumentUpload.PLAFOND_SERVEUR_MO;
  }

  private controler(fichier: File): string | null {
    const type = this.typeChoisi();

    const extension = (fichier.name.split('.').pop() ?? '').toLowerCase();
    const formats = type?.formats ?? [];
    if (formats.length && !formats.includes(extension)) {
      return `Le type « ${type?.name} » n'accepte que les fichiers `
           + `${formats.map(f => '.' + f).join(', ')} — celui-ci est un .${extension}.`;
    }

    const plafond = this.plafondMo();
    const tailleMo = fichier.size / (1024 * 1024);
    if (tailleMo > plafond) {
      return `Le fichier pèse ${this.lisible(fichier.size)} : la limite est de ${plafond} Mo.`;
    }
    return null;
  }

  /** Taille en unité lisible — « 2,5 Go » plutôt que « 2662711631 octets ». */
  private lisible(octets: number): string {
    const mo = octets / (1024 * 1024);
    return mo >= 1024
      ? `${(mo / 1024).toFixed(1).replace('.', ',')} Go`
      : `${mo.toFixed(mo < 10 ? 1 : 0).replace('.', ',')} Mo`;
  }

  /**
   * Le seul geste qui écrit. Dépose le fichier, puis enregistre les index dans
   * la foulée — l'opérateur n'a rien à confirmer une seconde fois.
   */
  submit(): void {
    /* Garde d'idempotence. `[disabled]` sur le bouton ne suffit pas : deux
       clics émis dans la MÊME tâche (double-clic tactile, clic + Entrée)
       partent avant qu'Angular n'ait rendu l'état désactivé — et créent deux
       documents identiques. Une ligne ici ferme la fenêtre définitivement. */
    if (this.loading()) return;

    this.serverError.set(null);

    // Le document a déjà été créé lors d'une tentative précédente : le
    // redéposer ferait un doublon. On termine sur ce qui existe.
    const dejaDepose = this.documentDepose();
    if (dejaDepose != null) { this.enregistrerIndex(dejaDepose); return; }

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const file = this.selectedFile();
    if (!file) {
      this.serverError.set('Veuillez choisir un fichier.');
      return;
    }
    // Dernier barrage : le fichier a pu être retenu avant un changement de type.
    const refus = this.controler(file);
    if (refus) {
      this.refusFichier.set(refus);
      return;
    }

    const v = this.form.getRawValue();
    this.loading.set(true);
    /* Dépôt et métadonnées en une seule requête (§5.3) : le serveur valide les
       index contre le plan avant d'écrire quoi que ce soit, puis les enregistre
       dans un second temps (§12.11). Seules les valeurs saisies partent. */
    const metadonnees: Record<string, string> = {};
    for (const c of this.champsIndex()) {
      const valeur = (this.valeursIndex()[c.indexFieldId] ?? '').trim();
      if (valeur) metadonnees[c.indexFieldId] = valeur;
    }
    this.service.upload(file, v.typeDocumentId, (v.name || '').trim(), versIso(v.expirationDate),
                        v.etiquetteIds ?? [], v.confidentialite ?? null, metadonnees,
                        { objet: v.objet ?? null, dateDocument: versIso(v.dateDocument) })
      .subscribe({
        next: doc => {
          this.documentDepose.set(doc.id);
          this.figerContexte();
          if (doc.statutIndexation === 'INDEXE') { this.loading.set(false); this.terminer('indexe'); return; }
          if (doc.statutIndexation === 'SANS_PLAN') { this.loading.set(false); this.terminer('sans-plan'); return; }
          if (doc.motifIndexation) {
            /* Temps 2 en échec : le document est reçu, son fichier conservé ;
               l'enregistrement des index se reprend d'ici (« Réessayer »). */
            this.loading.set(false);
            this.serverError.set("Le document est déposé, mais ses index n'ont pas été enregistrés : "
              + doc.motifIndexation + " Réessayez, ou fermez et reprenez-les depuis la fiche du document.");
            return;
          }
          this.enregistrerIndex(doc.id);
        },
        error: err => {
          this.loading.set(false);
          /* `status: 0` = la requête n'a pas abouti : connexion coupée en cours
             d'envoi, serveur injoignable. « Failed to fetch », le message brut
             du navigateur, n'apprend rien à un opérateur. */
          this.serverError.set(
            err?.status === 0
              ? "L'envoi a été interrompu. Vérifiez votre connexion, puis réessayez."
              : err?.error?.message ?? 'Le téléversement a échoué.');
        },
      });
  }

  /**
   * Écrit les index du document qui vient d'être déposé.
   *
   * <p>C'est cet appel qui déclenche, côté serveur, la composition du nom
   * depuis la charte du plan. Sans champ à poser — un type sans plan — il n'y a
   * rien à écrire et le dépôt est déjà complet.</p>
   */
  private enregistrerIndex(documentId: string): void {
    const champs = this.champsIndex();
    if (!champs.length) { this.terminer('sans-plan'); return; }

    const valeurs = champs.map(c => ({
      indexFieldId: c.indexFieldId,
      valeur: (this.valeursIndex()[c.indexFieldId] ?? '').trim() || null,
    }));

    this.loading.set(true);
    this.indexation.enregistrer(documentId, valeurs).subscribe({
      next: () => { this.loading.set(false); this.terminer('indexe'); },
      error: err => {
        this.loading.set(false);
        /* Le fichier est déposé : le supprimer serait pire que le laisser à
           indexer. On le dit franchement, et on laisse deux issues — réessayer,
           ou fermer et reprendre depuis la fiche du document. */
        this.serverError.set((err?.error?.message
          ?? "Le document est déposé, mais l'enregistrement des index a été refusé.")
          + " Réessayez, ou fermez et reprenez-les depuis la fiche du document.");
      },
    });
  }

  /** Le document existe déjà : « Annuler » ne peut plus l'effacer, on le dit. */
  protected readonly depotEffectue = computed(() => this.documentDepose() != null);

  /**
   * Le document est créé : son type et son fichier ne peuvent plus changer.
   *
   * <p>Sans ce gel, un dépôt dont l'indexation a échoué laissait tout modifiable :
   * on changeait de type, les champs devenaient ceux d'un AUTRE plan, et
   * « Réessayer » écrivait ces index-là sur un document qui n'en relevait pas —
   * ses propres index obligatoires n'ayant jamais été vérifiés. Le serveur
   * l'acceptait. C'est une atteinte à l'intégrité, pas une gêne d'affichage.</p>
   */
  private figerContexte(): void {
    this.form.get('typeDocumentId')!.disable({ emitEvent: false });
    /* Objet et date sont partis avec le dépôt : « Réessayer » ne renvoie que
       les index. Les laisser modifiables ferait croire à une correction qui
       n'aurait jamais lieu — elle se fait depuis la fiche. */
    this.form.get('objet')!.disable({ emitEvent: false });
    this.form.get('dateDocument')!.disable({ emitEvent: false });
  }

  /**
   * Le document est déposé : la liste doit être rafraîchie dans tous les cas.
   * L'issue remonte à l'appelant, qui affiche un seul message — le dialogue n'en
   * émet aucun, sans quoi deux notifications se superposeraient.
   */
  private terminer(issue: IssueDepot): void {
    this.ref.close(issue);
  }

  /**
   * Ferme la fenêtre. Si le fichier a déjà été déposé, l'issue le dit à l'écran
   * appelant : il reste à indexer, et la liste doit quand même se rafraîchir.
   */
  cancel(): void {
    this.ref.close(this.documentDepose() != null ? 'a-indexer' : false);
  }
}
