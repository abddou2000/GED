import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColumnPrefs, ColonneDef } from '../column-prefs.service';

/**
 * Sélecteur de colonnes d'un tableau — reprend le comportement de
 * l'application d'origine : chaque écran mémorise les colonnes que
 * l'utilisateur a masquées, et les retrouve à la visite suivante.
 *
 * <p>Les colonnes marquées `toujours` (case à cocher, actions) ne sont pas
 * proposées : les masquer priverait la liste de toute interaction.
 */
@Component({
  selector: 'app-column-picker',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatCheckboxModule, MatTooltipModule],
  template: `
    <button mat-icon-button [matMenuTriggerFor]="menu"
            matTooltip="Colonnes affichées" aria-label="Colonnes affichées">
      <mat-icon svgIcon="columns"></mat-icon>
    </button>
    <mat-menu #menu="matMenu" class="cp-menu">
      <div class="cp-tete" (click)="$event.stopPropagation()">Colonnes affichées</div>
      @for (c of optionnelles(); track c.cle) {
        <div class="cp-ligne" (click)="$event.stopPropagation()">
          <mat-checkbox [checked]="estVisible(c.cle)" (change)="basculer(c.cle)">
            {{ c.libelle }}
          </mat-checkbox>
        </div>
      }
      <div class="cp-pied" (click)="$event.stopPropagation()">
        <button mat-button type="button" (click)="reinitialiser()">Réinitialiser</button>
      </div>
    </mat-menu>`,
  styleUrl: './column-picker.scss',
})
export class ColumnPicker {
  private prefs = inject(ColumnPrefs);

  /** Identifiant de l'écran ; sert de clé de stockage (ex. `indices`). */
  readonly titre = input.required<string>();
  readonly colonnes = input.required<ColonneDef[]>();

  /** Liste des clés visibles, dans l'ordre de déclaration. */
  readonly visibles = output<string[]>();

  private etat = signal<Record<string, boolean>>({});

  constructor() {
    // Recharge dès que l'écran ou la définition des colonnes change, et
    // publie tout de suite la sélection : sans cette première émission, le
    // tableau afficherait ses colonnes par défaut jusqu'au premier clic.
    effect(() => {
      const titre = this.titre();
      const colonnes = this.colonnes();
      /* `publier()` lit `etat` : sans `untracked`, l'effet se réabonne à son
         propre signal. Chaque bascule le réveillait, il rechargeait alors le
         stockage et écrasait le choix qui venait d'être fait — la colonne
         n'apparaissait jamais. L'effet ne doit dépendre que des entrées. */
      untracked(() => {
        this.etat.set(this.prefs.charger(titre, colonnes));
        this.publier();
      });
    });
  }

  protected optionnelles(): ColonneDef[] {
    return this.colonnes().filter(c => !c.toujours);
  }

  protected estVisible(cle: string): boolean {
    return this.etat()[cle] !== false;
  }

  protected basculer(cle: string): void {
    this.etat.update(e => ({ ...e, [cle]: e[cle] === false }));
    this.prefs.enregistrer(this.titre(), this.etat());
    this.publier();
  }

  /** Rétablit l'état d'origine de l'écran, pas « tout coché » : certaines
   *  colonnes sont volontairement repliées au premier affichage. */
  protected reinitialiser(): void {
    this.etat.set(Object.fromEntries(
      this.colonnes().map(c => [c.cle, !(c.masqueeParDefaut && !c.toujours)])));
    this.prefs.enregistrer(this.titre(), this.etat());
    this.publier();
  }

  private publier(): void {
    this.visibles.emit(
      this.colonnes().filter(c => c.toujours || this.estVisible(c.cle)).map(c => c.cle));
  }
}
