import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { CircuitService } from '../circuit.service';
import { Circuit, LIBELLE_STATUT, RegleDocument, StatutCircuit, ValidateurCircuit } from '../circuit.model';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { AuthService } from '../../../core/auth.service';
import { DroitsService, IdentiteAdmin, Option } from '../../administration/droits.service';

/**
 * Circuit de validation sur la fiche d'un document (§12.8) : statut, état de
 * chaque validateur sur la version courante, décisions (les caduques
 * signalées), et les actions permises à la personne connectée — décider,
 * annuler, ouvrir un nouveau circuit, diffuser le document validé.
 */
@Component({
  selector: 'app-circuit-document',
  imports: [SlicePipe, FormsModule, MatButtonModule, MatIconModule, MatSelectModule],
  template: `
    <div class="card circuit">
      <div class="entete">
        <h3>Validation</h3>
        @if (courant(); as c) {
          <span class="pill" [class]="badge(c.statut)"><span class="dot"></span>{{ libelle(c.statut) }}</span>
        } @else if (!chargement()) {
          <span class="muted">{{ regle() ? 'Aucun circuit' : 'Aucune validation requise' }}</span>
        }
      </div>

      @if (courant(); as c) {
        <p class="meta">
          Règle « {{ c.regle || '—' }} » · ouvert le {{ c.ouvertLe | slice:0:10 }}
          @if (c.initiateur) { par {{ c.initiateur }} }
          @if (c.versionCouranteNumero) { · décisions sur la version {{ c.versionCouranteNumero }} }
        </p>
        @if (c.statut === 'ANNULE') {
          <p class="meta">Annulé le {{ c.annuleLe | slice:0:10 }} par {{ c.annulePar || '—' }} : {{ c.motifAnnulation }}</p>
        }
        <ul class="validateurs">
          @for (v of c.validateurs; track v.id) {
            <li>
              <span class="pill" [class]="badgeEtat(v)"><span class="dot"></span>{{ etat(v) }}</span>
              <span class="nom">{{ v.libelle }}</span>
              <span class="muted">{{ v.type === 'ROLE' ? 'rôle ' + v.roleCode : v.employe }}</span>
              @if (v.reaffecteDe) {
                <span class="muted">· réaffecté (remplace {{ v.reaffecteDe }}, par {{ v.reaffectePar }} : {{ v.motifReaffectation }})</span>
              }
            </li>
          }
        </ul>
        @if (c.decisions.length) {
          <details>
            <summary>Décisions ({{ c.decisions.length }})</summary>
            <ul class="decisions">
              @for (d of c.decisions; track d.id) {
                <li [class.caduque]="d.caduque">
                  {{ d.le | slice:0:16 }} · {{ d.auteur || '—' }} ·
                  <strong>{{ d.decision === 'VALIDE' ? 'validé' : d.decision === 'REFUSE' ? 'refusé' : 'décision retirée' }}</strong>
                  @if (d.versionNumero) { (v{{ d.versionNumero }}) }
                  @if (d.motif) { — {{ d.motif }} }
                  @if (d.caduque) { <span class="muted">— caduque (version antérieure)</span> }
                </li>
              }
            </ul>
          </details>
        }
      }

      <div class="actions">
        @if (courant()?.peutDecider) {
          <button mat-flat-button color="primary" (click)="valider()">Valider</button>
          <button mat-stroked-button (click)="refuser()">Refuser</button>
        }
        @if (courant()?.peutAnnuler) {
          <button mat-stroked-button (click)="annuler()">Annuler le circuit</button>
        }
        @if (peutRouvrir()) {
          <button mat-stroked-button (click)="ouvrir()">Nouveau circuit</button>
        }
        @if (peutDiffuser()) {
          <button mat-stroked-button (click)="diffusionOuverte.set(!diffusionOuverte())">Diffuser</button>
        }
      </div>

      @if (diffusionOuverte()) {
        <div class="diffusion">
          <p class="muted">Accorde la lecture de ce document, sans copie.</p>
          @if (identites().length) {
            <mat-select [(ngModel)]="personnes" multiple placeholder="Personnes" aria-label="Personnes">
              @for (u of identites(); track u.id) { <mat-option [value]="u.id">{{ u.fullName }}</mat-option> }
            </mat-select>
          }
          <mat-select [(ngModel)]="groupesChoisis" multiple placeholder="Groupes" aria-label="Groupes">
            @for (g of groupes(); track g.id) { <mat-option [value]="g.id">{{ g.name }}</mat-option> }
          </mat-select>
          <button mat-flat-button color="primary" (click)="diffuser()">Accorder la lecture</button>
        </div>
      }
    </div>`,
  styles: [`
    .circuit { display: flex; flex-direction: column; gap: 8px; }
    .entete { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .entete h3 { margin: 0; font-size: 15px; }
    .meta { margin: 0; font-size: 12.5px; color: var(--muted); }
    .validateurs, .decisions { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
    .validateurs li { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
    .validateurs .nom { font-weight: 600; }
    .decisions li { font-size: 12.5px; }
    .decisions li.caduque { opacity: .6; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .diffusion { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .diffusion mat-select { min-width: 200px; }
    details summary { cursor: pointer; font-size: 12.5px; color: var(--muted); }
  `],
})
export class CircuitDocument {
  private service = inject(CircuitService);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private auth = inject(AuthService);
  private droits = inject(DroitsService);

  /** Document affiché et permissions de l'appelant sur lui (confort d'affichage). */
  readonly documentId = input.required<string>();
  readonly permissions = input<string[]>([]);

  readonly circuits = signal<Circuit[]>([]);
  readonly regle = signal<RegleDocument | null>(null);
  readonly chargement = signal(true);
  readonly diffusionOuverte = signal(false);
  readonly identites = signal<IdentiteAdmin[]>([]);
  readonly groupes = signal<Option[]>([]);
  personnes: string[] = [];
  groupesChoisis: string[] = [];

  /** Le plus récent : c'est lui qui dit où en est le document. */
  readonly courant = computed(() => this.circuits()[0] ?? null);

  readonly peutRouvrir = computed(() => {
    const c = this.courant();
    return c?.statut === 'ANNULE' && !!this.regle()
      && (this.permissions().includes('MODIFIER') || this.auth.peut('GERER_REFERENTIELS'));
  });

  /** Diffusable : permission Diffuser, et document validé ou hors workflow. */
  readonly peutDiffuser = computed(() => {
    if (!this.permissions().includes('DIFFUSER')) return false;
    const vivant = this.circuits().find(c => c.statut !== 'ANNULE');
    return this.circuits().length === 0 || vivant?.statut === 'VALIDE';
  });

  constructor() {
    effect(() => {
      const id = this.documentId();
      this.charger(id);
    });
    effect(() => {
      if (!this.diffusionOuverte()) return;
      this.droits.groupes().subscribe({ next: l => this.groupes.set(l), error: () => this.groupes.set([]) });
      this.droits.identites().subscribe({ next: l => this.identites.set(l), error: () => this.identites.set([]) });
    });
  }

  charger(id = this.documentId()): void {
    this.chargement.set(true);
    this.service.circuitsDuDocument(id).subscribe({
      next: l => { this.circuits.set(l); this.chargement.set(false); },
      error: () => { this.circuits.set([]); this.chargement.set(false); },
    });
    this.service.regleDuDocument(id).subscribe({ next: r => this.regle.set(r), error: () => this.regle.set(null) });
  }

  valider(): void {
    const c = this.courant();
    if (!c) return;
    this.confirm.ask({ title: 'Valider le document', message: `Confirmez-vous la validation de « ${c.document} » ?`,
      confirmLabel: 'Valider' }).subscribe(ok => {
      if (!ok) return;
      this.service.decider(c.id, 'VALIDE', null).subscribe({
        next: () => { this.notify.success('Validation enregistrée.'); this.charger(); },
        error: err => this.notify.error(err?.error?.message ?? 'Validation impossible.'),
      });
    });
  }

  refuser(): void {
    const c = this.courant();
    if (!c) return;
    this.confirm.askText({ title: 'Refuser le document', message: 'Indiquez le motif du refus.',
      promptLabel: 'Motif du refus', promptRequired: true, confirmLabel: 'Refuser', danger: true })
      .subscribe(motif => {
        if (motif == null) return;
        this.service.decider(c.id, 'REFUSE', motif).subscribe({
          next: () => { this.notify.success('Refus enregistré.'); this.charger(); },
          error: err => this.notify.error(err?.error?.message ?? 'Refus impossible.'),
        });
      });
  }

  annuler(): void {
    const c = this.courant();
    if (!c) return;
    this.confirm.askText({ title: 'Annuler le circuit',
      message: 'Les décisions déjà rendues sont conservées ; un nouveau circuit pourra être ouvert.',
      promptLabel: "Motif de l'annulation", promptRequired: true, confirmLabel: 'Annuler le circuit', danger: true })
      .subscribe(motif => {
        if (motif == null) return;
        this.service.annuler(c.id, motif).subscribe({
          next: () => { this.notify.success('Circuit annulé.'); this.charger(); },
          error: err => this.notify.error(err?.error?.message ?? 'Annulation impossible.'),
        });
      });
  }

  ouvrir(): void {
    this.service.ouvrir(this.documentId()).subscribe({
      next: () => { this.notify.success('Nouveau circuit ouvert : les validateurs sont prévenus.'); this.charger(); },
      error: err => this.notify.error(err?.error?.message ?? 'Ouverture impossible.'),
    });
  }

  diffuser(): void {
    if (!this.personnes.length && !this.groupesChoisis.length) {
      this.notify.error('Choisissez au moins une personne ou un groupe.');
      return;
    }
    this.service.diffuser(this.documentId(), this.personnes, this.groupesChoisis).subscribe({
      next: r => {
        this.notify.success(r.habilitationsPosees
          ? `Lecture accordée (${r.habilitationsPosees}).` : 'Ces destinataires lisaient déjà le document.');
        this.personnes = [];
        this.groupesChoisis = [];
        this.diffusionOuverte.set(false);
      },
      error: err => this.notify.error(err?.error?.message ?? 'Diffusion impossible.'),
    });
  }

  libelle(s: StatutCircuit): string { return LIBELLE_STATUT[s] ?? s; }

  badge(s: StatutCircuit): string {
    return s === 'VALIDE' ? 'st-ok' : s === 'REFUSE' ? 'st-ko' : s === 'ANNULE' ? 'st-off' : 'st-pending';
  }

  etat(v: ValidateurCircuit): string {
    return v.etat === 'VALIDE' ? 'Validé' : v.etat === 'REFUSE' ? 'Refusé' : 'En attente';
  }

  badgeEtat(v: ValidateurCircuit): string {
    return v.etat === 'VALIDE' ? 'st-ok' : v.etat === 'REFUSE' ? 'st-ko' : 'st-pending';
  }
}
