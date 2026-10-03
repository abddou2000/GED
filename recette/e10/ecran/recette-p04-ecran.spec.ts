/*
 * Recette qa, tour 4 — P-04 (DAT §3.4.2, revue client D1) À L'ÉCRAN, avec un compte RÉEL de l'annuaire et
 * l'application RÉELLE : l'application Angular complète (appConfig : intercepteurs, routes, gardes, initialiseurs)
 * est rendue dans jsdom ; l'écran de connexion est rempli et soumis ; les appels HTTP partent vers l'instance
 * lancée (GED_URL, défaut http://localhost:18084) par le fetch de Node. Aucun bouchon de l'API.
 *
 * Ce poste n'a pas de navigateur (téléchargement de Chrome refusé par le mandataire) : jsdom tient lieu d'écran
 * (DOM et routage réels, sans rendu graphique).
 *
 * Contrôles : un compte provisionné SANS rôle (qanouveau1 de recette/donnees/annuaire-recette.ldif) arrive sur
 * l'accueil vide (message d'attente d'un rôle), la coque ne propose que l'accueil et la déconnexion (ni « Mes
 * exports », ni cloche, ni « Mon profil »), aucun appel à /modules ni à /notifications/compteur, toute autre route
 * ramène à l'accueil ; puis un compte AVEC rôle (sbennani) retrouve les menus.
 *
 * Usage (ce n'est pas un test du dépôt : copié le temps de l'exécution, puis retiré) :
 *   cp recette/e10/ecran/recette-p04-ecran.spec.ts frontend/src/app/zz-recette-p04-ecran.spec.ts
 *   cd frontend && GED_URL=http://localhost:18084 npx ng test --watch=false --include=src/app/zz-recette-p04-ecran.spec.ts
 *   rm src/app/zz-recette-p04-ecran.spec.ts
 */
import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { appConfig } from './app.config';
import { App } from './app';

const GED = (globalThis as { process?: { env?: Record<string, string> } }).process?.env?.['GED_URL'] ?? 'http://localhost:18084';
const MDP = (globalThis as { process?: { env?: Record<string, string> } }).process?.env?.['GED_RECETTE_MOT_DE_PASSE'] ?? 'dev-local-only';
const fetchNode = globalThis.fetch.bind(globalThis);
const appels: string[] = [];

/** Trace de recette sur la sortie standard du processus (la console des tests est absorbée par le lanceur). */
function trace(ligne: string): void {
  const p = (globalThis as { process?: { stdout?: { write(s: string): void } } }).process;
  if (p?.stdout) p.stdout.write(ligne + '\n'); else console.log(ligne);
}

function attendre(ms: number): Promise<void> {
  return new Promise(r => setTimeout(r, ms));
}

async function stabiliser(): Promise<void> {
  for (let i = 0; i < 20; i++) {
    await attendre(100);
    await TestBed.inject(ApplicationRef).whenStable();
  }
}

function saisir(racine: HTMLElement, selecteur: string, valeur: string): void {
  const champ = racine.querySelector(selecteur) as HTMLInputElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event('input', { bubbles: true }));
}

async function connecter(racine: HTMLElement, identifiant: string): Promise<void> {
  await TestBed.inject(Router).navigateByUrl('/login');
  await stabiliser();
  saisir(racine, 'input[formControlName="identifiant"]', identifiant);
  saisir(racine, 'input[formControlName="password"]', MDP);
  (racine.querySelector('button[type="submit"]') as HTMLButtonElement).click();
  await stabiliser();
}

function menus(racine: HTMLElement): string[] {
  return Array.from(racine.querySelectorAll('nav.topnav a')).map(a => (a.textContent ?? '').trim().replace(/\s+/g, ' '));
}

describe('Recette qa tour 4 — P-04 à l\'écran, compte réel, application réelle', () => {
  beforeAll(() => {
    // Chemins relatifs de l'application (/api/v1, assets/config.json) servis comme derrière NGINX.
    globalThis.fetch = (async (entree: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof entree === 'string' ? entree : entree instanceof URL ? entree.href : entree.url;
      if (url.includes('assets/config.json')) {
        return new Response('{"demo":false}', { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      const absolue = url.startsWith('/') ? GED + url : url.replace(/^https?:\/\/[^/]+/, GED);
      appels.push(new URL(absolue).pathname);
      return fetchNode(absolue, init);
    }) as typeof fetch;
  });

  afterAll(() => {
    globalThis.fetch = fetchNode;
  });

  it('compte sans rôle : accueil vide, coque réduite, aucun appel refusé ; compte avec rôle : menus rendus', async () => {
    TestBed.configureTestingModule({ providers: [...appConfig.providers] });
    const f = TestBed.createComponent(App);
    const racine = f.nativeElement as HTMLElement;
    document.body.appendChild(racine);

    appels.length = 0;
    await connecter(racine, 'qanouveau1');
    const routeur = TestBed.inject(Router);
    const urlApres = routeur.url;
    const vide = racine.querySelector('.accueil-vide');
    const nav = menus(racine);
    const cloche = racine.querySelector('a.cloche');
    const appelsSansRole = [...appels];
    // Toute autre route ramène à l'accueil (roleGuard).
    await routeur.navigateByUrl('/espaces-de-travail');
    await stabiliser();
    const urlGardee = routeur.url;
    // Menu du compte : « Mon profil » absent, « Se déconnecter » présent.
    (racine.querySelector('button.acc-btn') as HTMLButtonElement).click();
    await stabiliser();
    const carte = (document.querySelector('.carte-compte')?.textContent ?? '').replace(/\s+/g, ' ');

    trace('RESULTAT-P04|sans rôle|url ' + urlApres + ' | accueil vide : ' + (vide?.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 160)
      + ' | menus ' + JSON.stringify(nav) + ' | cloche ' + (cloche !== null) + ' | carte « ' + carte.trim() + ' » | après /espaces-de-travail : ' + urlGardee
      + ' | appels ' + JSON.stringify(appelsSansRole));
    expect(urlApres).toBe('/accueil');
    expect(vide).not.toBeNull();
    expect(vide!.textContent).toContain('aucun rôle ne vous est encore attribué');
    expect(nav).toEqual(['Accueil']);
    expect(cloche).toBeNull();
    expect(carte).toContain('Se déconnecter');
    expect(carte).not.toContain('Mon profil');
    expect(urlGardee).toBe('/accueil');
    expect(appelsSansRole.filter(a => a.endsWith('/modules') || a.includes('/notifications'))).toEqual([]);

    // Déconnexion, puis compte avec rôle : l'accueil habituel et les menus reviennent.
    const sortie = Array.from(document.querySelectorAll('.carte-compte button')).find(b => (b.textContent ?? '').includes('Se déconnecter')) as HTMLButtonElement;
    sortie.click();
    await stabiliser();
    const urlSortie = routeur.url;
    appels.length = 0;
    await connecter(racine, 'sbennani');
    const navAdmin = menus(racine);
    trace('RESULTAT-P04|avec rôle|déconnexion → ' + urlSortie + ' | url ' + routeur.url + ' | accueil vide ' + (racine.querySelector('.accueil-vide') !== null)
      + ' | menus ' + JSON.stringify(navAdmin) + ' | cloche ' + (racine.querySelector('a.cloche') !== null) + ' | appels ' + JSON.stringify([...new Set(appels)]));
    expect(urlSortie.startsWith('/login')).toBe(true);
    expect(racine.querySelector('.accueil-vide')).toBeNull();
    expect(navAdmin.length).toBeGreaterThan(5);
    expect(navAdmin).toContain('Espaces de travail');
    expect(appels.some(a => a.endsWith('/modules'))).toBe(true);
    racine.remove();
  }, 120000);
});
