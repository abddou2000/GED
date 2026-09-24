import { Component, input } from '@angular/core';

/**
 * Squelette de tableau (shimmer) affiché pendant le chargement d'une liste, à la
 * place d'un tableau vide — « visibilité de l'état du système » (Nielsen).
 */
@Component({
  selector: 'app-skeleton-table',
  template: `
    <div class="sk" aria-hidden="true">
      <div class="sk-row sk-head">
        @for (c of colArr(); track $index) { <span class="sk-cell"><span class="sk-bar"></span></span> }
      </div>
      @for (r of rowArr(); track $index) {
        <div class="sk-row">
          @for (c of colArr(); track $index) {
            <span class="sk-cell"><span class="sk-bar" [style.width.%]="widthFor($index)"></span></span>
          }
        </div>
      }
    </div>`,
  styleUrl: './skeleton-table.scss',
})
export class SkeletonTable {
  readonly rows = input(6);
  readonly cols = input(5);

  protected rowArr = () => Array.from({ length: this.rows() });
  protected colArr = () => Array.from({ length: this.cols() });

  private readonly widths = [70, 52, 80, 46, 62, 50, 68];
  protected widthFor(i: number): number { return this.widths[i % this.widths.length]; }
}
