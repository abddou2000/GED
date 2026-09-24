import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';

/**
 * Coque commune à tous les encarts du tableau de bord : bandeau de titre,
 * compteur, lien « tout voir », corps.
 *
 * <p>Elle existe pour une raison simple : sept encarts qui redessinent chacun
 * leur en-tête finissent par diverger — un filet ici, une graisse là — et le
 * tableau de bord se lit alors comme sept écrans juxtaposés. Le contenu, lui,
 * reste propre à chaque encart et arrive par projection.</p>
 */
@Component({
  selector: 'app-encart',
  imports: [RouterLink, MatIconModule],
  template: `
    <section class="card encart">
      <header class="encart-tete">
        <mat-icon [svgIcon]="icone()"></mat-icon>
        <h2>{{ titre() }}</h2>
        @if (compteur() !== null) { <span class="compte">{{ compteur() }}</span> }
        @if (lien()) {
          <a class="tout-voir" [routerLink]="lien()">{{ libelleLien() }}</a>
        }
      </header>

      <div class="encart-corps">
        @if (chargement()) {
          <!-- Une ligne de texte plutôt qu'un tourniquet : l'encart est petit,
               et un tourniquet dans chacun des sept ferait clignoter la page. -->
          <p class="encart-attente">Chargement…</p>
        } @else {
          <ng-content />
        }
      </div>
    </section>`,
  styleUrl: './encart.scss',
})
export class Encart {
  titre = input.required<string>();
  icone = input.required<string>();
  /** Compteur affiché près du titre ; `null` pour n'en afficher aucun. */
  compteur = input<number | null>(null);
  lien = input<string | null>(null);
  libelleLien = input('Tout voir');
  chargement = input(false);
}
