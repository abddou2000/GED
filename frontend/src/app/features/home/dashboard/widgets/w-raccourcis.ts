import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../../../core/auth.service';
import { ModulesService } from '../../../../core/modules.service';
import { Encart } from './encart';

/**
 * Un raccourci et la permission qu'il suppose (`null` : tout utilisateur doté
 * d'un rôle, seul admis au-delà de l'accueil).
 */
interface Raccourci {
  libelle: string; precision: string; icone: string; route: string; teinte: string;
  permission: string | null;
}

/**
 * Les actions de départ — filtrées sur les permissions de l'utilisateur.
 *
 * <p>La liste était figée : un utilisateur standard se voyait proposer « Créer
 * un espace » (réservé à l'Administrateur, §4.3.4), et « Rechercher par index »
 * menait au référentiel des index, écran d'administration (ANO-F-004). Un
 * accueil ne doit exposer que ce que le profil permet (§5), comme le menu : la
 * même permission décide des deux. Confort d'affichage — le serveur revérifie.</p>
 */
@Component({
  selector: 'app-w-raccourcis',
  imports: [RouterLink, MatIconModule, Encart],
  template: `
    <app-encart titre="Raccourcis" icone="nav-workflow">
      <div class="raccourcis">
        @for (r of raccourcis(); track r.route) {
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

  static readonly TOUS: readonly Raccourci[] = [
    { libelle: 'Déposer un document', precision: 'Téléverser et lancer le circuit', icone: 'nav-upload', route: '/televerser', teinte: 'd-cramoisi', permission: 'DEPOSER' },
    { libelle: 'Créer un espace', precision: 'Nouvel espace de travail', icone: 'folder-plus', route: '/espaces-de-travail', teinte: 'd-marine', permission: 'GERER_ESPACES' },
    { libelle: 'Mes validations', precision: 'Tout ce que je dois valider', icone: 'nav-mesworkflow', route: '/mes-workflow', teinte: 'd-vert', permission: null },
    /* La recherche, pas le référentiel des index (`/index`, administration). */
    { libelle: 'Rechercher un document', precision: 'Par contenu, type, dossier ou date', icone: 'search', route: '/recherche', teinte: 'd-ambre', permission: null },
  ];

  /**
   * Un compte sans rôle ne quitte pas l'accueil (§3.4.2) : aucun raccourci.
   * « Mes validations » suit aussi le module workflow : route fermée s'il est
   * inactif (T-088).
   */
  protected readonly raccourcis = computed(() => this.auth.sansRole() ? [] :
    WRaccourcis.TOUS.filter(r => (r.permission === null || this.auth.peut(r.permission))
      && (r.route !== '/mes-workflow' || this.modules.actif('workflow'))));
}
