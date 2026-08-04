import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { IndexService } from '../index.service';
import { FIELD_TYPES, IndexField, IndexRequest } from '../index.model';

interface DialogData {
  index: IndexField | null;
}

/** Formulaire créer / éditer un index — le champ « Valeurs » n'apparaît qu'en type Liste. */
@Component({
  selector: 'app-index-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatSlideToggleModule,
  ],
  templateUrl: './index-form.html',
  styleUrl: './index-form.scss',
})
export class IndexForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(IndexService);
  private ref = inject(MatDialogRef<IndexForm>);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  readonly fieldTypes = FIELD_TYPES;
  loading = signal(false);
  serverError = signal<string | null>(null);

  form: FormGroup = this.fb.group({
    nomIndex: ['', Validators.required],
    code: ['', Validators.required],
    fieldType: ['TEXTE', Validators.required],
    valeurs: [''],
    valeurParDefaut: [''],
    obligatoire: [false],
    indexePourRecherche: [false],
    indexDeGroupage: [false],
  });

  get isEdit(): boolean { return !!this.data.index; }
  get isListe(): boolean { return this.form.get('fieldType')?.value === 'LISTE'; }

  ngOnInit(): void {
    if (this.data.index) {
      const x = this.data.index;
      this.form.patchValue({
        nomIndex: x.nomIndex, code: x.code, fieldType: x.fieldType,
        valeurs: x.valeurs ?? '', valeurParDefaut: x.valeurParDefaut ?? '',
        obligatoire: x.obligatoire, indexePourRecherche: x.indexePourRecherche,
        indexDeGroupage: x.indexDeGroupage,
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
    const body: IndexRequest = {
      code: v.code, nomIndex: v.nomIndex, fieldType: v.fieldType,
      valeurs: v.fieldType === 'LISTE' ? (v.valeurs?.trim() || null) : null,
      valeurParDefaut: v.valeurParDefaut?.trim() || null,
      obligatoire: v.obligatoire, indexePourRecherche: v.indexePourRecherche,
      indexDeGroupage: v.indexDeGroupage,
    };
    this.loading.set(true);
    const call = this.data.index
      ? this.service.update(this.data.index.id, body)
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
