import { Component, Input } from '@angular/core';

/** Lignes fantômes animées affichées pendant le chargement d'un tableau. */
@Component({
  selector: 'app-skeleton-table',
  template: `
    <div class="sk">
      @for (r of rowsArr; track $index) {
        <div class="sk-row">
          @for (c of colsArr; track $index) {
            <span class="sk-cell skeleton" [style.width.%]="widthFor($index)"></span>
          }
        </div>
      }
    </div>`,
  styleUrl: './skeleton-table.scss',
})
export class SkeletonTable {
  @Input() rows = 6;
  @Input() cols = 6;

  get rowsArr(): number[] { return Array.from({ length: this.rows }); }
  get colsArr(): number[] { return Array.from({ length: this.cols }); }

  /** Largeurs variées pour un rendu plus naturel. */
  widthFor(i: number): number {
    const pattern = [40, 90, 70, 85, 55, 60, 45, 80];
    return pattern[i % pattern.length];
  }
}
