import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatRadioModule } from '@angular/material/radio';
import { CdkDrag, CdkDropList, CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { toSignal } from '@angular/core/rxjs-interop';
import { PlanIndexationService } from '../plan-indexation.service';
import { Jeton, PlanIndexation, PlanIndexationRequest } from '../plan-indexation.model';
import { IndexService } from '../../index/index.service';
import { SelectOption } from '../../index/index.model';

interface DialogData {
  plan: PlanIndexation | null;
}

/**
 * Formulaire créer / éditer un plan d'indexation.
 *
 * <p>La charte de nommage n'est pas un simple réglage : elle décide quels
 * champs composent le nom du fichier et <em>dans quel ordre</em>. D'où les deux
 * listes et le glisser-déposer, repris de l'application d'origine — une
 * multi-sélection ne permet pas d'exprimer un ordre.
 */
@Component({
  selector: 'app-plan-indexation-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule, MatTooltipModule,
    MatRadioModule, CdkDrag, CdkDropList, RouterLink, NgTemplateOutlet,
  ],
  templateUrl: './plan-indexation-form.html',
  styleUrl: './plan-indexation-form.scss',
})
export class PlanIndexationForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(PlanIndexationService);
  private indexService = inject(IndexService);
  // Le même composant sert de page (/create, /:id/edit) et de boîte de dialogue :
  // l'original édite sur des pages dédiées, mais le reste de Marchica ouvre des
  // modales. Dupliquer le formulaire les aurait fait diverger à la première
  // correction.
  private ref = inject(MatDialogRef<PlanIndexationForm>, { optional: true });
  private route = inject(ActivatedRoute, { optional: true });
  private router = inject(Router);
  data = inject<DialogData>(MAT_DIALOG_DATA, { optional: true });

  /** Vrai quand le formulaire occupe une page plutôt qu'une modale. */
  readonly modePage = signal(false);
  private planCharge = signal<PlanIndexation | null>(null);

  indexOptions = signal<SelectOption[]>([]);
  jetonsSysteme = signal<{ id: string; name: string }[]>([]);
  loading = signal(false);
  serverError = signal<string | null>(null);

  /** Jetons retenus pour le nom, dans l'ordre choisi. */
  charteIds = signal<string[]>([]);

  /**
   * Drapeau `modeIndexation` du plan, conservé tel quel.
   *
   * <p>Il n'a plus d'interrupteur : l'indexation automatique se décide au
   * DÉPÔT du document, pas dans le plan — un même plan sert des dépôts lus
   * automatiquement et d'autres saisis à la main. La valeur est néanmoins
   * renvoyée inchangée à l'enregistrement : la remettre à `false` d'office
   * modifierait en silence les plans existants qui la portent à `true`.</p>
   */
  private modeIndexation = false;

  form: FormGroup = this.fb.group({
    code: ['', Validators.required],
    nomDuPlan: ['', Validators.required],
    indexIds: [[] as string[]],
    manuel: [false],
    separateur: ['_'],
    majuscule: [false],
  });

  /** Valeurs suivies en signal, pour que l'aperçu se recalcule à chaque frappe. */
  private valeurs = toSignal(this.form.valueChanges, { initialValue: this.form.value });

  private get plan(): PlanIndexation | null {
    return this.data?.plan ?? this.planCharge();
  }

  get isEdit(): boolean { return !!this.plan; }
  get isManuel(): boolean { return this.form.get('manuel')?.value === true; }

  /** Tous les jetons possibles : les index du plan, plus les jetons système. */
  private readonly tous = computed<Jeton[]>(() => {
    const ids: string[] = this.valeurs()?.indexIds ?? [];
    const duPlan = ids
      .map(id => this.indexOptions().find(o => o.id === id))
      .filter((o): o is SelectOption => !!o)
      .map(o => ({ id: String(o.id), name: o.name, systeme: false }));
    return [...this.jetonsSysteme().map(j => ({ ...j, systeme: true })), ...duPlan];
  });

  /** Jetons pas encore placés dans le nom. */
  readonly disponibles = computed<Jeton[]>(() =>
    this.tous().filter(j => !this.charteIds().includes(j.id)));

  /** Jetons placés, dans l'ordre du nom. */
  readonly selectionnes = computed<Jeton[]>(() =>
    this.charteIds()
      .map(id => this.tous().find(j => j.id === id))
      .filter((j): j is Jeton => !!j));

  /** Aperçu du nom composé, reflétant l'ordre réel. */
  readonly preview = computed(() => {
    const v = this.valeurs();
    const joint = this.selectionnes().map(j => j.name).join(v?.separateur || '_');
    return v?.majuscule ? joint.toUpperCase() : joint.toLowerCase();
  });

  ngOnInit(): void {
    this.indexService.forSelect().subscribe(l => this.indexOptions.set(l));
    this.service.jetonsSysteme().subscribe(l => this.jetonsSysteme.set(l));

    if (this.data) {
      if (this.data.plan) this.remplir(this.data.plan);
    } else {
      this.modePage.set(true);
      const id = this.route?.snapshot.paramMap.get('id');
      if (id) {
        this.service.get(id).subscribe({
          next: p => { this.planCharge.set(p); this.remplir(p); },
          error: () => this.router.navigate(['/plan-indexation']),
        });
      }
    }

    // Retirer un index du plan doit le retirer du nom : le laisser produirait
    // un aperçu qui promet un champ que le plan ne porte plus.
    this.form.get('indexIds')!.valueChanges.subscribe((ids: string[]) => {
      const gardes = new Set((ids ?? []).map(String));
      this.charteIds.update(l => l.filter(id => gardes.has(id) || this.estSysteme(id)));
    });
  }

  private remplir(p: PlanIndexation): void {
    this.form.patchValue({
      code: p.code, nomDuPlan: p.nomDuPlan,
      indexIds: p.indices.map(i => i.id), manuel: p.manuel,
      separateur: p.separateur || '_', majuscule: p.majuscule,
    });
    this.modeIndexation = p.modeIndexation;
    this.charteIds.set([...(p.charteIds ?? [])]);
  }

  private estSysteme(id: string): boolean {
    return this.jetonsSysteme().some(j => j.id === id);
  }

  ajouter(j: Jeton): void {
    if (!this.charteIds().includes(j.id)) this.charteIds.update(l => [...l, j.id]);
  }

  retirer(j: Jeton): void {
    this.charteIds.update(l => l.filter(id => id !== j.id));
  }

  deplacer(e: CdkDragDrop<Jeton[]>): void {
    const l = [...this.charteIds()];
    moveItemInArray(l, e.previousIndex, e.currentIndex);
    this.charteIds.set(l);
  }

  submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.value;
    const body: PlanIndexationRequest = {
      code: v.code, nomDuPlan: v.nomDuPlan, modeIndexation: this.modeIndexation,
      manuel: v.manuel, majuscule: v.majuscule, separateur: v.separateur || '_',
      indexIds: v.indexIds ?? [],
      charteIds: v.manuel ? [] : this.charteIds(),
    };
    this.loading.set(true);
    const courant = this.plan;
    const call = courant
      ? this.service.update(courant.id, body)
      : this.service.create(body);
    call.subscribe({
      next: () => { this.loading.set(false); this.terminer(true); },
      error: err => {
        this.loading.set(false);
        this.serverError.set(err?.error?.message ?? 'Une erreur est survenue.');
      },
    });
  }

  cancel(): void {
    this.terminer(false);
  }

  /** Referme la modale, ou revient à la liste quand on est sur une page. */
  private terminer(enregistre: boolean): void {
    if (this.ref) { this.ref.close(enregistre); return; }
    this.router.navigate(['/plan-indexation']);
  }
}
