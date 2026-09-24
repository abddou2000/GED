import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Encart } from './encart';
import { PartType, StatsService } from '../../../../core/stats.service';

interface Barre extends PartType { part: number; largeur: number; teinte: string; }

/**
 * Répartition des documents par type.
 *
 * <p>Des barres horizontales, pas un camembert : on compare ici des longueurs,
 * ce que l'œil fait bien, plutôt que des angles, ce qu'il fait mal. Et le
 * libellé tient sur la même ligne que sa valeur, sans légende à recouper.</p>
 */
@Component({
  selector: 'app-w-repartition',
  imports: [Encart],
  template: `
    <app-encart titre="Répartition par type" icone="chart" [chargement]="chargement()">
      @if (barres().length) {
        <div class="rep">
          @for (b of barres(); track b.label) {
            <div class="part">
              <div class="part-tete">
                <span class="part-nom">{{ b.label }}</span>
                <span class="part-val">{{ b.count }}<span class="part-pc">{{ b.part }} %</span></span>
              </div>
              <div class="rail"><span class="jauge" [class]="b.teinte" [style.width.%]="b.largeur"></span></div>
            </div>
          }
          @if (reste() > 0) {
            <p class="rep-reste">+ {{ reste() }} autre{{ reste() > 1 ? 's' : '' }} type{{ reste() > 1 ? 's' : '' }}</p>
          }
        </div>
      } @else {
        <div class="vide">
          <span class="vide-titre">Aucun document</span>
          <span class="vide-sous">La répartition apparaîtra dès le premier dépôt.</span>
        </div>
      }
    </app-encart>`,
  styleUrl: './w-repartition.scss',
})
export class WRepartition implements OnInit {
  private service = inject(StatsService);
  private static readonly VISIBLES = 5;
  private static readonly TEINTES = ['t-1', 't-2', 't-3', 't-4', 't-5'];

  protected readonly chargement = signal(true);
  private readonly parts = signal<PartType[]>([]);

  protected readonly barres = computed<Barre[]>(() => {
    const toutes = this.parts();
    const total = toutes.reduce((s, p) => s + p.count, 0);
    if (!total) return [];

    /* Deux échelles distinctes, et c'est volontaire : le POURCENTAGE se lit sur
       le total réel, la LARGEUR se cale sur la plus grosse part. Une barre
       proportionnelle au total serait un trait de 4 % qu'on ne verrait pas. */
    const sommet = toutes[0].count;
    return toutes.slice(0, WRepartition.VISIBLES).map((p, i) => ({
      ...p,
      part: Math.round((p.count / total) * 100),
      largeur: Math.max(4, Math.round((p.count / sommet) * 100)),
      teinte: WRepartition.TEINTES[i % WRepartition.TEINTES.length],
    }));
  });

  /** Types non détaillés : annoncés plutôt que passés sous silence. */
  protected readonly reste = computed(() => Math.max(0, this.parts().length - WRepartition.VISIBLES));

  ngOnInit(): void {
    this.service.parType().subscribe({
      next: p => { this.parts.set(p); this.chargement.set(false); },
      error: () => this.chargement.set(false),
    });
  }
}
