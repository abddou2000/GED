import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { ConfirmDialog, ConfirmDialogData } from './confirm-dialog/confirm-dialog';

export type ConfirmOptions = Omit<ConfirmDialogData, 'prompt'>;
export type PromptOptions = Omit<ConfirmDialogData, 'prompt'> & { promptRequired?: boolean };

/**
 * Ouvre une boîte de dialogue Material soignée à la place des dialogues natifs du navigateur.
 *  - `ask(...)`     → Observable<boolean>  (true si confirmé)
 *  - `askText(...)` → Observable<string|null>  (texte saisi, ou null si annulé)
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private dialog = inject(MatDialog);

  ask(opts: ConfirmOptions): Observable<boolean> {
    return this.open({ ...opts, prompt: false }).pipe(map(r => r === true));
  }

  askText(opts: PromptOptions): Observable<string | null> {
    return this.open({ ...opts, prompt: true }).pipe(map(r => (typeof r === 'string' ? r : null)));
  }

  private open(data: ConfirmDialogData): Observable<unknown> {
    return this.dialog
      .open(ConfirmDialog, {
        data,
        width: '440px',
        maxWidth: '94vw',
        autoFocus: data.prompt ? 'input' : false,
        restoreFocus: true,
        ariaLabel: data.title,
      })
      .afterClosed();
  }
}
