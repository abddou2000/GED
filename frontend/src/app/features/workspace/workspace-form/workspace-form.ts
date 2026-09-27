import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { WorkspaceService } from '../workspace.service';
import { SelectOption, WorkSpace, WorkSpaceRequest } from '../workspace.model';
import { EmployeService, Employe } from '../../../core/employe.service';
import { WorkflowService } from '../../workflow/workflow.service';
import { Workflow } from '../../workflow/workflow.model';

interface DialogData {
  workspace: WorkSpace | null;
  parentId?: string | null;
}

/** Formulaire créer / éditer un espace de travail — boîte de dialogue Material. */
@Component({
  selector: 'app-workspace-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule,
  ],
  templateUrl: './workspace-form.html',
  styleUrl: './workspace-form.scss',
})
export class WorkspaceForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(WorkspaceService);
  private employeService = inject(EmployeService);
  private workflowService = inject(WorkflowService);
  private ref = inject(MatDialogRef<WorkspaceForm>);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  employes = signal<Employe[]>([]);
  workflows = signal<Workflow[]>([]);
  parents = signal<SelectOption[]>([]);
  loading = signal(false);
  serverError = signal<string | null>(null);

  /** Masqué quand on crée un sous-dossier (le parent est imposé). */
  hideParent = false;

  form: FormGroup = this.fb.group({
    code: ['', Validators.required],
    name: ['', Validators.required],
    description: [''],
    workflowId: [null, Validators.required],
    employeId: [null, Validators.required],
    parentId: [null as string | null],
    usageEspace: ['METIER'],
    status: ['ACTIF', Validators.required],
  });

  get isEdit(): boolean {
    return !!this.data.workspace;
  }

  ngOnInit(): void {
    this.employeService.listApprovers().subscribe(l => this.employes.set(l));
    this.workflowService.list(0, 1000, '').subscribe(p => this.workflows.set(p.content));
    this.service.forSelect().subscribe(list => {
      // à l'édition, on ne peut pas se choisir soi-même comme parent
      const selfId = this.data.workspace?.id;
      this.parents.set(selfId ? list.filter(o => o.id !== selfId) : list);
    });

    if (this.data.workspace) {
      const w = this.data.workspace;
      this.form.patchValue({
        code: w.code, name: w.name, description: w.description ?? '',
        workflowId: w.workflow?.id ?? null, employeId: w.owner?.id ?? null,
        parentId: w.parent?.id ?? null, status: w.status, usageEspace: w.usageEspace ?? 'METIER',
      });
    } else if (this.data.parentId != null) {
      this.hideParent = true;
      this.form.patchValue({ parentId: this.data.parentId });
    }
  }

  submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.value;
    const body: WorkSpaceRequest = {
      code: v.code, name: v.name, description: v.description || null,
      status: v.status, employeId: v.employeId,
      parentId: v.parentId ?? null, workflowId: v.workflowId,
      usageEspace: v.parentId ? undefined : v.usageEspace,
    };
    this.loading.set(true);
    const call = this.data.workspace
      ? this.service.update(this.data.workspace.id, body)
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
