import { Component, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * Bouton « Sélectionner » — entrée et sortie du mode sélection d'une liste.
 *
 * <p>Les cases à cocher ne sont utiles qu'au moment d'une action groupée. Les
 * afficher en permanence met en tête de chaque ligne un élément qui ne sert
 * presque jamais ; les révéler au seul survol oblige à deviner qu'elles
 * existent. Un bouton dit les deux choses : que la sélection multiple est
 * possible, et quand on y est.
 *
 * <p>Le composant ne porte que l'état d'affichage. La liste hôte reste maîtresse
 * de sa sélection : c'est elle qui la vide en sortant du mode.
 */
@Component({
  selector: 'app-selection-toggle',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  template: `
    <button mat-stroked-button type="button" class="sel-btn" [class.on]="actif()"
            (click)="basculer()"
            [attr.aria-pressed]="actif()">
      <mat-icon [svgIcon]="actif() ? 'close' : 'checkbox-on'"></mat-icon>
      {{ actif() ? 'Annuler' : 'Sélectionner' }}
    </button>
  `,
  styles: [`
    .sel-btn { white-space: nowrap; }
    /* En mode sélection le bouton est plein : c'est le seul repère qui dit
       pourquoi les cases sont apparues. */
    .sel-btn.on {
      background: var(--primary); color: #fff; border-color: var(--primary);
    }
  `],
})
export class SelectionToggle {
  /** Émis à chaque bascule : vrai quand le mode sélection est actif. */
  readonly change = output<boolean>();

  readonly actif = signal(false);

  basculer(): void {
    this.actif.update(v => !v);
    this.change.emit(this.actif());
  }

  /** Sortie forcée par la liste hôte (rechargement, changement de vue…). */
  fermer(): void {
    if (!this.actif()) return;
    this.actif.set(false);
    this.change.emit(false);
  }
}
