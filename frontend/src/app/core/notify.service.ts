import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

/**
 * Notifications discrètes (toasts) après une action. Enveloppe MatSnackBar
 * avec un style et un placement cohérents pour tout le module.
 */
@Injectable({ providedIn: 'root' })
export class NotifyService {
  private sb = inject(MatSnackBar);

  success(message: string): void {
    this.sb.open(message, '', {
      duration: 2600,
      panelClass: ['ged-snack', 'ged-snack-success'],
      horizontalPosition: 'right',
      verticalPosition: 'bottom',
    });
  }

  error(message: string): void {
    this.sb.open(message, 'Fermer', {
      duration: 5000,
      panelClass: ['ged-snack', 'ged-snack-error'],
      horizontalPosition: 'right',
      verticalPosition: 'bottom',
    });
  }
}
