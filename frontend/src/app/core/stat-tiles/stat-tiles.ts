import { Component, OnInit, inject, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { StatsService, StatsOverview } from '../stats.service';

/** Rangée de tuiles d'indicateurs (en-tête « tableau de bord ») — réutilisable. */
@Component({
  selector: 'app-stat-tiles',
  imports: [MatIconModule],
  template: `
    <div class="tiles">
      @for (t of tiles(); track t.key) {
        <div class="tile">
          <div class="t-head"><span class="lb">{{ t.label }}</span></div>
          <div class="t-body">
            <span class="ico" [class]="t.cls"><mat-icon [svgIcon]="t.icon"></mat-icon></span>
            <span class="n">{{ t.value }}</span>
          </div>
          <div class="t-foot">
            <span class="dot" [class]="t.cls"></span>
            <span class="d">{{ t.hint }}</span>
          </div>
        </div>
      }
    </div>`,
  styleUrl: './stat-tiles.scss',
})
export class StatTiles implements OnInit {
  private service = inject(StatsService);
  private data = signal<StatsOverview>({ workspaces: 0, documents: 0, pendingSignatures: 0, accessGroups: 0 });

  tiles = () => {
    const d = this.data();
    return [
      { key: 'ws', label: 'Dossiers actifs', value: d.workspaces, icon: 'nav-workspaces', cls: 'i-marine', hint: 'espaces de travail' },
      { key: 'doc', label: 'Documents', value: d.documents, icon: 'nav-type', cls: 'i-vert', hint: 'déposés' },
      // Ambre = sémantique « en attente » partout dans l'app ; l'or reste
      // l'accent de marque neutre, réservé aux tuiles sans urgence.
      { key: 'pend', label: 'En attente de signature', value: d.pendingSignatures, icon: 'nav-mesworkflow', cls: 'i-ambre', hint: 'à traiter' },
      { key: 'grp', label: "Groupes d'accès", value: d.accessGroups, icon: 'nav-groups', cls: 'i-cramoisi', hint: 'définis' },
    ];
  };

  ngOnInit(): void {
    this.service.overview().subscribe(o => this.data.set(o));
  }
}
