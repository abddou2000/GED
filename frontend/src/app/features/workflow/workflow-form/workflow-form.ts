import { Component, OnInit, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { CdkDragDrop, DragDropModule } from '@angular/cdk/drag-drop';
import { WorkflowService } from '../workflow.service';
import { EmployeService, Employe } from '../../../core/employe.service';
import { Workflow, WorkflowRequest } from '../workflow.model';
import { DroitsService, RoleVue } from '../../administration/droits.service';
import { WorkspaceService } from '../../workspace/workspace.service';
import { SelectOption } from '../../workspace/workspace.model';

/**
 * Formulaire créer / éditer une règle de workflow (§12.8) — boîte de dialogue.
 * Chaque validateur est une personne NOMMÉE ou un RÔLE sur un périmètre ;
 * tous sont sollicités en même temps (D7), l'ordre n'est qu'affichage. Une
 * modification ne vaut que pour les dépôts futurs : les circuits ouverts sont
 * des copies figées.
 */
@Component({
  selector: 'app-workflow-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule, DragDropModule,
  ],
  templateUrl: './workflow-form.html',
  styleUrl: './workflow-form.scss',
})
export class WorkflowForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(WorkflowService);
  private employeService = inject(EmployeService);
  private droits = inject(DroitsService);
  private noeuds = inject(WorkspaceService);
  private ref = inject(MatDialogRef<WorkflowForm>);
  data = inject<{ workflow: Workflow | null }>(MAT_DIALOG_DATA);

  employes = signal<Employe[]>([]);
  roles = signal<RoleVue[]>([]);
  perimetres = signal<SelectOption[]>([]);
  loading = signal(false);
  serverError = signal<string | null>(null);
  /** Une règle sans étape n'a pas de circuit : on le dit, au lieu de refuser en silence. */
  erreurEtapes = signal<string | null>(null);

  form: FormGroup = this.fb.group({
    name: ['', Validators.required],
    steps: this.fb.array([]),
  });

  get steps(): FormArray {
    return this.form.get('steps') as FormArray;
  }

  get isEdit(): boolean {
    return !!this.data.workflow;
  }

  ngOnInit(): void {
    this.employeService.listApprovers().subscribe(l => this.employes.set(l));
    this.droits.roles().subscribe({ next: l => this.roles.set(l), error: () => this.roles.set([]) });
    this.noeuds.forSelect().subscribe({ next: l => this.perimetres.set(l), error: () => this.perimetres.set([]) });
    if (this.data.workflow) {
      this.form.patchValue({ name: this.data.workflow.name });
      this.data.workflow.steps.forEach(s => this.steps.push(
        this.buildStep(s.roleId ? 'ROLE' : 'NOMME', s.employeId, s.roleId ?? null, s.perimetreNoeudId ?? null, s.label)));
    }
  }

  private buildStep(nature: 'NOMME' | 'ROLE' = 'NOMME', employeId: string | null = null,
                    roleId: string | null = null, perimetreNoeudId: string | null = null, label = ''): FormGroup {
    const g = this.fb.group({
      nature: [nature],
      employeId: [employeId],
      roleId: [roleId],
      perimetreNoeudId: [perimetreNoeudId],
      label: [label, [Validators.required, Validators.maxLength(255)]],
    });
    this.appliquerNature(g, nature);
    g.get('nature')!.valueChanges.subscribe(n => this.appliquerNature(g, n as 'NOMME' | 'ROLE'));
    return g;
  }

  /** Nommé : l'employé est exigé ; par rôle : le rôle (le périmètre reste facultatif). */
  private appliquerNature(g: FormGroup, nature: 'NOMME' | 'ROLE'): void {
    const employe = g.get('employeId')!;
    const role = g.get('roleId')!;
    employe.setValidators(nature === 'NOMME' ? Validators.required : null);
    role.setValidators(nature === 'ROLE' ? Validators.required : null);
    if (nature === 'NOMME') { role.setValue(null, { emitEvent: false }); g.get('perimetreNoeudId')!.setValue(null); }
    else employe.setValue(null, { emitEvent: false });
    employe.updateValueAndValidity({ emitEvent: false });
    role.updateValueAndValidity({ emitEvent: false });
  }

  /** Libellé proposé à partir du rôle choisi. */
  onRoleChange(i: number, roleId: string): void {
    const r = this.roles().find(x => x.id === roleId);
    const label = this.steps.at(i).get('label');
    if (r && !label?.value) label?.setValue(r.libelle);
  }

  stepGroup(i: number): FormGroup {
    return this.steps.at(i) as FormGroup;
  }

  addStep(): void {
    this.steps.push(this.buildStep());
    this.erreurEtapes.set(null);
  }

  removeStep(i: number): void {
    this.steps.removeAt(i);
  }

  /** Auto-remplit le libellé avec « prénom nom » à chaque changement d'approbateur. */
  onEmployeChange(i: number, employeId: string): void {
    const e = this.employes().find(x => x.id === employeId);
    if (e) this.steps.at(i).get('label')?.setValue(`${e.firstName} ${e.lastName}`);
  }

  /** Réordonnancement par glisser-déposer (CDK). */
  drop(event: CdkDragDrop<unknown>): void {
    const ctrl = this.steps.at(event.previousIndex);
    this.steps.removeAt(event.previousIndex);
    this.steps.insert(event.currentIndex, ctrl);
  }

  submit(): void {
    this.serverError.set(null);
    this.erreurEtapes.set(this.steps.length === 0 ? 'Au moins un validateur est requis.' : null);
    if (this.form.invalid || this.steps.length === 0) {
      this.form.markAllAsTouched();
      return;
    }
    const body: WorkflowRequest = {
      name: this.form.value.name,
      steps: this.steps.controls.map((c, i) => ({
        employeId: c.value.nature === 'NOMME' ? c.value.employeId : null,
        roleId: c.value.nature === 'ROLE' ? c.value.roleId : null,
        perimetreNoeudId: c.value.nature === 'ROLE' ? (c.value.perimetreNoeudId || null) : null,
        label: c.value.label,
        stepOrder: i + 1,
      })),
    };
    this.loading.set(true);
    const call = this.data.workflow
      ? this.service.update(this.data.workflow.id, body)
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
