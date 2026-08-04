import { Component, computed, input } from '@angular/core';

/**
 * Logotype Marchica Med — image du logo officiel client (aucun tracé maison).
 *
 * Deux fichiers, découpés depuis le logo fourni :
 *  - `marchica-med-full.png`  : logotype complet (emblème + « marchica med »)
 *  - `marchica-med-mark.png`  : emblème seul, pour les usages compacts
 *
 * Le logo est peint en marine + sable : illisible tel quel sur un fond sombre.
 * Sur variant `onDark` / `brass`, il est donc posé sur une plaque claire
 * plutôt que recoloré — on ne modifie jamais les couleurs de la marque.
 *
 *  - variant : 'onLight' (fond clair : logo posé directement)
 *              'onDark' / 'brass' (fond sombre : logo sur plaque claire)
 *  - showWord : true = logotype complet, false = emblème seul.
 *  - size : hauteur cible (px). L'emblème est carré ; le logotype complet
 *           garde son ratio naturel (largeur ≈ 1,83 × la hauteur).
 */
@Component({
  selector: 'app-brand-logo',
  template: `
    <span class="bl" [class.plated]="variant() !== 'onLight'">
      <img class="bl-mark" [class.bl-full]="showWord()" [class.bl-emblem]="!showWord()"
           [src]="src()" [style.height.px]="size()"
           [style.width.px]="showWord() ? null : size()"
           alt="Marchica Med" />
    </span>`,
  styleUrl: './brand-logo.scss',
})
export class BrandLogo {
  readonly variant = input<'onLight' | 'onDark' | 'brass'>('onLight');
  readonly showWord = input(true);
  readonly size = input(32);

  protected readonly src = computed(() =>
    this.showWord() ? '/brand/marchica-med-full.png' : '/brand/marchica-med-mark.png');
}
