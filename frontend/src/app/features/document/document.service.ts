import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { API_BASE } from '../../core/api';
import { DocumentItem, DocumentRequest, PageResult } from './document.model';
import { SignatureService } from '../signature/signature.service';

/**
 * Le minimum à connaître d'un document pour le télécharger : son identifiant,
 * et de quoi reconstituer un nom de fichier. Volontairement plus permissif que
 * `DocumentItem` — l'écran de liste ne dispose parfois que de l'identifiant
 * (ligne non encore rechargée), et il doit tout de même pouvoir télécharger.
 */
export interface NomFichierSource {
  id: string;
  fileName?: string | null;
  name?: string | null;
  extension?: string | null;
}

/**
 * Nom proposé à l'enregistrement. `fileName` fait foi ; à défaut on recompose
 * `name` + extension, car un fichier sans extension n'est ouvert par aucune
 * application sous Windows. Dernier recours : `document-<id>`, pour ne jamais
 * enregistrer sous un nom vide.
 */
export function nomDeFichier(doc: NomFichierSource): string {
  if (doc.fileName) return assainir(doc.fileName);
  const ext = doc.extension ? doc.extension.replace(/^\.+/, '') : '';
  if (doc.name) return assainir(ext ? `${doc.name}.${ext}` : doc.name);
  return assainir(ext ? `document-${doc.id}.${ext}` : `document-${doc.id}`);
}

/**
 * Neutralise ce qu'un système de fichiers refuse. Le nom vient de la fiche,
 * donc d'une saisie utilisateur : un « / » ou un « : » dans le nom d'un
 * document ne doit pas décider de l'endroit ni du nom de l'enregistrement.
 * Les accents et les apostrophes, eux, passent intacts — `a.download` est une
 * chaîne DOM, pas un en-tête HTTP, et n'a rien à encoder.
 */
function assainir(nom: string): string {
  const propre = nom
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '-')  // interdits Windows + controles
    .replace(/^\.+/, '')                          // pas de fichier caché ni de « .. »
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 200);
  return propre || 'document';
}

/**
 * Message destiné à l'utilisateur. `null` signifie « ne rien afficher » : sur
 * 401 l'intercepteur a déjà fermé la session et renvoyé vers la connexion, et
 * un toast « téléchargement impossible » posé par-dessus l'écran de connexion
 * ne ferait que masquer le vrai motif (session expirée).
 *
 * On raisonne sur le CODE seul : la réponse a été lue en `blob`, donc
 * `err.error` est un `Blob` et non le JSON d'erreur du serveur — l'afficher
 * donnerait « [object Blob] ».
 */
export function messageErreurTelechargement(err: HttpErrorResponse): string | null {
  if (err.status === 401) return null;
  if (err.status === 0)   return 'Serveur injoignable : téléchargement impossible.';
  if (err.status === 403) return "Vous n'avez pas l'autorisation de télécharger ce document.";
  if (err.status === 404) return "Ce document n'est plus disponible au téléchargement.";
  return 'Téléchargement impossible. Réessayez dans un instant.';
}

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private http = inject(HttpClient);
  /* Le compteur d'étapes à traiter vit dans le service des signatures : c'est
     lui qui prévient la coque. Les écritures documentaires le touchent parce
     qu'un dépôt ouvre un circuit — l'écran appelant n'a rien à y penser. */
  private signatures = inject(SignatureService);
  private url = `${API_BASE}/documents`;

  /** `workspaceId` restreint la liste aux documents d'un dossier — le serveur
   *  sait déjà le faire, inutile de tout charger pour filtrer ensuite. */
  list(page = 0, size = 10, search = '', workspaceId?: string,
       sortBy = '', sortDir = 'desc'): Observable<PageResult<DocumentItem>> {
    const params = this.params(page, size, search, sortBy, sortDir);
    if (workspaceId != null) params['workspaceId'] = workspaceId;
    return this.http.get<PageResult<DocumentItem>>(this.url, { params });
  }

  trashed(page = 0, size = 10, search = '', workspaceId?: string,
          sortBy = '', sortDir = 'desc'): Observable<PageResult<DocumentItem>> {
    return this.http.get<PageResult<DocumentItem>>(`${this.url}/trashed`,
      { params: this.params(page, size, search, sortBy, sortDir) });
  }

  /** Le tri n'est transmis que s'il est demande : sinon le serveur applique son
   *  ordre par defaut (le plus recent d'abord) plutot qu'un tri vide. */
  private params(page: number, size: number, search: string, sortBy: string, sortDir: string) {
    const p: Record<string, string | number> = { page, size, search };
    if (sortBy) { p['sortBy'] = sortBy; p['sortDir'] = sortDir; }
    return p;
  }

  /** Fiche complète d'un document (étiquettes et versions comprises). */
  get(id: string): Observable<DocumentItem> {
    return this.http.get<DocumentItem>(`${this.url}/${id}`);
  }

  /** Modifie la fiche : nom, type, date, etiquettes, archivage. */
  update(id: string, body: DocumentRequest): Observable<DocumentItem> {
    return this.http.put<DocumentItem>(`${this.url}/${id}`, body);
  }

  /** Verrouille ou libere le document. */
  verrou(id: string, verrouille: boolean): Observable<DocumentItem> {
    return this.http.patch<DocumentItem>(`${this.url}/${id}/verrou`, {}, { params: { verrouille } });
  }

  /** Depose une nouvelle version : l'ancienne reste consultable. */
  ajouterVersion(id: string, file: File, observation: string): Observable<DocumentItem> {
    const fd = new FormData();
    fd.append('file', file);
    if (observation) fd.append('observation', observation);
    return this.http.post<DocumentItem>(`${this.url}/${id}/versions`, fd);
  }

  /** Rend une version anterieure courante. */
  restaurerVersion(id: string, versionId: string): Observable<DocumentItem> {
    return this.http.patch<DocumentItem>(`${this.url}/${id}/versions/${versionId}/default`, {});
  }

  /**
   * Dépose un document. Le CRÉATEUR n'est plus transmis : le serveur le lit
   * dans le jeton. L'envoyer d'ici laissait croire que le navigateur en
   * décidait, alors qu'il n'a aucune autorité pour désigner l'auteur d'un
   * dépôt.
   */
  upload(file: File, typeDocumentId: string, name: string, expirationDate: string | null,
         etiquetteIds: string[] = []): Observable<DocumentItem> {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('typeDocumentId', String(typeDocumentId));
    if (name) fd.append('name', name);
    if (expirationDate) fd.append('expirationDate', expirationDate);
    for (const id of etiquetteIds) fd.append('etiquetteIds', String(id));
    /* Un dépôt ouvre le circuit de validation du dossier : une étape de plus à
       traiter. Le badge du menu doit s'en apercevoir tout de suite — il restait
       sinon sur son chiffre jusqu'au prochain changement d'écran. */
    return this.http.post<DocumentItem>(this.url, fd)
      .pipe(tap(() => this.signatures.signalerChangement()));
  }

  /**
   * Récupère le CONTENU du fichier, et non plus une simple URL.
   *
   * POURQUOI : les écrans ouvraient auparavant `downloadUrl()` dans un onglet
   * (`window.open`). Une navigation de ce type ne passe pas par `HttpClient`,
   * donc pas par les intercepteurs : aucun en-tête `Authorization` n'était
   * posé. Le jeton vivant dans `sessionStorage` (et non dans un cookie envoyé
   * d'office par le navigateur), le serveur répondait 401 et l'onglet restait
   * blanc, sans le moindre message. En mode démonstration c'était pire encore :
   * l'intercepteur qui simule le serveur était contourné lui aussi, et l'onglet
   * tombait sur le 404 de l'hébergeur.
   *
   * En passant par `HttpClient` en `responseType: 'blob'`, la requête traverse
   * la chaîne d'intercepteurs : le jeton est posé s'il y a un vrai serveur, et
   * la démonstration peut répondre s'il n'y en a pas.
   */
  telecharger(id: string): Observable<Blob> {
    return this.http.get(`${this.url}/${id}/download`, { responseType: 'blob' });
  }

  /**
   * Aperçu d'une version (§6.1.6) : PDF ou image déchiffrés à la volée,
   * bureautique convertie en PDF. Même raison que le téléchargement pour
   * passer par HttpClient : le jeton doit accompagner la requête.
   */
  apercu(versionId: string): Observable<Blob> {
    return this.http.get(`${API_BASE}/versions/${versionId}/apercu`, { responseType: 'blob' });
  }

  /**
   * Télécharge puis déclenche l'enregistrement du fichier par le navigateur.
   *
   * POURQUOI ici et pas dans les écrans : quatre écrans proposent la même
   * action. Dupliquer la mécanique de l'ancre temporaire, c'était garantir
   * qu'un jour l'un des quatre oublierait de révoquer son URL d'objet. Les
   * écrans n'ont plus qu'à s'abonner et à signaler l'échec à l'utilisateur.
   *
   * Le nom vient de la fiche du document (`fileName`, sinon `name` + extension)
   * car on ne peut pas compter sur un en-tête `Content-Disposition` : la
   * démonstration renvoie un `Blob` nu, et un `Blob` ne porte aucun nom.
   */
  telechargerEtEnregistrer(doc: NomFichierSource): Observable<Blob> {
    return this.telecharger(doc.id).pipe(
      tap(blob => this.enregistrerBlob(blob, nomDeFichier(doc))),
    );
  }

  /**
   * Confie le `Blob` au navigateur via une ancre synthétique.
   *
   * L'URL d'objet est RÉVOQUÉE aussitôt : sans cela, le contenu du fichier
   * resterait retenu en mémoire jusqu'au rechargement de la page, et un
   * utilisateur qui télécharge plusieurs documents ferait grossir l'onglet
   * d'autant. La révocation est différée d'un tour de boucle d'évènements pour
   * laisser le navigateur amorcer l'enregistrement avant que l'URL ne meure.
   */
  private enregistrerBlob(blob: Blob, nom: string): void {
    const href = URL.createObjectURL(blob);
    try {
      const a = document.createElement('a');
      a.href = href;
      a.download = nom;
      // Firefox exige que l'ancre soit dans le document pour que le clic compte.
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();
      a.remove();
    } finally {
      // Différée d'un tour de boucle : révoquer trop tôt annule l'enregistrement.
      setTimeout(() => URL.revokeObjectURL(href));
    }
  }

  /* Supprimer, restaurer : la file des etapes a traiter peut changer.
     On redemande le compte plutot que de le deviner — c'est le serveur
     qui fait foi, y compris quand il ne retire rien. */
  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`)
      .pipe(tap(() => this.signatures.signalerChangement()));
  }

  restore(id: string): Observable<void> {
    return this.http.patch<void>(`${this.url}/${id}/restore`, {})
      .pipe(tap(() => this.signatures.signalerChangement()));
  }

  multipleDelete(ids: string[]): Observable<void> {
    return this.http.delete<void>(`${this.url}/multiple-delete`, { body: { ids } })
      .pipe(tap(() => this.signatures.signalerChangement()));
  }

  multipleRestore(ids: string[]): Observable<void> {
    return this.http.patch<void>(`${this.url}/multiple-restore`, { ids })
      .pipe(tap(() => this.signatures.signalerChangement()));
  }
}
