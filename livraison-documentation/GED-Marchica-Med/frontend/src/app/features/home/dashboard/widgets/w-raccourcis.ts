import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { Encart } from './encart';

interface Raccourci { libelle: string; precision: string; icone: string; route: string; teinte: string; }

/** Les actions de départ. Statique par nature : ce sont des chemins, pas des données. */
@Component({
  selector: 'app-w-raccourcis',
  imports: [RouterLink, MatIconModule, Encart],
  template: `
    <app-encart titre="Raccourcis" icone="nav-workflow">
      <div class="raccourcis">
        @for (r of raccourcis; track r.route) {
          <a class="rac" [routerLink]="r.route">
            <span class="pastille" [class]="r.teinte"><mat-icon [svgIcon]="r.icone"></mat-icon></span>
            <span class="txt">
              <span class="nom">{{ r.libelle }}</span>
              <span class="meta">{{ r.precision }}</span>
            </span>
            <mat-icon class="fleche" svgIcon="chevron-right"></mat-icon>
          </a>
        }
      </div>
    </app-encart>`,
  styleUrl: './w-raccourcis.scss',
})
export class WRaccourcis {
  protected readonly raccourcis: Raccourci[] = [
    { libelle: 'Déposer un document', precision: 'Téléverser et lancer le circuit', icone: 'nav-upload', route: '/televerser', teinte: 'd-cramoisi' },
    { libelle: 'Créer un espace', precision: 'Nouvel espace de travail', icone: 'folder-plus', route: '/espaces-de-travail', teinte: 'd-marine' },
    { libelle: 'Mes workflow', precision: 'Tout ce que je dois valider', icone: 'nav-mesworkflow', route: '/mes-workflow', teinte: 'd-vert' },
    { libelle: 'Rechercher par index', precision: 'Retrouver un document indexé', icone: 'nav-index', route: '/index', teinte: 'd-ambre' },
  ];
}
