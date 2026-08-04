import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog } from '@angular/material/dialog';
import { forkJoin } from 'rxjs';
import { IndexationService } from '../indexation.service';
import { Critere, FiltreIndex, Groupe, Resultat } from '../indexation.model';
import { ValeursForm } from '../valeurs-form/valeurs-form';
import { WorkspaceService } from '../../workspace/workspace.service';
import { TypeDocumentService } from '../../type-document/type-document.service';
import { SelectOption } from '../../workspace/workspace.model';
import { DocumentService } from '../../document/document.service';
import { NotifyService } from '../../../core/notify.service';

/**
 * Recherche indexée — l'écran ne connaît aucun critère à l'avance.
 * Le serveur renvoie les index cochés « indexé pour recherche » ; un contrôle
 * est fabriqué pour chacun selon son type. Créer un nouvel index suffit donc à
 * faire apparaître un critère de plus, sans toucher à ce composant.
 */
@Component({
  selector: 'app-recherche-indexee',
  imports: [
    ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatTooltipModule,
  ],
  templateUrl: './recherche-indexee.html',
  styleUrl: './recherche-indexee.scss',
})
export class RechercheIndexee implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(IndexationService);
  private workspaces = inject(WorkspaceService);
  private types = inject(TypeDocumentService);
  private documents = inject(DocumentService);
  private dialog = inject(MatDialog);
  private notify = inject(NotifyService);

  criteres = signal<Critere[]>([]);
  groupages = signal<Critere[]>([]);
  optionsWorkspace = signal<SelectOption[]>([]);
  optionsType = signal<SelectOption[]>([]);

  groupes = signal<Groupe[]>([]);
  loading = signal(false);
  recherchee = signal(false);      // une recherche a-t-elle déjà été lancée ?
  replies = signal<number[]>([]);  // ids des groupes repliés

  /** Portée + regroupement ; les critères d'index sont ajoutés dynamiquement. */
  form: FormGroup = this.fb.group({
    workspaceId: [null],
    typeDocumentId: [null],
    grouperPar: [null],
  });

  /** Nombre total de documents, tous groupes confondus. */
  total = computed(() => this.groupes().reduce((n, g) => n + g.total, 0));

  /**
   * Nombre de critères renseignés — affiché sur le bouton « Effacer ».
   * Alimenté par `valueChanges` : le formulaire réactif n'est pas un signal,
   * un `computed` qui le lirait resterait figé sur sa première valeur.
   */
  actifs = signal(0);

  constructor() {
    const sub = this.form.valueChanges.subscribe(() => this.actifs.set(this.compterActifs()));
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
  }

  private compterActifs(): number {
    return this.filtres().length
      + (this.form.value.workspaceId ? 1 : 0)
      + (this.form.value.typeDocumentId ? 1 : 0);
  }

  ngOnInit(): void {
    this.loading.set(true);
    forkJoin({
      criteres: this.service.criteres(),
      groupages: this.service.groupages(),
      ws: this.workspaces.forSelect(),
      td: this.types.forSelect(),
    }).subscribe({
      next: r => {
        this.criteres.set(r.criteres);
        this.groupages.set(r.groupages);
        this.optionsWorkspace.set(r.ws);
        this.optionsType.set(r.td);

        // Un contrôle par critère : simple pour TEXTE/LISTE, deux bornes pour DATE/NOMBRE
        for (const c of r.criteres) {
          if (this.estPlage(c)) {
            this.form.addControl(this.cle(c, 'de'), this.fb.control(null));
            this.form.addControl(this.cle(c, 'a'), this.fb.control(null));
          } else {
            this.form.addControl(this.cle(c), this.fb.control(null));
          }
        }
        // Regroupement par défaut : premier index de groupage disponible
        if (r.groupages.length) this.form.patchValue({ grouperPar: r.groupages[0].id });
        this.loading.set(false);
        this.rechercher();
      },
      error: () => {
        this.loading.set(false);
        this.notify.error('Impossible de charger les critères de recherche.');
      },
    });
  }

  /** DATE et NOMBRE se cherchent par intervalle, les autres par valeur. */
  estPlage(c: Critere): boolean { return c.fieldType === 'DATE' || c.fieldType === 'NOMBRE'; }

  cle(c: Critere, borne?: 'de' | 'a'): string { return borne ? `c${c.id}_${borne}` : `c${c.id}`; }

  private valeur(nom: string): string | null {
    const v = this.form.get(nom)?.value;
    return v === null || v === undefined || `${v}`.trim() === '' ? null : `${v}`.trim();
  }

  /** Traduit le formulaire en filtres ; un critère vide n'est pas envoyé. */
  private filtres(): FiltreIndex[] {
    const out: FiltreIndex[] = [];
    for (const c of this.criteres()) {
      if (this.estPlage(c)) {
        const de = this.valeur(this.cle(c, 'de'));
        const a = this.valeur(this.cle(c, 'a'));
        if (de || a) out.push({ indexFieldId: c.id, de, a });
      } else {
        const v = this.valeur(this.cle(c));
        if (v) out.push({ indexFieldId: c.id, valeur: v });
      }
    }
    return out;
  }

  rechercher(): void {
    this.loading.set(true);
    this.service.rechercher({
      workspaceId: this.form.value.workspaceId,
      typeDocumentId: this.form.value.typeDocumentId,
      grouperPar: this.form.value.grouperPar,
      criteres: this.filtres(),
    }).subscribe({
      next: g => {
        this.groupes.set(g);
        this.replies.set([]);
        this.recherchee.set(true);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.notify.error('La recherche a échoué.');
      },
    });
  }

  /** Vide les critères en conservant le regroupement choisi. */
  effacer(): void {
    const grouperPar = this.form.value.grouperPar;
    this.form.reset({ workspaceId: null, typeDocumentId: null, grouperPar });
    this.rechercher();
  }

  basculer(i: number): void {
    this.replies.update(l => l.includes(i) ? l.filter(x => x !== i) : [...l, i]);
  }

  replie(i: number): boolean { return this.replies().includes(i); }

  /** Saisie / correction des valeurs d'index d'un document. */
  indexer(doc: Resultat): void {
    this.dialog.open(ValeursForm, { width: '560px', data: { document: doc } })
      .afterClosed().subscribe(ok => { if (ok) this.rechercher(); });
  }

  telecharger(doc: Resultat): void {
    window.open(this.documents.downloadUrl(doc.id), '_blank');
  }
}
