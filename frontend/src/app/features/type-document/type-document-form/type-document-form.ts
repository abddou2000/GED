import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { TypeDocumentService } from '../type-document.service';
import { FILE_TYPES, TypeDocument, TypeDocumentRequest } from '../type-document.model';
import { WorkspaceService } from '../../workspace/workspace.service';
import { SelectOption } from '../../workspace/workspace.model';
import { PlanIndexationService } from '../../plan-indexation/plan-indexation.service';

interface DialogData {
  type: TypeDocument | null;
}

/**
 * Formulaire créer / éditer un type de document : rattachement à un dossier
 * (obligatoire) et à un plan d'indexation (facultatif), formats et taille max.
 */
@Component({
  selector: 'app-type-document-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule,
  ],
  templateUrl: './type-document-form.html',
  styleUrl: './type-document-form.scss',
})
export class TypeDocumentForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(TypeDocumentService);
  private workspaceService = inject(WorkspaceService);
  private planService = inject(PlanIndexationService);
  private ref = inject(MatDialogRef<TypeDocumentForm>);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  readonly fileTypes = FILE_TYPES;
  workspaces = signal<SelectOption[]>([]);
  plans = signal<SelectOption[]>([]);
  loading = signal(false);
  serverError = signal<string | null>(null);

  form: FormGroup = this.fb.group({
    code: ['', Validators.required],
    typeDeDocument: ['', Validators.required],
    description: ['', Validators.required],
    workspaceId: [null as number | null, Validators.required],
    planIndexationId: [null as number | null],
    typeAutorise: [[] as string[], Validators.required],
    tailleMaxMo: [10, [Validators.required, Validators.min(5)]],
  });

  get isEdit(): boolean { return !!this.data.type; }

  ngOnInit(): void {
    this.workspaceService.forSelect().subscribe(l => this.workspaces.set(l));
    this.planService.forSelect().subscribe(l => this.plans.set(l));

    if (this.data.type) {
      const t = this.data.type;
      this.form.patchValue({
        code: t.code, typeDeDocument: t.typeDeDocument, description: t.description,
        workspaceId: t.workspace?.id ?? null, planIndexationId: t.planIndexation?.id ?? null,
        typeAutorise: t.typeAutorise, tailleMaxMo: t.tailleMaxMo,
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
    const body: TypeDocumentRequest = {
      code: v.code, typeDeDocument: v.typeDeDocument, description: v.description,
      workspaceId: v.workspaceId, planIndexationId: v.planIndexationId ?? null,
      typeAutorise: v.typeAutorise ?? [], tailleMaxMo: v.tailleMaxMo,
    };
    this.loading.set(true);
    const call = this.data.type
      ? this.service.update(this.data.type.id, body)
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
