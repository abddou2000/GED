import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs/operators';
import { CodeModule, ModulesService } from './modules.service';

/**
 * Écran d'un module métier (T-088) : fermé, retour à l'accueil, quand le module
 * est désactivé sur l'environnement. Confort seulement — le serveur répond de
 * toute façon 404 `MODULE_INACTIF` sur les routes du module.
 */
export function moduleGuard(code: CodeModule): CanActivateFn {
  return () => {
    const modules = inject(ModulesService);
    const router = inject(Router);
    return modules.charger().pipe(
      map(() => modules.actif(code) ? true : router.createUrlTree(['/accueil'])),
    );
  };
}
