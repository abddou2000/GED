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

/** Formulaire créer / éditer une règle de workflow — ouvert en boîte de dialogue Material. */
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
  private ref = inject(MatDialogRef<WorkflowForm>);
  data = inject<{ workflow: Workflow | null }>(MAT_DIALOG_DATA);

  employes = signal<Employe[]>([]);
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
    if (this.data.workflow) {
      this.form.patchValue({ name: this.data.workflow.name });
      this.data.workflow.steps.forEach(s => this.steps.push(this.buildStep(s.employeId, s.label)));
    }
  }

  private buildStep(employeId: string | null = null, label = ''): FormGroup {
    return this.fb.group({
      employeId: [employeId, Validators.required],
      label: [label, [Validators.required, Validators.maxLength(255)]],
    });
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
    this.erreurEtapes.set(this.steps.length === 0 ? 'Au moins une étape est requise.' : null);
    if (this.form.invalid || this.steps.length === 0) {
      this.form.markAllAsTouched();
      return;
    }
    const body: WorkflowRequest = {
      name: this.form.value.name,
      steps: this.steps.controls.map((c, i) => ({
        employeId: c.value.employeId,
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
