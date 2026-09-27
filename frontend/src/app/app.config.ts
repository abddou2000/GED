import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners, provideAppInitializer, inject } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { MAT_DATE_LOCALE, provideNativeDateAdapter } from '@angular/material/core';
import { provideRouter, withHashLocation } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors, HttpClient } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { firstValueFrom, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { MatPaginatorIntl } from '@angular/material/paginator';

import { routes } from './app.routes';
import { PaginateurFr } from './core/paginateur-fr';
import { GED_ICONS } from './core/ged-icons';
import { MODE_DEMO } from './core/api';
import { demoInterceptor } from './core/demo.interceptor';
import { authInterceptor } from './core/auth.interceptor';
import { problemeInterceptor } from './core/probleme';

// Formats de date et libellés du calendrier en français.
registerLocaleData(localeFr);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    /**
     * Routage par ancre (`/#/accueil`).
     *
     * <p>Avec des URL classiques, ouvrir `/accueil` directement demande à
     * l'hébergeur de renvoyer `index.html` pour toute route inconnue. Sans cette
     * réécriture — impossible sur bien des hébergements statiques — chaque
     * rechargement finirait en 404. L'ancre supprime la contrainte : le serveur
     * ne voit jamais que `/index.html`.
     */
    provideRouter(routes, withHashLocation()),
    /* L'ordre compte : `problemeInterceptor` normalise les erreurs problem+json
       de l'API (DAT 5.3.2) pour tous les écrans ; `authInterceptor` pose le
       jeton et traite le 401 ;
       `demoInterceptor` court-circuite les appels quand il n'y a pas de
       backend : il doit rester en dernier pour ne rien intercepter
       tant qu'un vrai serveur répond. */
    provideHttpClient(withFetch(), withInterceptors([problemeInterceptor, authInterceptor, demoInterceptor])),
    provideAnimationsAsync(),

    /**
     * Sélecteur de date Material plutôt que le champ natif du navigateur :
     * ce dernier impose son propre rendu (« jj/mm/aaaa » grisé, icône système),
     * qui jure avec le reste des formulaires et change d'un navigateur à l'autre.
     */
    provideNativeDateAdapter(),
    { provide: LOCALE_ID, useValue: 'fr-FR' },
    { provide: MAT_DATE_LOCALE, useValue: 'fr-FR' },
    { provide: MatPaginatorIntl, useClass: PaginateurFr },

    /**
     * Mode de fonctionnement, lu avant le premier écran.
     *
     * <p>Le drapeau vit dans un fichier servi à côté du bundle, pas dedans :
     * basculer une instance déployée entre démonstration et backend réel ne
     * demande alors aucune recompilation.
     *
     * <p>Fichier illisible → mode démonstration. Ce paquet est conçu pour être
     * hébergé seul ; sans ce fichier il n'y a de toute façon aucun backend à
     * joindre, et des écrans vides seraient moins parlants qu'un jeu d'essai.
     * Une instance branchée sur une vraie API, elle, sert bien ce fichier.
     */
    provideAppInitializer(() => {
      const http = inject(HttpClient);
      return firstValueFrom(
        http.get<{ demo?: boolean }>('assets/config.json').pipe(catchError(() => of({ demo: true }))),
      ).then(cfg => { MODE_DEMO.actif = cfg?.demo !== false; });
    }),

    // Enregistre les icônes Lucide (trait fin) dans MatIconRegistry → <mat-icon svgIcon="…">
    provideAppInitializer(() => {
      const registry = inject(MatIconRegistry);
      const sanitizer = inject(DomSanitizer);
      for (const { name, svg } of GED_ICONS) {
        registry.addSvgIconLiteral(name, sanitizer.bypassSecurityTrustHtml(svg));
      }
    }),
  ]
};
