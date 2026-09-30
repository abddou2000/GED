import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { Encart } from './encart';
import { AuthService } from '../../../../core/auth.service';
import { ModulesService } from '../../../../core/modules.service';

interface Raccourci { libelle: string; precision: string; icone: string; route: string; teinte: string; }

/** Les actions de départ. Statique par nature : ce sont des chemins, pas des données. */
@Component({
  selector: 'app-w-raccourcis',
  imports: [RouterLink, MatIconModule, Encart],
  template: `
    <app-encart titre="Raccourcis" icone="nav-workflow">
      <div class="raccourcis">
        @for (r of visibles(); track r.route) {
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
  private auth = inject(AuthService);
  private modules = inject(ModulesService);

  private readonly raccourcis: Raccourci[] = [
    { libelle: 'Déposer un document', precision: 'Téléverser et lancer le circuit', icone: 'nav-upload', route: '/televerser', teinte: 'd-cramoisi' },
    { libelle: 'Créer un espace', precision: 'Nouvel espace de travail', icone: 'folder-plus', route: '/espaces-de-travail', teinte: 'd-marine' },
    { libelle: 'Mes validations', precision: 'Tout ce que je dois valider', icone: 'nav-mesworkflow', route: '/mes-workflow', teinte: 'd-vert' },
    { libelle: 'Rechercher par index', precision: 'Retrouver un document indexé', icone: 'nav-index', route: '/index', teinte: 'd-ambre' },
  ];

  /** Seuls les raccourcis vers un écran ouvert : garde par permission (ANO-F-003), module actif (T-088). */
  protected readonly visibles = computed(() => this.raccourcis.filter(r => {
    if (r.route === '/index') return this.auth.peut('GERER_REFERENTIELS');
    if (r.route === '/mes-workflow') return this.modules.actif('workflow');
    return true;
  }));
}
