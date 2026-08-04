import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { DocumentService } from '../document.service';
import { IndexationService } from '../../indexation/indexation.service';
import { OcrService } from '../../indexation/ocr.service';
import { TypeDocumentService } from '../../type-document/type-document.service';
import { SelectOption } from '../../type-document/type-document.model';
import { Analyse, Critere } from '../../indexation/indexation.model';

/**
 * Comment le dépôt s'est terminé — l'écran appelant en tire le message affiché.
 * `false` (fermeture sans dépôt) reste la valeur d'annulation.
 */
export type IssueDepot = 'indexe' | 'sans-plan' | 'a-indexer';

/** Ligne d'index proposée par la GED, et la valeur que l'opérateur retient. */
interface Ligne {
  indexFieldId: number;
  libelle: string;
  fieldType: string;
  options: string[];
  proposee: string | null;
  source: 'NOM_FICHIER' | 'CONTENU' | null;
  motif: string | null;
  reconnue: boolean;
  valeur: string;
}

/**
 * Dépôt d'un document — l'indexation en fait partie, elle n'est pas une étape
 * séparée à faire plus tard : téléverser, puis la GED lit le document (couche
 * texte ou OCR) et propose les valeurs d'index, que l'opérateur confirme avant
 * que le dépôt soit considéré comme terminé.
 */
@Component({
  selector: 'app-document-upload',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule, MatTooltipModule, MatSlideToggleModule,
  ],
  templateUrl: './document-upload.html',
  styleUrl: './document-upload.scss',
})
export class DocumentUpload implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(DocumentService);
  private typeService = inject(TypeDocumentService);
  private ocr = inject(OcrService);
  private indexation = inject(IndexationService);
  private ref = inject(MatDialogRef<DocumentUpload, IssueDepot | false>);

  /** 'depot' = choix du fichier · 'indexation' = relecture des propositions. */
  etape = signal<'depot' | 'indexation'>('depot');

  /** Types disponibles, avec leur plan — nécessaire pour prévenir quand il manque. */
  types = signal<SelectOption[]>([]);
  /** Type actuellement sélectionné dans la liste, ou null. */
  typeChoisi = signal<SelectOption | null>(null);
  selectedFile = signal<File | null>(null);
  loading = signal(false);
  serverError = signal<string | null>(null);

  /** Document créé par le téléversement — il existe déjà côté serveur. */
  documentId = signal<number | null>(null);
  documentNom = signal('');
  analyse = signal<Analyse | null>(null);
  lignes = signal<Ligne[]>([]);
  analyseEnCours = signal(false);

  /**
   * L'indexation automatique est un choix, pas un passage obligé : cet
   * interrupteur décide si la GED lit le document pour remplir les index, ou si
   * l'opérateur les saisit lui-même. Il est réglé sur la disponibilité réelle du
   * moteur dès qu'un type est choisi.
   */
  indexationAuto = signal(true);
  /** Le moteur est-il utilisable ici ? (coupé globalement, ou pour ce type) */
  lectureDisponible = signal(true);
  motifIndisponible = signal<string | null>(null);

  reference = computed(() => {
    const a = this.analyse();
    if (!a) return null;
    const joint = this.lignes().map(l => l.valeur.trim() || '?').join(a.separateur);
    return a.majuscule ? joint.toUpperCase() : joint;
  });

  manquants = computed(() => this.lignes().filter(l => !l.valeur.trim()).length);
  venantDuContenu = computed(() => this.lignes().filter(l => l.source === 'CONTENU').length);

  /** Comment le contenu a été lu — null si l'OCRisation n'a pas été sollicitée. */
  lectureContenu(): string | null {
    const a = this.analyse();
    if (!a) return null;
    return { COUCHE_TEXTE: 'Couche texte du PDF', OCR: 'Reconnaissance optique (scan)', AUCUNE: '' }[a.provenanceTexte] || null;
  }

  form: FormGroup = this.fb.group({
    typeDocumentId: [null as number | null, Validators.required],
    name: [''],
    expirationDate: [''],
  });

  ngOnInit(): void {
    this.typeService.forSelect().subscribe(l => this.types.set(l));
    // L'interrupteur ne doit pas promettre une lecture que le serveur refusera :
    // on demande son état une fois pour toutes à l'ouverture.
    this.ocr.etat().subscribe(e => {
      if (!e.actif) {
        this.lectureDisponible.set(false);
        this.indexationAuto.set(false);
        this.motifIndisponible.set("La lecture automatique est désactivée sur ce serveur.");
      }
    });
    // Le formulaire réactif n'est pas un signal : on suit le champ pour tenir
    // `typeChoisi` à jour et pouvoir avertir dès la sélection.
    this.form.get('typeDocumentId')!.valueChanges.subscribe(id => {
      this.typeChoisi.set(this.types().find(x => x.id === id) ?? null);
    });
  }

  onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files && input.files.length ? input.files[0] : null);
  }

  submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const file = this.selectedFile();
    if (!file) {
      this.serverError.set('Veuillez choisir un fichier.');
      return;
    }
    const v = this.form.value;
    this.loading.set(true);
    this.service.upload(file, v.typeDocumentId, (v.name || '').trim(), (v.expirationDate || '').trim() || null)
      .subscribe({
        next: doc => {
          this.loading.set(false);
          this.documentId.set(doc.id);
          this.documentNom.set(doc.name);
          this.ouvrirIndexation(doc.id);
        },
        error: err => {
          this.loading.set(false);
          this.serverError.set(err?.error?.message ?? 'Le téléversement a échoué.');
        },
      });
  }

  /**
   * Ouvre l'étape d'indexation. Selon l'interrupteur, la GED lit le document
   * pour proposer les valeurs, ou se contente d'afficher les champs à saisir —
   * dans ce cas aucune lecture n'est demandée au serveur.
   */
  private ouvrirIndexation(id: number): void {
    this.etape.set('indexation');
    // Le dépôt n'est pas terminé tant que l'indexation n'est pas confirmée :
    // on ferme les sorties dérobées (Échap, clic hors de la boîte).
    this.ref.disableClose = true;
    if (this.indexationAuto()) this.analyser(id);
    else this.chargerChamps(id);
  }

  /** Champs du plan, sans aucune lecture du document : saisie entièrement manuelle. */
  private chargerChamps(id: number): void {
    this.analyseEnCours.set(true);
    this.indexation.champs(id).subscribe({
      next: (champs: Critere[]) => {
        this.lignes.set(champs.map(c => ({
          indexFieldId: c.id,
          libelle: c.libelle,
          fieldType: c.fieldType,
          options: c.options,
          proposee: null,
          source: null,
          motif: null,
          reconnue: false,
          valeur: '',
        })));
        this.analyseEnCours.set(false);
        if (!champs.length) this.terminer('sans-plan');
      },
      error: () => {
        this.analyseEnCours.set(false);
        this.serverError.set("Impossible de charger les index à renseigner.");
      },
    });
  }

  /** Lecture du document (couche texte ou OCR) : le serveur propose, il n'écrit pas. */
  private analyser(id: number): void {
    this.analyseEnCours.set(true);

    this.indexation.analyser(id).subscribe({
      next: a => {
        this.analyse.set(a);
        this.lignes.set(a.propositions.map(p => ({
          indexFieldId: p.indexFieldId,
          libelle: p.libelle,
          fieldType: p.fieldType,
          options: p.options,
          proposee: p.valeurProposee,
          source: p.source,
          motif: p.motif,
          reconnue: p.reconnue,
          valeur: p.valeurProposee ?? p.valeurActuelle ?? '',
        })));
        this.analyseEnCours.set(false);
        // Type sans plan d'indexation : il n'y a rien à confirmer, le dépôt est complet.
        if (!a.propositions.length) this.terminer('sans-plan');
      },
      error: () => {
        this.analyseEnCours.set(false);
        this.serverError.set("La lecture du document a échoué. Le document est déposé mais reste à indexer.");
      },
    });
  }

  /** Combien de champs la dernière lecture a réellement remplis. */
  remplisParLaGed = computed(() => this.lignes().filter(l => l.source !== null).length);

  /**
   * Relance la lecture automatique à la demande — utile après avoir activé la
   * lecture dans les réglages, ou pour retrouver la proposition d'origine.
   *
   * <p>Les saisies manuelles ne sont écrasées que là où la GED a effectivement
   * quelque chose à proposer : relancer ne doit pas faire perdre un champ que
   * l'opérateur vient de renseigner à la main et que la machine ne sait pas lire.
   */
  /**
   * L'opérateur active ou coupe l'indexation automatique en cours de saisie.
   * En l'activant, la GED lit et remplit ; en la coupant, elle retire ses propres
   * propositions et laisse la main — les valeurs tapées à la main sont conservées
   * dans les deux sens, on ne détruit jamais le travail de l'opérateur.
   */
  basculerAuto(actif: boolean): void {
    this.indexationAuto.set(actif);
    const id = this.documentId();
    if (id == null) return;
    if (actif) this.relancerLecture();
    else this.retirerPropositions();
  }

  /** Efface ce que la GED avait proposé, garde ce que l'opérateur a saisi. */
  private retirerPropositions(): void {
    this.analyse.set(null);
    this.lignes.update(l => l.map(x => x.source === null
      ? x
      : { ...x, valeur: '', proposee: null, source: null, motif: null, reconnue: false }));
  }

  relancerLecture(): void {
    const id = this.documentId();
    if (id == null) return;
    this.serverError.set(null);
    this.analyseEnCours.set(true);

    this.indexation.analyser(id).subscribe({
      next: a => {
        this.analyse.set(a);
        this.lignes.update(lignes => lignes.map(l => {
          const p = a.propositions.find(x => x.indexFieldId === l.indexFieldId);
          if (!p) return l;
          return {
            ...l,
            proposee: p.valeurProposee,
            source: p.source,
            motif: p.motif,
            reconnue: p.reconnue,
            valeur: p.valeurProposee ?? l.valeur,
          };
        }));
        this.analyseEnCours.set(false);
      },
      error: () => {
        this.analyseEnCours.set(false);
        this.serverError.set("La lecture du document a échoué.");
      },
    });
  }

  majValeur(i: number, valeur: string): void {
    this.lignes.update(l => l.map((x, k) => k === i ? { ...x, valeur } : x));
  }

  /** Le seul geste qui écrit les index. */
  confirmer(): void {
    const id = this.documentId();
    if (id == null) return;
    this.serverError.set(null);
    this.loading.set(true);

    const valeurs = this.lignes().map(l => ({
      indexFieldId: l.indexFieldId,
      valeur: l.valeur.trim() || null,
    }));

    this.indexation.enregistrer(id, valeurs).subscribe({
      next: () => {
        this.loading.set(false);
        this.terminer('indexe');
      },
      error: err => {
        this.loading.set(false);
        this.serverError.set(err?.error?.message ?? "L'enregistrement des index a été refusé.");
      },
    });
  }

  /**
   * Le document est déposé : la liste doit être rafraîchie dans tous les cas.
   * L'issue remonte à l'appelant, qui affiche un seul message — le dialogue n'en
   * émet aucun, sans quoi deux notifications se superposeraient.
   */
  private terminer(issue: IssueDepot): void {
    this.ref.disableClose = false;
    this.ref.close(issue);
  }

  /**
   * Sortie de secours quand la lecture a échoué : le fichier est déjà déposé,
   * le supprimer serait pire que le laisser à indexer depuis l'écran dédié.
   */
  reporter(): void {
    this.terminer('a-indexer');
  }

  cancel(): void {
    this.ref.close(false);
  }
}
