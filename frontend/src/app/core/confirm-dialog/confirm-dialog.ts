import { Component, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

export interface ConfirmData {
  title?: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
}

/** Boîte de dialogue de confirmation élégante (remplace le confirm() natif). */
@Component({
  selector: 'app-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  templateUrl: './confirm-dialog.html',
  styleUrl: './confirm-dialog.scss',
})
export class ConfirmDialog {
  data = inject<ConfirmData>(MAT_DIALOG_DATA);
  private ref = inject(MatDialogRef<ConfirmDialog>);

  cancel(): void { this.ref.close(false); }
  confirm(): void { this.ref.close(true); }
}
