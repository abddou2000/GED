import { Component, computed, inject } from '@angular/core';
import { MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { DashboardPrefs } from '../dashboard-prefs.service';
import { CATALOGUE, DefinitionWidget } from '../widgets';

interface Rang extends DefinitionWidget {
  visible: boolean;
  /** Position dans la disposition ; `-1` si l'encart est masqué. */
  place: number;
  premier: boolean;
  dernier: boolean;
}

/**
 * Panneau « Personnaliser » : quels encarts afficher, et dans quel ordre.
 *
 * <p>Les changements s'appliquent IMMÉDIATEMENT au tableau de bord derrière le
 * panneau. Pas de bouton « Enregistrer » : le résultat est visible pendant le
 * réglage, ce qu'aucune liste de cases à cocher validée à l'aveugle ne
 * permet. Le panneau se ferme, il ne se valide pas.</p>
 */
@Component({
  selector: 'app-personnaliser',
  imports: [MatIconModule],
  template: `
    <div class="perso">
      <header class="perso-tete">
        <mat-icon svgIcon="sliders"></mat-icon>
        <div class="perso-titre">
          <h2>Personnaliser</h2>
          <p>Choisissez les encarts et leur ordre.</p>
        </div>
        <button type="button" class="ico-btn" (click)="fermer()" aria-label="Fermer">
          <mat-icon svgIcon="close"></mat-icon>
        </button>
      </header>

      <ul class="perso-liste">
        @for (r of rangs(); track r.cle) {
          <li class="perso-item" [class.off]="!r.visible">
            <span class="perso-ic"><mat-icon [svgIcon]="r.icone"></mat-icon></span>

            <span class="perso-txt">
              <span class="perso-nom">
                {{ r.titre }}
                @if (r.fixe) { <span class="perso-fixe">toujours affiché</span> }
              </span>
              <span class="perso-resume">{{ r.resume }}</span>
            </span>

            <span class="perso-cmd">
              @if (!r.fixe) {
                <button type="button" class="ico-btn" [disabled]="!r.visible || r.premier"
                        (click)="prefs.deplacer(r.cle, -1)"
                        [attr.aria-label]="'Remonter ' + r.titre">
                  <mat-icon svgIcon="arrow-up"></mat-icon>
                </button>
                <button type="button" class="ico-btn" [disabled]="!r.visible || r.dernier"
                        (click)="prefs.deplacer(r.cle, 1)"
                        [attr.aria-label]="'Descendre ' + r.titre">
                  <mat-icon svgIcon="arrow-down"></mat-icon>
                </button>
                <button type="button" class="bascule" role="switch"
                        [attr.aria-checked]="r.visible"
                        [attr.aria-label]="(r.visible ? 'Masquer ' : 'Afficher ') + r.titre"
                        (click)="prefs.basculer(r.cle)">
                  <mat-icon [svgIcon]="r.visible ? 'eye' : 'eye-off'"></mat-icon>
                </button>
              }
            </span>
          </li>
        }
      </ul>

      <footer class="perso-pied">
        <button type="button" class="lien-reset" [disabled]="!prefs.personnalise()"
                (click)="prefs.reinitialiser()">
          <mat-icon svgIcon="reset"></mat-icon> Disposition par défaut
        </button>
        <button type="button" class="valider" (click)="fermer()">Terminé</button>
      </footer>
    </div>`,
  styleUrl: './personnaliser.scss',
})
export class Personnaliser {
  protected prefs = inject(DashboardPrefs);
  private ref = inject(MatDialogRef<Personnaliser>);

  /**
   * Le panneau liste le CATALOGUE au complet, dans son ordre de référence — pas
   * la disposition courante. Une liste qui se réorganise à chaque clic fait
   * perdre de vue l'encart qu'on manipule.
   */
  protected readonly rangs = computed<Rang[]>(() => {
    const disposition = this.prefs.disposition();
    const mobiles = disposition.filter(c => !CATALOGUE.find(d => d.cle === c)?.fixe);
    return CATALOGUE.map(d => {
      const place = mobiles.indexOf(d.cle);
      return {
        ...d,
        visible: disposition.includes(d.cle),
        place,
        premier: place === 0,
        dernier: place === mobiles.length - 1,
      };
    });
  });

  protected fermer(): void {
    this.ref.close();
  }
}
