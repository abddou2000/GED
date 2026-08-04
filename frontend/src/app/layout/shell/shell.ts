import { Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { filter } from 'rxjs/operators';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { SessionService } from '../../core/session.service';
import { BrandLogo } from '../../core/brand-logo/brand-logo';
import { SignatureService } from '../../features/signature/signature.service';

/**
 * Coque applicative — disposition « topnav » (style UBold) :
 *  - barre supérieure : marque, action principale, compte ;
 *  - menu horizontal collant (rubriques à plat) ;
 *  - contenu pleine largeur.
 * Sous 992px, le menu se replie en liste déroulante (bouton hamburger).
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatIconModule, MatMenuModule, BrandLogo],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  protected session = inject(SessionService);
  private router = inject(Router);
  private signatures = inject(SignatureService);

  protected drawerOpen = signal(false);
  protected pendingCount = signal(0);
  protected pageTitle = signal('Accueil');

  /** Libellé de la page courante, par 1er segment d'URL. */
  private readonly titles: Record<string, string> = {
    'accueil': 'Accueil',
    'televerser': 'Documents déposés',
    'recherche': 'Rechercher un document',
    'mes-workflow': 'Mes workflow',
    'espaces-de-travail': 'Espaces de travail',
    'regles-de-workflow': 'Règles de Workflow',
    'groupe-d-acces': "Groupe d'accès",
    'index': 'Index',
    'plan-indexation': "Plan d'indexation",
    'type-de-document': 'Type de document',
    'etiquette': 'Étiquette',
  };

  constructor() {
    const destroyRef = inject(DestroyRef);

    const syncTitle = () => {
      const seg = this.router.url.split(/[/?#]/).filter(Boolean)[0] ?? 'accueil';
      this.pageTitle.set(this.titles[seg] ?? 'Accueil');
    };
    syncTitle();

    const sub = this.router.events
      .pipe(filter(e => e instanceof NavigationEnd))
      .subscribe(() => {
        syncTitle();
        this.drawerOpen.set(false);   // referme le menu déroulant (mobile)
      });
    destroyRef.onDestroy(() => sub.unsubscribe());

    // Identité + compteur « à traiter » (badge du menu)
    this.session.ensureUser();
    effect(() => {
      const u = this.session.user();
      if (u?.id == null) return;
      this.signatures.pending(u.id).subscribe({
        next: l => this.pendingCount.set(l.length),
        error: () => { /* silencieux */ },
      });
    });
  }

  toggleDrawer(): void { this.drawerOpen.update(o => !o); }
  closeDrawer(): void { this.drawerOpen.set(false); }

  signOut(): void {
    this.closeDrawer();
    this.session.signOut();
    this.router.navigateByUrl('/login');
  }
}
