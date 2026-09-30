import { AuthService } from '../../../core/auth.service';
import { Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { DocumentService, messageErreurTelechargement } from '../document.service';
import { CanalDepot, DocumentItem, LIBELLES_CANAL, Version, Confidentialite, NIVEAUX_CONFIDENTIALITE } from '../document.model';
import { EtiquetteService } from '../../etiquette/etiquette.service';
import { Etiquette } from '../../etiquette/etiquette.model';
import { TypeDocumentService } from '../../type-document/type-document.service';
import { SelectOption } from '../../type-document/type-document.model';
import { IndexationService } from '../../indexation/indexation.service';
import { Critere } from '../../indexation/indexation.model';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { formaterDate, versDate, versIso } from '../../../core/dates';
import { CircuitDocument } from '../../workflow/circuit-document/circuit-document';
import { CycleDeVieService } from '../../cycle-de-vie/cycle-de-vie.service';
import { Conservation } from '../../cycle-de-vie/cycle-de-vie.model';
import { EmployeService } from '../../../core/employe.service';
import { DocumentDesignes } from '../document-designes/document-designes';
import { DocumentEmplacements } from '../document-emplacements/document-emplacements';

/** Une valeur d'index déjà enregistrée pour ce document. */
interface ValeurIndex {
  indexFieldId: string;
  label: string;
  valeur: string;
}

/**
 * Fiche d'un document — reprend la page « Single document » de l'application
 * d'origine : métadonnées modifiables, étiquettes, valeurs d'index et versions.
 *
 * <p>Le parcours de dépôt et son indexation assistée ne sont pas touchés : cet
 * écran ne fait que relire et corriger ce qui a déjà été déposé.
 */
@Component({
  selector: 'app-document-detail',
  imports: [
    RouterLink, ReactiveFormsModule, MatButtonModule, MatIconModule, MatTooltipModule,
    MatFormFieldModule, MatInputModule, MatDatepickerModule, MatSelectModule, MatSlideToggleModule, MatTableModule,
    CircuitDocument, DocumentDesignes, DocumentEmplacements,
  ],
  templateUrl: './document-detail.html',
  styleUrl: './document-detail.scss',
})
export class DocumentDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private fb = inject(FormBuilder);
  private service = inject(DocumentService);
  private etiquetteService = inject(EtiquetteService);
  private typeService = inject(TypeDocumentService);
  private indexation = inject(IndexationService);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private cycleDeVie = inject(CycleDeVieService);
  private employes = inject(EmployeService);

  /** Nom de chaque personne par identité GED : auteur d'une version (ANO-F-012). */
  auteurs = signal<Map<string, string>>(new Map());

  /** Copie de conservation PDF/A et statut d'archivage (§12.6). */
  conservation = signal<Conservation | null>(null);
  archivageEnCours = signal(false);

  protected auth = inject(AuthService);

  /** Carte du circuit : rechargée avec la fiche (un versement rend les décisions caduques). */
  @ViewChild(CircuitDocument) circuit?: CircuitDocument;
  id = signal<string | null>(null);
  doc = signal<DocumentItem | null>(null);
  etiquettes = signal<Etiquette[]>([]);
  types = signal<SelectOption[]>([]);
  valeurs = signal<ValeurIndex[]>([]);

  /* ---------- Saisie des index (ANO-F-005) ----------
     Un document déposé « à indexer » (§4.1.7 : fichier conservé, métadonnées
     non enregistrées) ne se reprenait que par l'API : la fiche montrait ses
     index en lecture seule. Elle porte désormais la saisie — mêmes champs que
     le plan du type, même enregistrement (PUT /indexation/documents/{id}), qui
     fait passer l'issue à « indexé ». */
  /** Champs du plan d'indexation du type (vide : type sans plan). */
  champs = signal<Critere[]>([]);
  /** Saisies en cours, par index ; absentes = valeur enregistrée. */
  saisieIndex = signal<Record<string, string>>({});
  editionIndex = signal(false);
  enregistrementIndex = signal(false);
  erreurIndex = signal<string | null>(null);

  /** Issue « à indexer » : métadonnées à saisir ou à reprendre (§12.11). */
  readonly aIndexer = computed(() => this.doc()?.statutIndexation === 'A_INDEXER');

  /** Index obligatoires encore vides dans la saisie : l'envoi serait refusé. */
  readonly indexObligatoiresVides = computed(() =>
    this.champs().filter(c => c.obligatoire && !this.valeurSaisie(c).trim()));

  chargement = signal(true);
  introuvable = signal(false);
  enregistrement = signal(false);

  /** Fichier choisi pour une nouvelle version, avant envoi. */
  nouveauFichier = signal<File | null>(null);

  readonly colonnesVersions = ['fichier', 'observation', 'taille', 'date', 'auteur', 'actions'];

  /** Auteur du versement (ANO-F-012) : l'API donne son identité GED, l'écran son nom. */
  nomAuteur(v: Version): string {
    return v.auteurId ? this.auteurs().get(v.auteurId) ?? '—' : '—';
  }

  form: FormGroup = this.fb.group({
    name: [''],
    typeDocumentId: [null as string | null],
    expirationDate: [null as Date | null],
    etiquetteIds: [[] as string[]],
    active: [true],
    observation: [''],
    confidentialite: ['PUBLIC' as Confidentialite],
    /* Socle commun (§4.2.3 ; ANO-F-006) : corrigeables ici, comme le reste de
       la fiche — l'API les acceptait, l'écran les montrait en lecture seule. */
    objet: ['', Validators.maxLength(1000)],
    dateDocument: [null as Date | null],
  });

  readonly niveaux = NIVEAUX_CONFIDENTIALITE;

  /** L'appelant détient-il la permission sur ce document ? (confort d'affichage) */
  peut(permission: string): boolean {
    return this.doc()?.permissions?.includes(permission) ?? false;
  }

  /** Fiche modifiable : ni verrouillée, ni hors de la permission Modifier. */
  get modifiable(): boolean { return !this.verrouille && this.peut('MODIFIER'); }

  ngOnInit(): void {
    // Toutes les étiquettes actives : elles se comptent en dizaines, une page
    // large évite un second appel pour les rares dépassements.
    this.etiquetteService.list(0, 200, '').subscribe(r => this.etiquettes.set(r.content));
    this.typeService.forSelect().subscribe(l => this.types.set(l));
    this.employes.personnes().subscribe(p => this.auteurs.set(new Map(p.map(x => [x.utilisateurId, x.nom]))));

    this.route.paramMap.subscribe(p => {
      const id = p.get('id');
      if (!id) {
        this.introuvable.set(true);
        this.chargement.set(false);
        return;
      }
      this.id.set(id);
      this.charger();
    });
  }

  charger(): void {
    const id = this.id();
    if (id == null) return;
    this.chargement.set(true);
    this.introuvable.set(false);
    this.circuit?.charger();
    this.service.get(id).subscribe({
      next: d => {
        this.doc.set(d);
        this.form.patchValue({
          name: d.name,
          typeDocumentId: d.typeDocument?.id ?? null,
          expirationDate: versDate(d.expirationDate),
          etiquetteIds: (d.etiquettes ?? []).map(e => e.id),
          active: d.active,
          confidentialite: d.confidentialite ?? 'PUBLIC',
          objet: d.objet ?? '',
          dateDocument: versDate(d.dateDocument ?? null),
        });
        this.appliquerVerrou(!this.modifiable);
        this.chargement.set(false);
        this.chargerConservation(d);
      },
      error: () => { this.chargement.set(false); this.introuvable.set(true); },
    });
    // Les valeurs d'index viennent du module d'indexation : la fiche les affiche
    // en lecture, les corriger reste du ressort de l'écran de dépôt.
    this.chargerIndex(id);
  }

  private chargerIndex(id: string): void {
    this.indexation.valeurs(id).subscribe({
      next: v => this.valeurs.set(v.map(x => ({ indexFieldId: x.indexFieldId, label: x.libelle, valeur: x.valeur }))),
      error: () => this.valeurs.set([]),
    });
    this.indexation.champs(id).subscribe({
      next: c => this.champs.set(c),
      error: () => this.champs.set([]),
    });
  }

  /**
   * Le formulaire des index est-il ouvert ? Toujours pour un document « à
   * indexer » que l'on peut modifier : c'est précisément ce qui reste à faire.
   */
  saisieIndexOuverte(): boolean {
    return this.modifiable && this.champs().length > 0 && (this.editionIndex() || this.aIndexer());
  }

  ouvrirSaisieIndex(): void {
    this.saisieIndex.set({});
    this.erreurIndex.set(null);
    this.editionIndex.set(true);
  }

  annulerSaisieIndex(): void {
    this.saisieIndex.set({});
    this.erreurIndex.set(null);
    this.editionIndex.set(false);
  }

  /** Valeur affichée dans le champ : la saisie en cours, sinon l'enregistrée. */
  valeurSaisie(c: Critere): string {
    const saisie = this.saisieIndex()[c.id];
    if (saisie !== undefined) return saisie;
    return this.valeurs().find(v => v.indexFieldId === c.id)?.valeur ?? '';
  }

  majIndex(c: Critere, valeur: string | null): void {
    this.saisieIndex.update(s => ({ ...s, [c.id]: valeur ?? '' }));
  }

  /** Date d'un index : le sélecteur manipule des dates, l'API des chaînes. */
  readonly versDate = versDate;
  readonly versIso = versIso;

  /**
   * Enregistre TOUS les champs du plan : un index absent du corps compterait
   * comme non renseigné au contrôle des obligatoires. Le serveur valide la
   * nature de chaque valeur, recompose la référence et passe l'issue à
   * « indexé » ; la fiche est rechargée pour le montrer (nom compris).
   */
  enregistrerIndex(): void {
    const id = this.id();
    if (id == null || this.enregistrementIndex() || this.indexObligatoiresVides().length) return;
    const valeurs = this.champs().map(c => ({ indexFieldId: c.id, valeur: this.valeurSaisie(c).trim() || null }));
    this.enregistrementIndex.set(true);
    this.erreurIndex.set(null);
    this.indexation.enregistrer(id, valeurs).subscribe({
      next: v => {
        this.enregistrementIndex.set(false);
        this.valeurs.set(v.map(x => ({ indexFieldId: x.indexFieldId, label: x.libelle, valeur: x.valeur })));
        this.saisieIndex.set({});
        this.editionIndex.set(false);
        this.notify.success('Index enregistrés.');
        this.charger();
      },
      error: err => {
        this.enregistrementIndex.set(false);
        this.erreurIndex.set(err?.error?.message ?? "Enregistrement des index impossible.");
      },
    });
  }

  /**
   * Fiche figée : verrou posé, ou document archivé (§12.6 : lecture seule
   * totale ; seul le désarchivage y fait exception).
   */
  get verrouille(): boolean { return this.doc()?.verrouille === true || this.archive; }

  get archive(): boolean { return this.doc()?.statutConservation === 'ARCHIVE'; }

  private chargerConservation(d: DocumentItem): void {
    if (d.statutConservation !== 'ARCHIVE') { this.conservation.set(null); return; }
    this.cycleDeVie.conservation(d.id).subscribe({
      next: c => this.conservation.set(c),
      error: () => this.conservation.set(null),
    });
  }

  /** Archivage manuel (D10) : empreinte revérifiée, copie PDF/A-2, lecture seule. */
  archiver(): void {
    const d = this.doc();
    if (!d || this.archivageEnCours()) return;
    this.confirm.ask({
      title: 'Archiver ce document ?',
      message: "Le document passera en lecture seule pour tous. Une copie de conservation PDF/A sera produite ; l'original est conservé.",
      confirmLabel: 'Archiver',
    }).subscribe(ok => {
      if (!ok) return;
      this.archivageEnCours.set(true);
      this.cycleDeVie.archiver(d.id).subscribe({
        next: r => {
          this.archivageEnCours.set(false);
          if (r.issue === 'ANOMALIE') {
            this.notify.error("Document archivé avec son seul original : la copie PDF/A n'a pas pu être produite (signalée pour reprise).");
          } else {
            this.notify.success('Document archivé.');
          }
          this.charger();
        },
        error: err => {
          this.archivageEnCours.set(false);
          this.notify.error(err?.error?.message ?? 'Archivage impossible.');
        },
      });
    });
  }

  desarchiver(): void {
    const d = this.doc();
    if (!d) return;
    this.confirm.ask({
      title: 'Désarchiver ce document ?',
      message: 'Il redeviendra modifiable. Opération réservée et tracée.',
      confirmLabel: 'Désarchiver',
    }).subscribe(ok => {
      if (!ok) return;
      this.cycleDeVie.desarchiver(d.id).subscribe({
        next: () => { this.notify.success('Document désarchivé.'); this.charger(); },
        error: err => this.notify.error(err?.error?.message ?? 'Désarchivage impossible.'),
      });
    });
  }

  /** Document archivé : l'original déposé, plutôt que la copie PDF/A servie par défaut. */
  telechargerOriginal(): void {
    const d = this.doc();
    if (!d || this.telechargementEnCours()) return;
    this.telechargementEnCours.set(true);
    this.service.telecharger(d.id, true).subscribe({
      next: blob => {
        this.telechargementEnCours.set(false);
        this.cycleDeVie.enregistrer(blob, d.fileName || d.name);
      },
      error: (e: HttpErrorResponse) => {
        this.telechargementEnCours.set(false);
        const msg = messageErreurTelechargement(e);
        if (msg) this.notify.error(msg);
      },
    });
  }

  /**
   * Un document verrouillé fige tout le formulaire. On passe par
   * `enable()/disable()` plutôt que par une liaison `[disabled]` : Angular
   * refuse la seconde sur un formulaire réactif et la contourne au prix d'un
   * avertissement à chaque rendu.
   */
  private appliquerVerrou(verrouille: boolean): void {
    if (verrouille) this.form.disable({ emitEvent: false });
    else this.form.enable({ emitEvent: false });
  }

  enregistrer(): void {
    const id = this.id();
    if (id == null) return;
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.value;
    this.enregistrement.set(true);
    this.service.update(id, {
      name: v.name, typeDocumentId: v.typeDocumentId ?? undefined,
      expirationDate: versIso(v.expirationDate),
      active: v.active, etiquetteIds: v.etiquetteIds ?? [],
      confidentialite: v.confidentialite ?? undefined,
      /* Objet vide = effacé (le serveur le ramène à null). La date du document
         ne s'efface pas : absente, elle n'est simplement pas modifiée. */
      objet: (v.objet ?? '').trim(),
      dateDocument: versIso(v.dateDocument) ?? undefined,
    }).subscribe({
      next: d => {
        this.doc.set(d);
        this.enregistrement.set(false);
        this.notify.success('Document mis à jour.');
      },
      error: err => {
        this.enregistrement.set(false);
        this.notify.error(err?.error?.message ?? 'Mise à jour impossible.');
      },
    });
  }

  basculerVerrou(): void {
    const id = this.id();
    const d = this.doc();
    if (id == null || !d) return;
    // Le motif du verrou est conservé et affiché (§12.8).
    const motif = d.verrouille ? null : window.prompt('Motif du verrou :', '');
    if (!d.verrouille && motif === null) return;
    this.service.verrou(id, !d.verrouille, motif).subscribe({
      next: maj => {
        this.doc.set(maj);
        this.appliquerVerrou(!this.modifiable);
        this.notify.success(maj.verrouille ? 'Document verrouillé.' : 'Document déverrouillé.');
      },
      error: () => this.notify.error('Opération impossible.'),
    });
  }

  fichierChoisi(e: Event): void {
    const input = e.target as HTMLInputElement;
    this.nouveauFichier.set(input.files?.[0] ?? null);
  }

  deposerVersion(): void {
    const id = this.id();
    const f = this.nouveauFichier();
    if (id == null || !f) return;
    this.enregistrement.set(true);
    this.service.ajouterVersion(id, f, this.form.value.observation ?? '').subscribe({
      next: d => {
        this.doc.set(d);
        this.nouveauFichier.set(null);
        this.form.patchValue({ observation: '' });
        this.enregistrement.set(false);
        this.notify.success('Nouvelle version déposée.');
      },
      error: err => {
        this.enregistrement.set(false);
        this.notify.error(err?.error?.message ?? 'Dépôt impossible.');
      },
    });
  }

  restaurerVersion(v: Version): void {
    const id = this.id();
    if (id == null || v.principale) return;
    this.confirm.ask({
      title: 'Rendre cette version courante',
      message: `« ${v.fileName} » deviendra le fichier servi au téléchargement.`,
      confirmLabel: 'Restaurer',
    }).subscribe(ok => {
      if (!ok) return;
      this.service.restaurerVersion(id, v.id).subscribe({
        next: d => { this.doc.set(d); this.notify.success('Version restaurée.'); },
        error: () => this.notify.error('Restauration impossible.'),
      });
    });
  }

  readonly telechargementEnCours = signal(false);

  /**
   * Télécharge par `HttpClient` (et non plus `window.open`) : seule cette voie
   * traverse les intercepteurs, donc seule elle porte le jeton — un onglet
   * ouvert sur l'URL brute recevait un 401 et restait blanc.
   */
  telecharger(): void {
    const id = this.id();
    if (id == null || this.telechargementEnCours()) return;
    // La fiche n'est pas forcément chargée : `NomFichierSource` accepte le seul
    // identifiant, et le service saura toujours proposer un nom.
    const doc = this.doc() ?? { id };
    this.telechargementEnCours.set(true);
    this.service.telechargerEtEnregistrer(doc).subscribe({
      next: () => this.telechargementEnCours.set(false),
      error: (e: HttpErrorResponse) => {
        this.telechargementEnCours.set(false);
        const msg = messageErreurTelechargement(e);
        if (msg) this.notify.error(msg);
      },
    });
  }

  /**
   * Aperçu d'une version dans un nouvel onglet (§6.1.6). Le blob déchiffré
   * est servi par une URL d'objet locale, révoquée après une minute : le temps
   * que le visionneur du navigateur l'ait chargée, sans la garder en mémoire.
   * Un format sans aperçu (415) ou une conversion indisponible (503) renvoie
   * au téléchargement.
   */
  apercu(v: Version): void {
    const onglet = window.open('', '_blank');
    this.service.apercu(v.id).subscribe({
      next: blob => {
        const href = URL.createObjectURL(blob);
        if (onglet) { onglet.location.href = href; } else { window.open(href, '_blank'); }
        setTimeout(() => URL.revokeObjectURL(href), 60_000);
      },
      error: (e: HttpErrorResponse) => {
        onglet?.close();
        this.notify.error(e.status === 415 || e.status === 503
          ? 'Aperçu indisponible pour ce format : téléchargez le document.'
          : 'Aperçu impossible.');
      },
    });
  }

  /** Libellé de l'état OCR de la version courante, pour le bandeau. */
  libelleOcr(statut: string | null | undefined): string | null {
    switch (statut) {
      case 'EN_ATTENTE_OCR':
      case 'EN_COURS_OCR':
        return "Contenu en cours d'indexation : il sera interrogeable en recherche plein texte après son traitement.";
      case 'OCR_ECHEC':
        return 'Contenu non interrogeable : le traitement OCR a échoué. Le document reste consultable et téléchargeable.';
      default:
        return null;
    }
  }

  /** Date lisible ; mutualisée pour que tous les écrans lisent pareil. */
  readonly dateCourte = formaterDate;

  libelleCanal(c: CanalDepot): string {
    return LIBELLES_CANAL[c] ?? c;
  }

  retour(): void {
    this.router.navigate(['/televerser']);
  }
}
