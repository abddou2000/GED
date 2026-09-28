import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import {
  DroitsService, HabilitationVue, IdentiteAdmin, LienRepris, Option, RoleVue, TypeSujet,
} from '../droits.service';

type Portee = 'GLOBALE' | 'NOEUD' | 'DOCUMENT';

/**
 * Habilitations (dossier technique §12.2.1) : qui (utilisateur ou groupe GED)
 * reçoit quel rôle, où (portée globale, nœud, document isolé), avec ou sans
 * rupture d'héritage. Toute modification est effective immédiatement et
 * auditée (avant / après) par le serveur.
 *
 * <p>Une identité provisionnée sans rôle y apparaît signalée : lui attribuer
 * un premier rôle, c'est une habilitation de portée globale ou sur un espace.
 */
@Component({
  selector: 'app-habilitations-admin',
  imports: [MatIconModule, FormsModule, DatePipe],
  template: `
    <div class="page">
      <header class="entete">
        <h1>Habilitations</h1>
        <p>Attribuer un rôle à un utilisateur ou à un groupe GED, sur tout le fonds (portée globale),
           sur un espace ou dossier (hérité en dessous) ou sur un seul document. Une rupture d'héritage
           retire, sur un nœud et en dessous, tout ce qui serait hérité. Effet immédiat.</p>
      </header>

      @if (sansRole().length) {
        <section class="alerte">
          <strong>{{ sansRole().length }} identité(s) sans rôle</strong> — elles ne voient qu'une page d'accueil vide :
          @for (u of sansRole(); track u.id) {
            <button type="button" class="lien" (click)="choisirUtilisateur(u)">{{ u.fullName }} ({{ u.identifiant }})</button>
          }
        </section>
      }

      @if (liensRepris().length) {
        <section class="carte">
          <h2>Rapport de reprise : espaces que les groupes « couvraient »</h2>
          <p class="muet">L'ancienne application n'attachait aucun droit à ces liens ; ils n'ont pas été
             convertis. Posez ici les habilitations voulues.</p>
          <table>
            <thead><tr><th>Groupe</th><th>Espace</th><th>Habilitation posée</th><th></th></tr></thead>
            <tbody>
              @for (l of liensRepris(); track l.groupeId + l.noeudId) {
                <tr>
                  <td>{{ l.groupe }}</td><td>{{ l.noeud }}</td>
                  <td>{{ l.habilitationPosee ? 'oui' : 'non' }}</td>
                  <td><button type="button" class="lien" (click)="preparer(l)">Préparer l'attribution</button></td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }

      <section class="carte">
        <h2>Nouvelle attribution</h2>
        <div class="grille">
          <label>Sujet
            <select [(ngModel)]="sujetType" (ngModelChange)="sujetId = ''">
              <option value="UTILISATEUR">Utilisateur</option>
              <option value="GROUPE">Groupe GED</option>
            </select>
          </label>
          <label>{{ sujetType === 'GROUPE' ? 'Groupe' : 'Utilisateur' }}
            <select [(ngModel)]="sujetId" (ngModelChange)="filtrer()">
              <option value="">— tous —</option>
              @if (sujetType === 'GROUPE') {
                @for (g of groupes(); track g.id) { <option [value]="g.id">{{ g.name }}</option> }
              } @else {
                @for (u of identites(); track u.id) {
                  <option [value]="u.id">{{ u.fullName }} ({{ u.identifiant }}){{ u.roles.length ? '' : ' — sans rôle' }}</option>
                }
              }
            </select>
          </label>
          <label>Portée
            <select [(ngModel)]="portee">
              <option value="GLOBALE">Tout le fonds (globale)</option>
              <option value="NOEUD">Un espace ou dossier</option>
              <option value="DOCUMENT">Un document</option>
            </select>
          </label>
          @if (portee === 'NOEUD') {
            <label>Nœud
              <select [(ngModel)]="noeudId">
                <option value="">—</option>
                @for (n of noeuds(); track n.id) { <option [value]="n.id">{{ n.name }}</option> }
              </select>
            </label>
            <label class="case"><input type="checkbox" [(ngModel)]="rupture" /> Rupture d'héritage</label>
          }
          @if (portee === 'DOCUMENT') {
            <label>Document
              <input type="search" placeholder="Nom du document…" [(ngModel)]="rechercheDoc" (ngModelChange)="chercherDocuments()" />
              <select [(ngModel)]="documentId">
                <option value="">—</option>
                @for (d of documents(); track d.id) { <option [value]="d.id">{{ d.name }}</option> }
              </select>
            </label>
          }
          <label>Rôle
            <select [(ngModel)]="roleId">
              <option value="">{{ rupture ? '— aucun (rupture seule) —' : '—' }}</option>
              @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.libelle }}</option> }
            </select>
          </label>
        </div>
        <button type="button" class="primaire" [disabled]="!valide()" (click)="attribuer()">Attribuer</button>
      </section>

      <section class="carte">
        <h2>Attributions {{ sujetId ? 'du sujet choisi' : '' }}</h2>
        <table>
          <thead><tr><th>Sujet</th><th>Rôle</th><th>Portée</th><th>Cible</th><th>Depuis le</th><th></th></tr></thead>
          <tbody>
            @for (h of habilitations(); track h.id) {
              <tr>
                <td>{{ h.sujetType === 'GROUPE' ? 'Groupe ' : '' }}{{ h.sujetLibelle }}</td>
                <td>@if (h.roleLibelle) { {{ h.roleLibelle }} } @else { <em>aucun</em> }
                    @if (h.ruptureHeritage) { <span class="marque">rupture</span> }</td>
                <td>{{ h.noeudId ? 'Nœud' : h.documentId ? 'Document' : 'Globale' }}</td>
                <td>{{ h.noeudLibelle || h.documentLibelle || '—' }}</td>
                <td>{{ h.creeLe | date:'dd/MM/yyyy HH:mm' }}</td>
                <td><button type="button" class="retirer" (click)="retirer(h)">Retirer</button></td>
              </tr>
            } @empty {
              <tr><td colspan="6">Aucune attribution.</td></tr>
            }
          </tbody>
        </table>
      </section>
    </div>
  `,
  styleUrl: '../administration.scss',
})
export class HabilitationsAdmin implements OnInit {
  private droits = inject(DroitsService);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  protected identites = signal<IdentiteAdmin[]>([]);
  protected groupes = signal<Option[]>([]);
  protected noeuds = signal<Option[]>([]);
  protected roles = signal<RoleVue[]>([]);
  protected documents = signal<Option[]>([]);
  protected habilitations = signal<HabilitationVue[]>([]);
  protected liensRepris = signal<LienRepris[]>([]);
  protected sansRole = computed(() => this.identites().filter(u => u.roles.length === 0));

  protected sujetType: TypeSujet = 'UTILISATEUR';
  protected sujetId = '';
  protected portee: Portee = 'GLOBALE';
  protected noeudId = '';
  protected documentId = '';
  protected roleId = '';
  protected rupture = false;
  protected rechercheDoc = '';

  ngOnInit(): void {
    this.droits.identites().subscribe(l => this.identites.set(l));
    this.droits.groupes().subscribe(l => this.groupes.set(l));
    this.droits.noeuds().subscribe(l => this.noeuds.set(l));
    this.droits.roles().subscribe(l => this.roles.set(l));
    this.droits.liensRepris().subscribe(l => this.liensRepris.set(l));
    this.filtrer();
  }

  /** Préremplit une attribution du groupe sur l'espace repris. */
  protected preparer(l: LienRepris): void {
    this.sujetType = 'GROUPE';
    this.sujetId = l.groupeId;
    this.portee = 'NOEUD';
    this.noeudId = l.noeudId;
    this.rupture = false;
    this.filtrer();
  }

  protected choisirUtilisateur(u: IdentiteAdmin): void {
    this.sujetType = 'UTILISATEUR';
    this.sujetId = u.id;
    this.filtrer();
  }

  protected filtrer(): void {
    const filtre = this.sujetId ? { sujetType: this.sujetType, sujetId: this.sujetId } : {};
    this.droits.habilitations(filtre).subscribe({
      next: l => this.habilitations.set(l),
      error: () => this.notify.error('Attributions indisponibles.'),
    });
  }

  protected chercherDocuments(): void {
    if (this.rechercheDoc.trim().length < 2) return;
    this.droits.documents(this.rechercheDoc.trim()).subscribe(l => this.documents.set(l));
  }

  protected valide(): boolean {
    if (!this.sujetId) return false;
    if (this.portee === 'NOEUD' && !this.noeudId) return false;
    if (this.portee === 'DOCUMENT' && !this.documentId) return false;
    const rupture = this.portee === 'NOEUD' && this.rupture;
    return !!this.roleId || rupture;
  }

  protected attribuer(): void {
    this.droits.attribuer({
      sujetType: this.sujetType,
      sujetId: this.sujetId,
      roleId: this.roleId || null,
      noeudId: this.portee === 'NOEUD' ? this.noeudId : null,
      documentId: this.portee === 'DOCUMENT' ? this.documentId : null,
      ruptureHeritage: this.portee === 'NOEUD' && this.rupture,
    }).subscribe({
      next: () => {
        this.notify.success('Attribution enregistrée : effective immédiatement.');
        this.filtrer();
        this.droits.liensRepris().subscribe(l => this.liensRepris.set(l));
        this.droits.identites().subscribe(l => this.identites.set(l));
      },
      error: err => this.notify.error(err?.error?.message ?? 'Attribution refusée.'),
    });
  }

  protected retirer(h: HabilitationVue): void {
    this.confirm.ask({
      title: 'Retirer l\'attribution',
      message: `Retirer ${h.roleLibelle ?? 'la rupture'} à ${h.sujetLibelle} (${h.noeudLibelle || h.documentLibelle || 'portée globale'}) ? L'effet est immédiat.`,
      confirmLabel: 'Retirer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.droits.retirer(h.id).subscribe({
        next: () => { this.notify.success('Attribution retirée.'); this.filtrer(); },
        error: () => this.notify.error('Retrait impossible.'),
      });
    });
  }
}
