import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { ConfirmDialog, ConfirmData } from './confirm-dialog/confirm-dialog';

/** Ouvre une confirmation élégante et renvoie true / false selon le choix. */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private dialog = inject(MatDialog);

  ask(data: ConfirmData): Observable<boolean> {
    return this.dialog.open(ConfirmDialog, {
      data, width: '400px', maxWidth: '92vw', autoFocus: false,
    }).afterClosed();
  }
}
