import { AuthService } from '../../../core/auth.service';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
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
import { DocumentItem, Version, Confidentialite, NIVEAUX_CONFIDENTIALITE } from '../document.model';
import { EtiquetteService } from '../../etiquette/etiquette.service';
import { Etiquette } from '../../etiquette/etiquette.model';
import { TypeDocumentService } from '../../type-document/type-document.service';
import { SelectOption } from '../../type-document/type-document.model';
import { IndexationService } from '../../indexation/indexation.service';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { formaterDate, versDate, versIso } from '../../../core/dates';

/** Une valeur d'index déjà enregistrée pour ce document. */
interface ValeurIndex {
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

  protected auth = inject(AuthService);
  id = signal<string | null>(null);
  doc = signal<DocumentItem | null>(null);
  etiquettes = signal<Etiquette[]>([]);
  types = signal<SelectOption[]>([]);
  valeurs = signal<ValeurIndex[]>([]);
  chargement = signal(true);
  introuvable = signal(false);
  enregistrement = signal(false);

  /** Fichier choisi pour une nouvelle version, avant envoi. */
  nouveauFichier = signal<File | null>(null);

  readonly colonnesVersions = ['fichier', 'observation', 'taille', 'date', 'actions'];

  form: FormGroup = this.fb.group({
    name: [''],
    typeDocumentId: [null as string | null],
    expirationDate: [null as Date | null],
    etiquetteIds: [[] as string[]],
    active: [true],
    observation: [''],
    confidentialite: ['PUBLIC' as Confidentialite],
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
        });
        this.appliquerVerrou(!this.modifiable);
        this.chargement.set(false);
      },
      error: () => { this.chargement.set(false); this.introuvable.set(true); },
    });
    // Les valeurs d'index viennent du module d'indexation : la fiche les affiche
    // en lecture, les corriger reste du ressort de l'écran de dépôt.
    this.indexation.valeurs(id).subscribe({
      next: v => this.valeurs.set(v.map(x => ({ label: x.libelle, valeur: x.valeur }))),
      error: () => this.valeurs.set([]),
    });
  }

  get verrouille(): boolean { return this.doc()?.verrouille === true; }

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
    const v = this.form.value;
    this.enregistrement.set(true);
    this.service.update(id, {
      name: v.name, typeDocumentId: v.typeDocumentId ?? undefined,
      expirationDate: versIso(v.expirationDate),
      active: v.active, etiquetteIds: v.etiquetteIds ?? [],
      confidentialite: v.confidentialite ?? undefined,
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

  /** Date lisible ; mutualisée pour que tous les écrans lisent pareil. */
  readonly dateCourte = formaterDate;

  retour(): void {
    this.router.navigate(['/televerser']);
  }
}
