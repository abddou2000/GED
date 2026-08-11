import { Component, OnDestroy, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog } from '@angular/material/dialog';
import { StatTiles } from '../../../core/stat-tiles/stat-tiles';
import { SessionService } from '../../../core/session.service';
import { DashboardPrefs } from './dashboard-prefs.service';
import { Personnaliser } from './personnaliser/personnaliser';
import { WAValider } from './widgets/w-a-valider';
import { WRaccourcis } from './widgets/w-raccourcis';
import { WDocumentsRecents } from './widgets/w-documents-recents';
import { WActivite } from './widgets/w-activite';
import { WEspaces } from './widgets/w-espaces';
import { WRepartition } from './widgets/w-repartition';
import { WDepots } from './widgets/w-depots';

/**
 * Tableau de bord d'accueil — la PREMIÈRE vue.
 *
 * <p>Il n'affiche plus une composition figée mais les encarts que l'utilisateur
 * a retenus, dans son ordre. Ce composant ne connaît donc rien de leur
 * contenu : il porte le bandeau, la grille et l'accès au réglage. Chaque encart
 * va chercher ses propres données — un écran qui centraliserait sept appels ne
 * saurait plus lequel a échoué, ni quel bloc laisser en attente.</p>
 */
@Component({
  selector: 'app-dashboard',
  imports: [
    MatIconModule, StatTiles,
    WAValider, WRaccourcis, WDocumentsRecents, WActivite, WEspaces, WRepartition, WDepots,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard implements OnDestroy {
  private session = inject(SessionService);
  private dialog = inject(MatDialog);

  protected prefs = inject(DashboardPrefs);
  protected readonly firstName = this.session.firstName;
  protected readonly todayLabel = this.capitalize(
    new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(new Date()),
  );

  constructor() {
    this.session.ensureUser();       // identité si l'on arrive directement sur /accueil
  }

  /**
   * Ouvre le réglage en tiroir sur le bord droit. Le tableau de bord reste
   * visible derrière : chaque bascule s'y répercute immédiatement, ce qu'une
   * boîte centrée — qui masquerait le résultat — interdirait.
   */
  protected personnaliser(): void {
    this.dialog.open(Personnaliser, {
      width: '440px',
      maxWidth: '96vw',
      height: '100vh',
      position: { right: '0', top: '0' },
      panelClass: 'tiroir-perso',
      autoFocus: false,
    });
  }

  /* ---------- Parallaxe du bandeau ----------
     La position de la souris est convertie en deux valeurs entre -1 et 1,
     posées en variables CSS sur la section. Ce sont les feuilles de style qui
     décident de l'amplitude de chaque plan : le composant ne connaît pas la
     scène, il ne fournit qu'une direction.

     L'écriture est différée à la prochaine image : un mouvement de souris
     déclenche des dizaines d'événements par seconde, et écrire le style à
     chacun ferait travailler le navigateur pour rien. */
  private imagePrevue = 0;

  protected surviser(evenement: MouseEvent): void {
    const cible = evenement.currentTarget as HTMLElement | null;
    if (!cible) return;
    if (this.imagePrevue) cancelAnimationFrame(this.imagePrevue);

    const { clientX, clientY } = evenement;
    this.imagePrevue = requestAnimationFrame(() => {
      const cadre = cible.getBoundingClientRect();
      const px = (clientX - cadre.left) / cadre.width * 2 - 1;
      const py = (clientY - cadre.top) / cadre.height * 2 - 1;
      cible.style.setProperty('--px', px.toFixed(3));
      cible.style.setProperty('--py', py.toFixed(3));
    });
  }

  /** Retour au repos quand la souris quitte le bandeau : sans cela la scène
      resterait figée dans sa dernière inclinaison. */
  protected quitter(evenement: MouseEvent): void {
    const cible = evenement.currentTarget as HTMLElement | null;
    if (!cible) return;
    if (this.imagePrevue) cancelAnimationFrame(this.imagePrevue);
    cible.style.setProperty('--px', '0');
    cible.style.setProperty('--py', '0');
  }

  ngOnDestroy(): void {
    // Une image programmée sur un écran qu'on quitte réveillerait Angular
    // pour rien.
    if (this.imagePrevue) cancelAnimationFrame(this.imagePrevue);
  }

  private capitalize(s: string): string {
    return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
  }
}
