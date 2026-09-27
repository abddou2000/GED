import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NotifyService } from '../../../core/notify.service';
import {
  DroitsEffectifs, DroitsService, IdentiteAdmin, NATURES_ORIGINE, Option, OrigineVue,
} from '../droits.service';

type Cible = 'GLOBALE' | 'NOEUD' | 'DOCUMENT';

/**
 * Droits effectifs (dossier technique §12.2.3, P-22) : pour une identité et un
 * objet, les permissions résolues et leur origine — rôle, nœud d'attribution,
 * héritage, rattachement, confidentialité. Calculés par la même fonction de
 * décision que les accès : l'écran montre exactement ce que le serveur applique.
 */
@Component({
  selector: 'app-droits-effectifs-admin',
  imports: [FormsModule],
  template: `
    <div class="page">
      <header class="entete">
        <h1>Droits effectifs</h1>
        <p>Choisissez une personne et un objet : la GED affiche ce qu'elle peut y faire, et pourquoi.</p>
      </header>

      <section class="carte">
        <div class="grille">
          <label>Personne
            <select [(ngModel)]="utilisateurId">
              <option value="">—</option>
              @for (u of identites(); track u.id) { <option [value]="u.id">{{ u.fullName }} ({{ u.identifiant }})</option> }
            </select>
          </label>
          <label>Objet
            <select [(ngModel)]="cible">
              <option value="GLOBALE">Portée globale (administration)</option>
              <option value="NOEUD">Un espace ou dossier</option>
              <option value="DOCUMENT">Un document</option>
            </select>
          </label>
          @if (cible === 'NOEUD') {
            <label>Nœud
              <select [(ngModel)]="noeudId">
                <option value="">—</option>
                @for (n of noeuds(); track n.id) { <option [value]="n.id">{{ n.name }}</option> }
              </select>
            </label>
          }
          @if (cible === 'DOCUMENT') {
            <label>Document
              <input type="search" placeholder="Nom du document…" [(ngModel)]="rechercheDoc" (ngModelChange)="chercher()" />
              <select [(ngModel)]="documentId">
                <option value="">—</option>
                @for (d of documents(); track d.id) { <option [value]="d.id">{{ d.name }}</option> }
              </select>
            </label>
          }
        </div>
        <button type="button" class="primaire" [disabled]="!pret()" (click)="calculer()">Afficher</button>
      </section>

      @if (resultat(); as r) {
        <section class="carte">
          <h2>{{ r.sujet }} — {{ r.cibleLibelle || 'portée globale' }}</h2>
          <p><strong>Permissions :</strong>
            @if (r.permissions.length) {
              @for (p of r.permissions; track p) { <span class="marque accorde">{{ p }}</span> }
            } @else { <span class="refuse">aucune</span> }
          </p>
          <p class="muet">Rôles détenus : {{ r.roles.join(', ') || 'aucun' }}
            · globaux : {{ r.rolesGlobaux.join(', ') || 'aucun' }}
            · administration : {{ r.administration.join(', ') || 'aucune' }}
            @if (r.accesGlobal) { · accès global }
            @if (r.voirPrive) { · voit les documents privés }
            @if (r.voirConfidentiel) { · voit les documents confidentiels }</p>

          @if (r.confidentialite; as c) {
            <p><strong>Confidentialité :</strong> {{ c.niveau }} —
              <span [class.accorde]="c.autorise" [class.refuse]="!c.autorise">{{ motif(c.motif) }}</span></p>
          }

          @if (r.origines.length) {
            <h2>Origine</h2>
            <table><tbody>
              @for (o of r.origines; track $index) { <tr><td>{{ origine(o) }}</td><td>{{ o.permissions.join(', ') || '—' }}</td></tr> }
            </tbody></table>
          }

          @for (e of r.emplacements; track e.noeudId) {
            <h2>{{ e.principal ? 'Emplacement principal' : 'Rattachement' }} : {{ e.noeud }}</h2>
            <p>{{ e.permissions.join(', ') || 'aucune permission sur cet emplacement' }}</p>
            @if (e.origines.length) {
              <table><tbody>
                @for (o of e.origines; track $index) { <tr><td>{{ origine(o) }}</td><td>{{ o.permissions.join(', ') || '—' }}</td></tr> }
              </tbody></table>
            }
          }
        </section>
      }
    </div>
  `,
  styleUrl: '../administration.scss',
})
export class DroitsEffectifsAdmin implements OnInit {
  private droits = inject(DroitsService);
  private notify = inject(NotifyService);

  protected identites = signal<IdentiteAdmin[]>([]);
  protected noeuds = signal<Option[]>([]);
  protected documents = signal<Option[]>([]);
  protected resultat = signal<DroitsEffectifs | null>(null);

  protected utilisateurId = '';
  protected cible: Cible = 'GLOBALE';
  protected noeudId = '';
  protected documentId = '';
  protected rechercheDoc = '';

  ngOnInit(): void {
    this.droits.identites().subscribe(l => this.identites.set(l));
    this.droits.noeuds().subscribe(l => this.noeuds.set(l));
  }

  protected chercher(): void {
    if (this.rechercheDoc.trim().length < 2) return;
    this.droits.documents(this.rechercheDoc.trim()).subscribe(l => this.documents.set(l));
  }

  protected pret(): boolean {
    if (!this.utilisateurId) return false;
    if (this.cible === 'NOEUD') return !!this.noeudId;
    if (this.cible === 'DOCUMENT') return !!this.documentId;
    return true;
  }

  protected calculer(): void {
    this.droits.droitsEffectifs(this.utilisateurId,
      this.cible === 'NOEUD' ? this.noeudId : null,
      this.cible === 'DOCUMENT' ? this.documentId : null).subscribe({
      next: r => this.resultat.set(r),
      error: err => this.notify.error(err?.error?.message ?? 'Calcul impossible.'),
    });
  }

  protected origine(o: OrigineVue): string {
    const nature = NATURES_ORIGINE[o.nature] ?? o.nature;
    const role = o.role ? ` — rôle ${o.role}` : '';
    const lieu = o.noeudAttribution ? ` — posée sur « ${o.noeudAttribution} »` : '';
    const via = o.via === 'GROUPE' ? ` — par le groupe « ${o.viaLibelle} »` : '';
    return nature + role + lieu + via;
  }

  protected motif(m: string): string {
    return ({
      PUBLIC: 'aucune restriction',
      DEPOSANT: 'autorisé : la personne est le déposant',
      VOIR_PRIVE: 'autorisé : la personne porte VOIR_PRIVE',
      DESIGNE: 'autorisé : la personne est désignée',
      VOIR_CONFIDENTIEL: 'autorisé : la personne porte VOIR_CONFIDENTIEL',
      REFUS: 'refusé : le niveau masque ce document à cette personne',
    } as Record<string, string>)[m] ?? m;
  }
}
