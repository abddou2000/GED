import { AfterViewInit, Component, DestroyRef, ElementRef, effect, inject, signal, viewChild } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { filter } from 'rxjs/operators';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { SessionService } from '../../core/session.service';
import { AuthService } from '../../core/auth.service';
import { BrandLogo } from '../../core/brand-logo/brand-logo';
import { SignatureService } from '../../features/signature/signature.service';
import { NotificationsService } from '../../features/notifications/notifications.service';

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
export class Shell implements AfterViewInit {
  /** La barre d'onglets : c'est en la mesurant qu'on place l'indicateur. */
  private readonly barreNav = viewChild<ElementRef<HTMLElement>>('barreNav');

  protected session = inject(SessionService);
  protected auth = inject(AuthService);
  private router = inject(Router);
  private signatures = inject(SignatureService);
  protected notifications = inject(NotificationsService);

  protected drawerOpen = signal(false);
  protected pendingCount = signal(0);
  protected pageTitle = signal('Accueil');

  ngAfterViewInit(): void {
    /* Même différé qu'après une navigation : `routerLinkActive` pose sa classe
       au cycle suivant. Mesurer ici même renverrait « aucun onglet actif », et
       l'indicateur resterait invisible jusqu'au premier changement de rubrique.

       `setTimeout` plutôt que `requestAnimationFrame` : ce dernier ne se
       déclenche pas quand l'onglet n'est pas composé — arrière-plan, fenêtre
       masquée. L'indicateur resterait alors introuvable au retour. */
    setTimeout(() => this.placerJauge());
  }

  /* La largeur d'un onglet dépend de la place disponible : sans réaction au
     redimensionnement, l'indicateur resterait sous l'ancienne position.

     L'écouteur est posé DANS le constructeur : `inject()` n'est utilisable que
     dans un contexte d'injection, et l'appeler depuis `ngAfterViewInit` lève à
     l'exécution. */
  private ecouterRedimensionnement(destroyRef: DestroyRef): void {
    const surRedimensionnement = () => this.placerJauge();
    window.addEventListener('resize', surRedimensionnement, { passive: true });
    destroyRef.onDestroy(() => window.removeEventListener('resize', surRedimensionnement));
  }

  /**
   * Place l'indicateur sous l'onglet actif.
   *
   * <p>Position et largeur passent par deux variables CSS : la feuille de style
   * garde la main sur l'animation et sur ce qui se passe en mouvement réduit.
   * Le composant ne fait que mesurer.</p>
   */
  private placerJauge(): void {
    const nav = this.barreNav()?.nativeElement;
    if (!nav) return;
    const actif = nav.querySelector<HTMLElement>('a.active');
    if (!actif) { nav.style.setProperty('--jauge-o', '0'); return; }

    // `offsetLeft` est relatif au conteneur positionné : il tient compte du
    // défilement horizontal de la barre, ce que `getBoundingClientRect` non.
    nav.style.setProperty('--jauge-x', `${actif.offsetLeft + 10}px`);
    nav.style.setProperty('--jauge-w', `${actif.offsetWidth - 20}px`);
    nav.style.setProperty('--jauge-o', '1');
  }

  /** Libellé de la page courante, par 1er segment d'URL. */
  private readonly titles: Record<string, string> = {
    'accueil': 'Accueil',
    'televerser': 'Documents déposés',
    'mes-workflow': 'Mes workflow',
    'espaces-de-travail': 'Espaces de travail',
    'regles-de-workflow': 'Règles de Workflow',
    'groupe-d-acces': "Groupe d'accès",
    'index': 'Index',
    'plan-indexation': "Plan d'indexation",
    'type-de-document': 'Type de document',
    'etiquette': 'Étiquette',
    'journal-audit': "Journal d'audit",
    'cles-api': "Clés d'API",
    'notifications': 'Notifications',
    'profil': 'Mon profil',
  };

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.ecouterRedimensionnement(destroyRef);

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
        /* Filet de sécurité : le service des signatures prévient de ce qu'il
           fait lui-même, mais la file bouge aussi par des chemins qu'il ne
           voit pas — un dépôt de document ouvre un circuit et ajoute une
           étape. Recompter à chaque navigation rattrape ces cas sans que
           chaque écran ait à y penser. */
        if (this.session.user()?.id != null) {
          this.recompterAtraiter();
          this.notifications.rafraichirCompteur();
        }
        // `routerLinkActive` pose sa classe pendant le même cycle : mesurer
        // tout de suite renverrait la position de l'onglet qu'on vient de
        // quitter. On attend la fin du cycle courant.
        setTimeout(() => this.placerJauge());
      });
    destroyRef.onDestroy(() => sub.unsubscribe());

    /* Pastille des notifications : une notification naît d'une action d'un
       autre utilisateur (circuit ouvert, accès attribué), donc sans navigation
       de celui-ci. Relevé toutes les minutes, tant qu'une session est ouverte. */
    const releve = setInterval(() => {
      if (this.session.user()?.id != null) this.notifications.rafraichirCompteur();
    }, 60_000);
    destroyRef.onDestroy(() => clearInterval(releve));

    // Identité + compteur « à traiter » (badge du menu)
    this.session.ensureUser();
    effect(() => {
      // L'appel ne porte plus d'identifiant : le serveur déduit l'employé du
      // jeton. On dépend tout de même de la session pour ne compter qu'une
      // fois l'identité résolue, et pour recompter au changement de compte.
      const u = this.session.user();
      // Recompte aussi à chaque signature accordée ou refusée : sans cette
      // dépendance, le badge restait figé sur sa valeur de chargement — on
      // signait, la liste passait à 31 et le badge annonçait toujours 32.
      this.signatures.revision();
      if (u?.id == null) return;
      this.recompterAtraiter();
      this.notifications.rafraichirCompteur();
    });
  }

  /** Recompte les étapes réellement à traiter. Silencieux en cas d'échec :
   *  un badge périmé vaut mieux qu'une erreur en travers de la navigation. */
  private recompterAtraiter(): void {
    if (this.auth.sansRole()) return;   // aucun circuit accessible sans rôle
    this.signatures.pending().subscribe({
      next: l => this.pendingCount.set(l.length),
      error: () => { /* silencieux */ },
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
