import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { WorkspaceService } from '../workspace.service';
import { WorkSpace } from '../workspace.model';

/**
 * « Nouveau dossier » dans un espace d'échange (D12, ANO-F-016) : un nom, une
 * description facultative, rien d'autre.
 *
 * <p>Le bouton ouvrait le formulaire d'administration « Créer un espace de
 * travail » (code, propriétaire, statut) qui appelle POST /workspaces, réservé
 * à l'Administrateur : un membre de l'espace ne pouvait donc pas créer de
 * dossier à l'écran. Ce formulaire appelle POST /noeuds/{parent}/dossiers,
 * ouvert à qui peut Déposer sur le parent ; le code est attribué par le
 * serveur.</p>
 */
@Component({
  selector: 'app-dossier-simple-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>Nouveau dossier</h2>
    <mat-dialog-content>
      <p class="dans">Dans « {{ data.parentNom }} »</p>
      <form [formGroup]="form" class="form" (ngSubmit)="creer()">
        <mat-form-field appearance="outline" class="full">
          <mat-label>Nom du dossier</mat-label>
          <input matInput formControlName="nom" maxlength="255" cdkFocusInitial />
          @if (form.get('nom')?.touched && form.get('nom')?.invalid) {
            <mat-error>Le nom est obligatoire</mat-error>
          }
        </mat-form-field>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Description (optionnel)</mat-label>
          <textarea matInput formControlName="description" rows="2" maxlength="1000"></textarea>
        </mat-form-field>
        @if (erreur(); as e) { <p class="erreur">{{ e }}</p> }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" (click)="ref.close(false)">Annuler</button>
      <button mat-flat-button color="primary" type="button" class="creer" [disabled]="enCours()" (click)="creer()">
        {{ enCours() ? 'Création…' : 'Créer' }}
      </button>
    </mat-dialog-actions>`,
  styles: [`
    .form { display: flex; flex-direction: column; min-width: min(420px, 80vw); }
    .full { width: 100%; }
    .dans { margin: 0 0 12px; font-size: 13px; color: var(--ink-soft); }
    .erreur { margin: 0; color: var(--danger); font-size: 13px; }
  `],
})
export class DossierSimpleForm {
  private fb = inject(FormBuilder);
  private service = inject(WorkspaceService);
  readonly ref = inject(MatDialogRef<DossierSimpleForm, WorkSpace | false>);
  readonly data = inject<{ parentId: string; parentNom: string }>(MAT_DIALOG_DATA);

  enCours = signal(false);
  erreur = signal<string | null>(null);

  form = this.fb.group({
    nom: ['', [Validators.required, Validators.maxLength(255)]],
    description: ['', Validators.maxLength(1000)],
  });

  creer(): void {
    if (this.enCours()) return;
    const nom = (this.form.value.nom ?? '').trim();
    if (!nom) { this.form.get('nom')!.setValue(''); this.form.markAllAsTouched(); return; }
    this.enCours.set(true);
    this.erreur.set(null);
    const description = (this.form.value.description ?? '').trim() || null;
    this.service.creerDossier(this.data.parentId, { nom, description }).subscribe({
      next: cree => { this.enCours.set(false); this.ref.close(cree); },
      error: e => {
        this.enCours.set(false);
        this.erreur.set(e?.error?.detail ?? e?.error?.message ?? 'Création du dossier impossible.');
      },
    });
  }
}
