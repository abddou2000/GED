import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { AccessGroupService } from '../access-group.service';
import { AccessGroup, AccessGroupRequest } from '../access-group.model';
import { WorkspaceService } from '../../workspace/workspace.service';
import { SelectOption } from '../../workspace/workspace.model';
import { EmployeService, Employe } from '../../../core/employe.service';

interface DialogData {
  group: AccessGroup | null;
}

/**
 * Formulaire créer / éditer un groupe d'accès — 2 sections : Identification,
 * Utilisateurs & Workspaces. Le groupe ne décrit qu'un rattachement : il n'ouvre
 * ni ne ferme aucun droit, l'application n'ayant qu'un seul utilisateur.
 */
@Component({
  selector: 'app-access-group-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule,
  ],
  templateUrl: './access-group-form.html',
  styleUrl: './access-group-form.scss',
})
export class AccessGroupForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(AccessGroupService);
  private workspaceService = inject(WorkspaceService);
  private employeService = inject(EmployeService);
  private ref = inject(MatDialogRef<AccessGroupForm>);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  workspaces = signal<SelectOption[]>([]);
  employes = signal<Employe[]>([]);
  loading = signal(false);
  serverError = signal<string | null>(null);

  form: FormGroup = this.fb.group({
    code: ['', Validators.required],
    name: ['', Validators.required],
    workspaceIds: [[] as string[]],
    userIds: [[] as string[]],
  });

  get isEdit(): boolean {
    return !!this.data.group;
  }

  ngOnInit(): void {
    this.workspaceService.forSelect().subscribe(l => this.workspaces.set(l));
    this.employeService.listApprovers().subscribe(l => this.employes.set(l));

    if (this.data.group) {
      const g = this.data.group;
      this.form.patchValue({
        code: g.code, name: g.name,
        workspaceIds: g.workspaces.map(w => w.id),
        userIds: g.users.map(u => u.id),
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
    const body: AccessGroupRequest = {
      code: v.code, name: v.name,
      workspaceIds: v.workspaceIds ?? [], userIds: v.userIds ?? [],
    };
    this.loading.set(true);
    const call = this.data.group
      ? this.service.update(this.data.group.id, body)
      : this.service.create(body);
    call.subscribe({
      // On renvoie le groupe enregistré, et non un simple booléen : l'appelant
      // a besoin de son identifiant pour ouvrir la fiche après une création.
      next: g => { this.loading.set(false); this.ref.close(g); },
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
