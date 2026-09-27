import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { API_BASE } from '../../core/api';

/** Permission livrée (§12.2.1). */
export interface PermissionVue {
  code: string;
  libelle: string;
  categorie: 'ELEMENTAIRE' | 'ADMINISTRATION' | 'CONFIDENTIALITE';
}

/** Rôle composé depuis l'interface. */
export interface RoleVue {
  id: string;
  code: string;
  libelle: string;
  systeme: boolean;
  accesGlobal: boolean;
  permissions: string[];
  attribue: boolean;
}

export type TypeSujet = 'UTILISATEUR' | 'GROUPE' | 'APPLICATION';

/** Attribution : sujet, rôle, cible (nœud, document ou globale), rupture d'héritage. */
export interface HabilitationVue {
  id: string;
  sujetType: TypeSujet;
  sujetId: string;
  sujetLibelle: string;
  roleId: string | null;
  roleCode: string | null;
  roleLibelle: string | null;
  noeudId: string | null;
  noeudLibelle: string | null;
  documentId: string | null;
  documentLibelle: string | null;
  ruptureHeritage: boolean;
  creeLe: string;
}

export interface DemandeHabilitation {
  sujetType: TypeSujet;
  sujetId: string;
  roleId: string | null;
  noeudId: string | null;
  documentId: string | null;
  ruptureHeritage: boolean;
}

/** Identité connue de la GED (`/admin/utilisateurs`). */
export interface IdentiteAdmin {
  id: string;
  identifiant: string;
  fullName: string;
  email: string | null;
  direction: string | null;
  roles: string[];
}

export interface OrigineVue {
  nature: string;
  role: string | null;
  habilitationId: string | null;
  noeudAttributionId: string | null;
  noeudAttribution: string | null;
  via: string | null;
  viaLibelle: string | null;
  permissions: string[];
}

export interface EmplacementVue {
  noeudId: string;
  noeud: string | null;
  principal: boolean;
  permissions: string[];
  origines: OrigineVue[];
}

/** Droits effectifs d'une identité et leur origine (§12.2.3, P-22). */
export interface DroitsEffectifs {
  sujetId: string;
  sujet: string;
  cible: 'GLOBALE' | 'NOEUD' | 'DOCUMENT';
  cibleId: string | null;
  cibleLibelle: string | null;
  permissions: string[];
  origines: OrigineVue[];
  emplacements: EmplacementVue[];
  confidentialite: { niveau: string; autorise: boolean; motif: string } | null;
  administration: string[];
  rolesGlobaux: string[];
  roles: string[];
  accesGlobal: boolean;
  voirPrive: boolean;
  voirConfidentiel: boolean;
}

export interface Option {
  id: string;
  name: string;
}

/** Libellés des natures d'origine d'une permission. */
export const NATURES_ORIGINE: Record<string, string> = {
  ATTRIBUTION_DIRECTE: 'Attribution sur ce nœud',
  HERITAGE: 'Héritée d\'un nœud parent',
  PORTEE_GLOBALE: 'Attribution de portée globale',
  ACCES_GLOBAL: 'Accès global (Direction Générale)',
  RUPTURE: 'Rupture d\'héritage',
  DOCUMENT_ISOLE: 'Attribution sur le document',
};

/** Appels de l'administration des droits (`/api/v1/admin`, réservée à l'Administrateur). */
@Injectable({ providedIn: 'root' })
export class DroitsService {
  private http = inject(HttpClient);
  private base = `${API_BASE}/admin`;

  permissions(): Observable<PermissionVue[]> {
    return this.http.get<PermissionVue[]>(`${this.base}/permissions`);
  }

  roles(): Observable<RoleVue[]> {
    return this.http.get<RoleVue[]>(`${this.base}/roles`);
  }

  creerRole(code: string, libelle: string, permissions: string[]): Observable<RoleVue> {
    return this.http.post<RoleVue>(`${this.base}/roles`, { code, libelle, permissions });
  }

  modifierRole(id: string, libelle: string, permissions: string[]): Observable<RoleVue> {
    return this.http.put<RoleVue>(`${this.base}/roles/${id}`, { libelle, permissions });
  }

  supprimerRole(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/roles/${id}`);
  }

  habilitations(filtre: { sujetType?: TypeSujet; sujetId?: string; noeudId?: string } = {}): Observable<HabilitationVue[]> {
    let params = new HttpParams();
    for (const [k, v] of Object.entries(filtre)) if (v) params = params.set(k, v);
    return this.http.get<HabilitationVue[]>(`${this.base}/habilitations`, { params });
  }

  attribuer(d: DemandeHabilitation): Observable<HabilitationVue> {
    return this.http.post<HabilitationVue>(`${this.base}/habilitations`, d);
  }

  retirer(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/habilitations/${id}`);
  }

  droitsEffectifs(utilisateurId: string, noeudId?: string | null, documentId?: string | null): Observable<DroitsEffectifs> {
    let params = new HttpParams().set('utilisateurId', utilisateurId);
    if (noeudId) params = params.set('noeudId', noeudId);
    if (documentId) params = params.set('documentId', documentId);
    return this.http.get<DroitsEffectifs>(`${this.base}/droits-effectifs`, { params });
  }

  identites(): Observable<IdentiteAdmin[]> {
    return this.http.get<IdentiteAdmin[]>(`${this.base}/utilisateurs`);
  }

  groupes(): Observable<Option[]> {
    return this.http.get<Option[]>(`${API_BASE}/access-groups/for-select`);
  }

  noeuds(): Observable<Option[]> {
    return this.http.get<Option[]>(`${API_BASE}/workspaces/for-select`);
  }

  /** Documents dont le nom contient le texte (cible d'une habilitation de document isolé). */
  documents(recherche: string): Observable<Option[]> {
    const params = new HttpParams().set('search', recherche).set('size', '20');
    return this.http.get<{ content: { id: string; name: string }[] }>(`${API_BASE}/documents`, { params })
      .pipe(map(p => p.content.map(d => ({ id: d.id, name: d.name }))));
  }
}
