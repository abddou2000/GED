import { HttpEvent, HttpHandlerFn, HttpRequest, HttpResponse, HttpClient } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, delay, switchMap } from 'rxjs/operators';
import { MODE_DEMO } from './api';

/**
 * Mode démonstration — l'application tourne sans backend.
 *
 * <p>Hébergée seule (Vercel, Netlify…), la GED n'a aucun serveur à interroger :
 * chaque appel échouerait et les écrans resteraient vides. Cet intercepteur
 * répond à sa place, à partir de **captures des vraies réponses de l'API** — les
 * formes et les valeurs sont celles du serveur, rien n'est inventé.
 *
 * <p>Les écritures sont acquittées mais **ne persistent pas** : au rechargement,
 * le jeu de démonstration est intact. C'est délibéré — laisser croire à un
 * enregistrement durable serait plus trompeur qu'un effet visiblement éphémère.
 */

/** Chaque fichier n'est lu qu'une fois. */
const cache = new Map<string, unknown>();

/** Page vide au format du serveur, pour toute liste sans capture. */
const PAGE_VIDE = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 };

/** Associe une URL d'API au fichier de démonstration correspondant. */
function fichierPour(url: string): string | null {
  const u = url.split('?')[0];

  if (u.includes('/stats/overview')) return 'stats-overview';
  if (u.includes('/employes')) return 'employes';
  if (u.includes('/ocr/etat')) return 'ocr-etat';
  if (u.includes('/ocr/diagnostic')) return 'ocr-diagnostic';

  const familles: [string, string][] = [
    ['/workspaces', 'workspaces'], ['/documents', 'documents'],
    ['/type-documents', 'type-documents'], ['/indices', 'indices'],
    ['/plan-indexations', 'plan-indexations'], ['/access-groups', 'access-groups'],
    ['/etiquettes', 'etiquettes'], ['/workflowgeds', 'workflowgeds'],
  ];
  for (const [segment, nom] of familles) {
    if (!u.includes(segment)) continue;
    const reste = u.substring(u.indexOf(segment) + segment.length);
    if (reste === '/tree') return `${nom}-tree`;
    if (reste === '/for-select') return `${nom}-for-select`;
    if (reste === '/trashed') return `${nom}-trashed`;
    return nom;                       // détail d'un élément : la liste suffit à peupler l'écran
  }
  return null;
}

export function demoInterceptor(
  req: HttpRequest<unknown>, next: HttpHandlerFn): Observable<HttpEvent<unknown>> {

  // Hors mode démonstration, et pour les fichiers statiques, on ne touche à rien.
  if (!MODE_DEMO.actif || !req.url.startsWith('/api/v1')) return next(req);

  const http = inject(HttpClient);

  // Un court délai reproduit la latence d'un appel réseau : sans lui, les états
  // de chargement clignoteraient au lieu de s'afficher.
  const ok = (corps: unknown) => of(new HttpResponse({ status: 200, body: corps })).pipe(delay(120));

  const lire = (nom: string): Observable<unknown> => {
    if (cache.has(nom)) return of(cache.get(nom));
    return http.get(`assets/demo/${nom}.json`).pipe(
      switchMap(d => { cache.set(nom, d); return of(d); }),
      // Capture absente : l'écran affiche une liste vide plutôt que de casser.
      catchError(() => of(null)),
    );
  };

  /**
   * Dépôt d'un document.
   *
   * <p>Le corps est un `FormData` : le renvoyer tel quel donnerait un objet sans
   * `id`, et l'étape d'indexation qui suit appellerait l'analyse sans
   * identifiant — c'est ce qui laissait la fenêtre bloquée sur « Lecture du
   * document en cours ». On répond donc avec un document plausible, dont l'id
   * correspond à une analyse réellement capturée.
   */
  if (req.method === 'POST' && /\/documents\/?$/.test(req.url.split('?')[0])) {
    const nomFichier = req.body instanceof FormData
      ? String(req.body.get('name') || (req.body.get('file') as File)?.name || 'Document')
      : 'Document';
    return lire('indexation-documents').pipe(switchMap((idx: any) => {
      // Parmi les analyses capturées, retenir celle qui reconnaît le plus de
      // champs : c'est elle qui montre la lecture du document à l'œuvre. Prendre
      // la première venue afficherait souvent « 0/4 » et donnerait l'impression
      // que la fonction ne marche pas.
      const analyses = Object.entries(idx?.analyse ?? {}) as [string, any][];
      const meilleure = analyses.sort(
        (a, b) => (b[1]?.nbReconnus ?? 0) - (a[1]?.nbReconnus ?? 0))[0];
      const id = String(meilleure?.[0] ?? '');
      return ok({
        id,
        name: nomFichier.replace(/\.[^.]+$/, ''),
        extension: 'pdf',
        sizeLabel: '—',
        workspace: null,
        typeDocument: null,
        reference: null,
        valeurs: [],
      });
    }));
  }

  /**
   * Confirmation des index : on répond par la liste des valeurs retenues, au
   * format attendu par l'écran. Rien n'est conservé — au rechargement, le jeu de
   * démonstration est intact.
   */
  if (req.method === 'PUT' && /\/indexation\/documents\/[0-9a-f-]+/i.test(req.url)) {
    const corps = req.body as { valeurs?: { indexFieldId: string; valeur: string | null }[] } | null;
    return ok((corps?.valeurs ?? []).map(v => ({
      indexFieldId: v.indexFieldId, code: '', libelle: '', valeur: v.valeur ?? '',
    })));
  }

  if (req.method !== 'GET') {
    if (req.method === 'DELETE') return ok(null);
    return ok(req.body ?? { ok: true });
  }

  // Signatures : le jeu dépend de l'employé demandé.
  if (req.url.includes('/signatures/')) {
    const volet = req.url.includes('/history') ? 'history' : 'pending';
    const id = req.params.get('employeId');
    return lire('signatures').pipe(
      // Sans employé précisé, le premier du jeu capturé (identifiants UUID).
      switchMap((d: any) => ok(d?.[volet]?.[id ?? Object.keys(d?.[volet] ?? {})[0]] ?? [])));
  }

  // Indexation d'un document : analyse proposée, ou valeurs déjà enregistrées.
  const m = req.url.match(/\/indexation\/documents\/([0-9a-f-]+)(\/analyse|\/champs)?/i);
  if (m) {
    const [, id, suffixe] = m;
    const cle = suffixe === '/analyse' ? 'analyse' : 'valeurs';
    return lire('indexation-documents').pipe(switchMap((d: any) => {
      const exact = d?.[cle]?.[id];
      if (exact) return ok(exact);
      // Document hors du jeu capturé : on retombe sur le premier connu, pour que
      // l'écran reste démontrable au lieu de renvoyer une erreur.
      const premier = Object.values(d?.[cle] ?? {})[0];
      return ok(premier ?? (cle === 'analyse' ? null : []));
    }));
  }

  const nom = fichierPour(req.url);
  if (!nom) return ok(PAGE_VIDE);
  return lire(nom).pipe(switchMap(d => ok(d ?? PAGE_VIDE)));
}
