import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { AccessGroupService } from '../access-group.service';
import { AccessGroup, AccessGroupRequest, GedRights, RIGHT_KEYS, EMPTY_RIGHTS } from '../access-group.model';
import { WorkspaceService } from '../../workspace/workspace.service';
import { SelectOption } from '../../workspace/workspace.model';
import { EmployeService, Employe } from '../../../core/employe.service';

interface DialogData {
  group: AccessGroup | null;
}

/**
 * Formulaire créer / éditer un groupe d'accès — 3 sections : Identification,
 * Droits GED (avec cascade de dépendances), Utilisateurs & Workspaces.
 */
@Component({
  selector: 'app-access-group-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatSlideToggleModule,
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

  readonly rightKeys = RIGHT_KEYS;
  rights = signal<GedRights>(EMPTY_RIGHTS());
  workspaces = signal<SelectOption[]>([]);
  employes = signal<Employe[]>([]);
  loading = signal(false);
  serverError = signal<string | null>(null);

  form: FormGroup = this.fb.group({
    code: ['', Validators.required],
    name: ['', Validators.required],
    workspaceIds: [[] as number[]],
    userIds: [[] as number[]],
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
      this.rights.set({ ...g.rights });
    }
  }

  isOn(key: keyof GedRights): boolean {
    return this.rights()[key];
  }

  /**
   * Cascade de dépendances façon « explorateur Windows » (reprise de CCISTTA) :
   * activer un droit fort active ses prérequis ; désactiver un prérequis coupe les droits dépendants.
   */
  onRightChange(key: keyof GedRights, value: boolean): void {
    const r: GedRights = { ...this.rights(), [key]: value };
    if (value) {
      switch (key) {
        case 'modifier': r.uploader = true; r.lecture = true; r.access = true; break;
        case 'supprimer': r.modifier = true; r.uploader = true; r.lecture = true; r.access = true; break;
        case 'deplacer': r.modifier = true; r.uploader = true; r.lecture = true; r.access = true; break;
        case 'ajouterVersion': r.uploader = true; r.lecture = true; r.access = true; break;
        case 'uploader': r.lecture = true; r.access = true; break;
        case 'lecture': r.access = true; break;
        case 'verrouillerDeverrouiller': r.access = true; break;
      }
    } else {
      switch (key) {
        case 'access':
          r.lecture = false; r.modifier = false; r.uploader = false; r.supprimer = false;
          r.deplacer = false; r.ajouterVersion = false; r.verrouillerDeverrouiller = false;
          break;
        case 'lecture':
          r.modifier = false; r.uploader = false; r.supprimer = false; r.deplacer = false; r.ajouterVersion = false;
          break;
        case 'uploader':
          r.modifier = false; r.supprimer = false; r.deplacer = false; r.ajouterVersion = false;
          break;
        case 'modifier':
          r.supprimer = false; r.deplacer = false;
          break;
      }
    }
    this.rights.set(r);
  }

  submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.value;
    const body: AccessGroupRequest = {
      code: v.code, name: v.name, rights: this.rights(),
      workspaceIds: v.workspaceIds ?? [], userIds: v.userIds ?? [],
    };
    this.loading.set(true);
    const call = this.data.group
      ? this.service.update(this.data.group.id, body)
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
