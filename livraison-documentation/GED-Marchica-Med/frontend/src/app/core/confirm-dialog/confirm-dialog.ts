import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface ConfirmDialogData {
  title: string;
  message?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
  icon?: string;
  /* Mode saisie (ex. motif de rejet) */
  prompt?: boolean;
  promptLabel?: string;
  promptPlaceholder?: string;
  promptRequired?: boolean;
  promptValue?: string;
}

/**
 * Boîte de dialogue Material réutilisable — remplace les dialogues natifs du navigateur.
 * Ferme avec :  `true` (confirmation simple) · la chaîne saisie (mode motif) ·
 * `undefined` (annulation / Échap / clic hors cadre).
 */
@Component({
  selector: 'app-confirm-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule],
  templateUrl: './confirm-dialog.html',
  styleUrl: './confirm-dialog.scss',
})
export class ConfirmDialog {
  protected data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private ref = inject(MatDialogRef<ConfirmDialog>);

  protected value = signal(this.data.promptValue ?? '');
  protected touched = signal(false);

  protected icon(): string {
    return this.data.icon ?? (this.data.danger ? 'alert' : 'nav-mesworkflow');
  }

  protected invalid(): boolean {
    return !!this.data.prompt && !!this.data.promptRequired && this.value().trim().length === 0;
  }

  accept(): void {
    if (this.data.prompt) {
      if (this.invalid()) { this.touched.set(true); return; }
      this.ref.close(this.value().trim());
    } else {
      this.ref.close(true);
    }
  }

  cancel(): void {
    this.ref.close(undefined);
  }
}
