import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

/**
 * Retour utilisateur discret (toasts) via MatSnackBar — coin bas-droit.
 * Styles définis globalement (styles.scss) : vert (succès), cramoisi (erreur), marine (info).
 * NB : `@angular/animations` doit être installé, sinon MatSnackBar échoue en NG0203.
 */
@Injectable({ providedIn: 'root' })
export class NotifyService {
  private snack = inject(MatSnackBar);

  success(message: string): void { this.show(message, 'snack-success'); }
  error(message: string): void { this.show(message, 'snack-error'); }
  info(message: string): void { this.show(message, 'snack-info'); }

  private show(message: string, cls: string): void {
    this.snack.open(message, '', {
      duration: 3500,
      horizontalPosition: 'end',
      verticalPosition: 'bottom',
      panelClass: ['snack', cls],
    });
  }
}
