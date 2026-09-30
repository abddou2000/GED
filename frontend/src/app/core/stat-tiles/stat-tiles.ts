import { Component, OnInit, inject, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { StatsService, StatsOverview } from '../stats.service';
import { AuthService } from '../auth.service';

/** Rangée de tuiles d'indicateurs (en-tête « tableau de bord ») — réutilisable. */
@Component({
  selector: 'app-stat-tiles',
  imports: [MatIconModule, RouterLink],
  template: `
    <div class="tiles">
      @for (t of tiles(); track t.key) {
        <a class="tile" [class]="t.cls" [routerLink]="t.route">
          <span class="ico"><mat-icon [svgIcon]="t.icon"></mat-icon></span>
          <span class="t-txt">
            <span class="n">{{ t.value }}</span>
            <span class="lb">{{ t.label }}</span>
          </span>
          <mat-icon class="t-go" svgIcon="chevron-right"></mat-icon>
        </a>
      }
    </div>`,
  styleUrl: './stat-tiles.scss',
})
export class StatTiles implements OnInit {
  private service = inject(StatsService);
  private auth = inject(AuthService);
  private data = signal<StatsOverview>({ workspaces: 0, documents: 0, pendingSignatures: 0, accessGroups: 0 });

  tiles = () => {
    const d = this.data();
    /* Les précisions de pied (« espaces de travail » sous « Dossiers actifs »,
       « déposés » sous « Documents ») répétaient le libellé sans rien ajouter :
       trois niveaux de tuile pour un seul chiffre. Chaque tuile devient en
       revanche un LIEN vers son écran — un indicateur qu'on ne peut pas ouvrir
       est une impasse. */
    const tuiles = [
      { key: 'ws', label: 'Dossiers actifs', value: d.workspaces, icon: 'nav-workspaces', cls: 'i-marine', route: '/espaces-de-travail' },
      { key: 'doc', label: 'Documents', value: d.documents, icon: 'nav-type', cls: 'i-vert', route: '/televerser' },
      // Ambre = sémantique « en attente » partout dans l'application.
      { key: 'pend', label: 'En attente de signature', value: d.pendingSignatures, icon: 'nav-mesworkflow', cls: 'i-ambre', route: '/mes-workflow' },
      { key: 'grp', label: "Groupes d'accès", value: d.accessGroups, icon: 'nav-groups', cls: 'i-cramoisi', route: '/groupe-d-acces' },
    ];
    /* Les groupes d'accès relèvent de l'administration des droits : la tuile
       suit la même permission que l'entrée du menu (ANO-F-004, §5 « sans
       exposer de fonctionnalités hors de son périmètre »). */
    return this.auth.peut('GERER_ROLES_HABILITATIONS') ? tuiles : tuiles.filter(t => t.key !== 'grp');
  };

  ngOnInit(): void {
    this.service.overview().subscribe(o => this.data.set(o));
  }
}
