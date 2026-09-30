import { Component, input } from '@angular/core';

/**
 * Marque de l'application : mot-symbole neutre « GED ».
 *
 * Le logo officiel Marchica Med est retiré pour le moment, à la demande du chef
 * de projet. Les fichiers `public/brand/marchica-med-*.png` restent dans le dépôt
 * pour pouvoir le rétablir sans les redemander au client.
 *
 *  - variant : 'onLight' (fond clair) ; 'onDark' / 'brass' (fond sombre, libellé clair)
 *  - showWord : true = pastille + libellé, false = pastille seule.
 *  - size : hauteur de la pastille (px).
 */
@Component({
  selector: 'app-brand-logo',
  template: `
    <span class="bl" [class.dark]="variant() !== 'onLight'" role="img" aria-label="GED">
      <span class="bl-pastille" [style.height.px]="size()" [style.width.px]="size()"
            [style.font-size.px]="size() * 0.34">GED</span>
      @if (showWord()) {
        <span class="bl-libelle" [style.font-size.px]="size() * 0.44">Gestion documentaire</span>
      }
    </span>`,
  styleUrl: './brand-logo.scss',
})
export class BrandLogo {
  readonly variant = input<'onLight' | 'onDark' | 'brass'>('onLight');
  readonly showWord = input(true);
  readonly size = input(32);
}
