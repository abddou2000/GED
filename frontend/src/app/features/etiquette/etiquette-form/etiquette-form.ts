import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { EtiquetteService } from '../etiquette.service';
import { Etiquette, EtiquetteRequest } from '../etiquette.model';

interface DialogData {
  etiquette: Etiquette | null;
}

/** Formulaire créer / éditer une étiquette : libellé, code et couleur, avec aperçu. */
@Component({
  selector: 'app-etiquette-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule,
  ],
  templateUrl: './etiquette-form.html',
  styleUrl: './etiquette-form.scss',
})
export class EtiquetteForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(EtiquetteService);
  private ref = inject(MatDialogRef<EtiquetteForm>);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  loading = signal(false);
  serverError = signal<string | null>(null);

  form: FormGroup = this.fb.group({
    tag: ['', Validators.required],
    code: ['', Validators.required],
    couleur: ['#16406b', Validators.required],
  });

  get isEdit(): boolean { return !!this.data.etiquette; }

  ngOnInit(): void {
    if (this.data.etiquette) {
      const e = this.data.etiquette;
      this.form.patchValue({ tag: e.tag, code: e.code, couleur: e.couleur });
    }
  }

  submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const body: EtiquetteRequest = this.form.value;
    this.loading.set(true);
    const call = this.data.etiquette
      ? this.service.update(this.data.etiquette.id, body)
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
