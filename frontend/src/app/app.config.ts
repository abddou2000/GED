import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideAppInitializer, inject } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';

import { routes } from './app.routes';
import { GED_ICONS } from './core/ged-icons';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withFetch()),
    provideAnimationsAsync(),
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
