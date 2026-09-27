import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { DroitsService, PermissionVue, RoleVue } from '../droits.service';

const CATEGORIES: { code: PermissionVue['categorie']; libelle: string }[] = [
  { code: 'ELEMENTAIRE', libelle: 'Permissions élémentaires' },
  { code: 'CONFIDENTIALITE', libelle: 'Confidentialité' },
  { code: 'ADMINISTRATION', libelle: 'Administration (portée globale seulement)' },
];

/**
 * Rôles (dossier technique §12.2.1) : ensembles nommés de permissions, créés
 * et composés sans développement. Les quatre rôles système sont livrés ; la
 * composition de l'Administrateur est verrouillée par le serveur. Toute
 * modification vaut immédiatement pour tous les porteurs du rôle.
 */
@Component({
  selector: 'app-roles-admin',
  imports: [FormsModule],
  template: `
    <div class="page">
      <header class="entete">
        <h1>Rôles</h1>
        <p>Un rôle est un ensemble de permissions. Cochez la composition puis enregistrez : les porteurs
           du rôle la reçoivent immédiatement. Les permissions d'administration ne s'exercent qu'avec une
           attribution de portée globale.</p>
      </header>

      <section class="carte">
        <h2>Nouveau rôle</h2>
        <div class="grille">
          <label>Code <input type="text" [(ngModel)]="nouveauCode" placeholder="LECTEUR_JURIDIQUE" /></label>
          <label>Libellé <input type="text" [(ngModel)]="nouveauLibelle" placeholder="Lecteur juridique" /></label>
        </div>
        <button type="button" class="primaire" [disabled]="!nouveauCode.trim() || !nouveauLibelle.trim()"
                (click)="creer()">Créer (sans permission)</button>
      </section>

      @for (r of roles(); track r.id) {
        <section class="carte">
          <h2>{{ r.libelle }} <span class="marque">{{ r.code }}</span>
            @if (r.systeme) { <span class="marque">système</span> }
            @if (r.accesGlobal) { <span class="marque">accès global</span> }
          </h2>
          @for (c of categories; track c.code) {
            <p class="muet">{{ c.libelle }}</p>
            <div class="permissions">
              @for (p of parCategorie()[c.code]; track p.code) {
                <label class="perm">
                  <input type="checkbox" [checked]="coche(r, p.code)" [disabled]="verrouille(r)"
                         (change)="basculer(r, p.code)" /> {{ p.libelle }}
                </label>
              }
            </div>
          }
          <p>
            <button type="button" class="primaire" [disabled]="verrouille(r) || !modifie(r)" (click)="enregistrer(r)">
              Enregistrer la composition</button>
            @if (!r.systeme) {
              <button type="button" class="retirer" [disabled]="r.attribue" (click)="supprimer(r)"
                      [title]="r.attribue ? 'Rôle encore attribué' : ''">Supprimer</button>
            }
          </p>
        </section>
      }
    </div>
  `,
  styleUrl: '../administration.scss',
})
export class RolesAdmin implements OnInit {
  private droits = inject(DroitsService);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  protected readonly categories = CATEGORIES;
  protected roles = signal<RoleVue[]>([]);
  protected permissions = signal<PermissionVue[]>([]);
  protected parCategorie = computed(() => {
    const m: Record<string, PermissionVue[]> = {};
    for (const p of this.permissions()) (m[p.categorie] ??= []).push(p);
    return m;
  });
  /** Composition en cours d'édition, par rôle. */
  private brouillons = new Map<string, Set<string>>();

  protected nouveauCode = '';
  protected nouveauLibelle = '';

  ngOnInit(): void {
    this.droits.permissions().subscribe(l => this.permissions.set(l));
    this.charger();
  }

  private charger(): void {
    this.brouillons.clear();
    this.droits.roles().subscribe({
      next: l => this.roles.set(l),
      error: () => this.notify.error('Rôles indisponibles.'),
    });
  }

  protected verrouille(r: RoleVue): boolean {
    return r.code === 'ADMINISTRATEUR';
  }

  private composition(r: RoleVue): Set<string> {
    if (!this.brouillons.has(r.id)) this.brouillons.set(r.id, new Set(r.permissions));
    return this.brouillons.get(r.id)!;
  }

  protected coche(r: RoleVue, code: string): boolean {
    return this.composition(r).has(code);
  }

  protected basculer(r: RoleVue, code: string): void {
    const c = this.composition(r);
    if (c.has(code)) c.delete(code); else c.add(code);
  }

  protected modifie(r: RoleVue): boolean {
    const c = this.composition(r);
    return c.size !== r.permissions.length || r.permissions.some(p => !c.has(p));
  }

  protected enregistrer(r: RoleVue): void {
    this.droits.modifierRole(r.id, r.libelle, [...this.composition(r)]).subscribe({
      next: () => { this.notify.success(`Rôle « ${r.libelle} » recomposé : effectif immédiatement.`); this.charger(); },
      error: err => this.notify.error(err?.error?.message ?? 'Enregistrement refusé.'),
    });
  }

  protected creer(): void {
    this.droits.creerRole(this.nouveauCode.trim(), this.nouveauLibelle.trim(), []).subscribe({
      next: () => {
        this.notify.success('Rôle créé : composez-le ci-dessous.');
        this.nouveauCode = '';
        this.nouveauLibelle = '';
        this.charger();
      },
      error: err => this.notify.error(err?.error?.message ?? 'Création refusée.'),
    });
  }

  protected supprimer(r: RoleVue): void {
    this.confirm.ask({
      title: 'Supprimer le rôle',
      message: `Supprimer le rôle « ${r.libelle} » ?`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.droits.supprimerRole(r.id).subscribe({
        next: () => { this.notify.success('Rôle supprimé.'); this.charger(); },
        error: err => this.notify.error(err?.error?.message ?? 'Suppression refusée.'),
      });
    });
  }
}
