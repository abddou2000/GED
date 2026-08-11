import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Encart } from './encart';
import { PointDepot, StatsService } from '../../../../core/stats.service';

interface Colonne extends PointDepot { hauteur: number; jour: string; }

/**
 * Volume de dépôts jour par jour sur le dernier mois.
 *
 * <p>Un histogramme en SVG plutôt qu'une bibliothèque de graphiques : trente
 * rectangles ne justifient pas 200 ko de dépendance, et le dessiner nous-mêmes
 * laisse le contrôle des couleurs, de l'accessibilité et du thème.</p>
 */
@Component({
  selector: 'app-w-depots',
  imports: [Encart],
  template: `
    <app-encart titre="Dépôts sur 30 jours" icone="trending" [chargement]="chargement()">
      <div class="depots">
        <div class="resume">
          <span class="somme">{{ total() }}</span>
          <span class="somme-lb">document{{ total() > 1 ? 's' : '' }} déposé{{ total() > 1 ? 's' : '' }}</span>
          <span class="pointe">Journée la plus chargée : {{ pointe() }}</span>
        </div>

        <div class="graphe" role="img" [attr.aria-label]="resumeAccessible()">
          @for (c of colonnes(); track c.date) {
            <span class="colonne" [title]="c.jour + ' — ' + c.count">
              <span class="fut" [style.height.%]="c.hauteur"></span>
            </span>
          }
        </div>

        <div class="axe">
          <span>{{ bornes().debut }}</span>
          <span>{{ bornes().fin }}</span>
        </div>
      </div>
    </app-encart>`,
  styleUrl: './w-depots.scss',
})
export class WDepots implements OnInit {
  private service = inject(StatsService);
  private static readonly JJMM = new Intl.DateTimeFormat('fr-FR', { day: '2-digit', month: 'short' });

  protected readonly chargement = signal(true);
  private readonly points = signal<PointDepot[]>([]);

  protected readonly total = computed(() => this.points().reduce((s, p) => s + p.count, 0));
  protected readonly pointe = computed(() => Math.max(0, ...this.points().map(p => p.count)));

  protected readonly colonnes = computed<Colonne[]>(() => {
    const sommet = this.pointe();
    return this.points().map(p => ({
      ...p,
      jour: this.libelle(p.date),
      /* Un jour sans dépôt garde une amorce de 3 % : une colonne de hauteur
         nulle disparaît, et l'on ne distingue plus « zéro » de « pas de
         donnée ». */
      hauteur: sommet ? Math.max(3, Math.round((p.count / sommet) * 100)) : 3,
    }));
  });

  protected readonly bornes = computed(() => {
    const p = this.points();
    return p.length
      ? { debut: this.libelle(p[0].date), fin: this.libelle(p[p.length - 1].date) }
      : { debut: '', fin: '' };
  });

  /** Le graphe est une image pour un lecteur d'écran : il lui faut sa phrase. */
  protected readonly resumeAccessible = computed(
    () => `${this.total()} documents déposés entre le ${this.bornes().debut} et le ${this.bornes().fin}.`);

  ngOnInit(): void {
    this.service.depots(30).subscribe({
      next: p => { this.points.set(p); this.chargement.set(false); },
      error: () => this.chargement.set(false),
    });
  }

  private libelle(iso: string): string {
    const d = new Date(`${iso}T00:00:00`);
    return Number.isNaN(d.getTime()) ? iso : WDepots.JJMM.format(d);
  }
}
