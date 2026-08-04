import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { PlanIndexationService } from '../plan-indexation.service';
import { PlanIndexation, PlanIndexationRequest } from '../plan-indexation.model';
import { IndexService } from '../../index/index.service';
import { SelectOption } from '../../index/index.model';

interface DialogData {
  plan: PlanIndexation | null;
}

/**
 * Formulaire créer / éditer un plan d'indexation : identification, regroupement
 * d'index, et charte de nommage avec aperçu live (masqué en mode manuel).
 */
@Component({
  selector: 'app-plan-indexation-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatSlideToggleModule,
  ],
  templateUrl: './plan-indexation-form.html',
  styleUrl: './plan-indexation-form.scss',
})
export class PlanIndexationForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(PlanIndexationService);
  private indexService = inject(IndexService);
  private ref = inject(MatDialogRef<PlanIndexationForm>);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  indexOptions = signal<SelectOption[]>([]);
  loading = signal(false);
  serverError = signal<string | null>(null);

  form: FormGroup = this.fb.group({
    code: ['', Validators.required],
    nomDuPlan: ['', Validators.required],
    modeIndexation: [false],
    indexIds: [[] as number[]],
    manuel: [false],
    separateur: ['_'],
    majuscule: [false],
  });

  get isEdit(): boolean { return !!this.data.plan; }
  get isManuel(): boolean { return this.form.get('manuel')?.value === true; }

  /** Aperçu du nom composé à partir des index choisis. */
  get preview(): string {
    const v = this.form.value;
    const ids: number[] = v.indexIds ?? [];
    const names = ids
      .map(id => this.indexOptions().find(o => o.id === id)?.name)
      .filter((n): n is string => !!n);
    const joined = names.join(v.separateur || '_');
    return v.majuscule ? joined.toUpperCase() : joined.toLowerCase();
  }

  ngOnInit(): void {
    this.indexService.forSelect().subscribe(l => this.indexOptions.set(l));

    if (this.data.plan) {
      const p = this.data.plan;
      this.form.patchValue({
        code: p.code, nomDuPlan: p.nomDuPlan, modeIndexation: p.modeIndexation,
        indexIds: p.indices.map(i => i.id), manuel: p.manuel,
        separateur: p.separateur || '_', majuscule: p.majuscule,
      });
    }
  }

  submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.value;
    const body: PlanIndexationRequest = {
      code: v.code, nomDuPlan: v.nomDuPlan, modeIndexation: v.modeIndexation,
      manuel: v.manuel, majuscule: v.majuscule, separateur: v.separateur || '_',
      indexIds: v.indexIds ?? [],
    };
    this.loading.set(true);
    const call = this.data.plan
      ? this.service.update(this.data.plan.id, body)
      : this.service.create(body);
    call.subscribe({
      next: () => { this.loading.set(false); this.ref.close(true); },
      error: err => {
        this.loading.set(false);
        this.serverError.set(err?.error?.message ?? 'Une erreur est survenue.');
      },
    });
  }

  cancel(): void {
    this.ref.close(false);
  }
}
