import { AfterViewInit, Component, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSelectModule } from '@angular/material/select';
import { SelectionModel } from '@angular/cdk/collections';
import { Observable, forkJoin } from 'rxjs';
import { CircuitService } from '../../workflow/circuit.service';
import {
  ATraiter, Anomalie, Circuit, DecisionRendue, LIBELLE_ANOMALIE, LIBELLE_STATUT, StatutCircuit, TypeDecision,
} from '../../workflow/circuit.model';
import { SessionService } from '../../../core/session.service';
import { AuthService } from '../../../core/auth.service';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { Employe, EmployeService } from '../../../core/employe.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { ColumnPicker } from '../../../core/column-picker/column-picker';
import { ColonneDef } from '../../../core/column-prefs.service';
import { SelectionToggle } from '../../../core/selection-toggle/selection-toggle';

type Vue = 'pending' | 'history' | 'anomalies';

/**
 * Écran « Mes validations » (workflow §12.8) : ce qui attend MA décision,
 * mon historique, et — pour l'Administrateur — les validateurs qui ne peuvent
 * pas décider, à réaffecter à la main (D1).
 *
 * <p>Plus d'étapes : tous les validateurs d'un circuit sont sollicités en même
 * temps (D7). Une décision porte sur la version courante du document ; un
 * versement la rend caduque et le document revient dans la liste.
 */
@Component({
  selector: 'app-mes-workflow',
  imports: [
    SlicePipe, FormsModule, RouterLink, MatTableModule, MatButtonModule, MatIconModule, MatTooltipModule,
    MatPaginatorModule, MatSelectModule, SkeletonTable, ColumnPicker, SelectionToggle, MatCheckboxModule,
  ],
  templateUrl: './mes-workflow.html',
  styleUrl: './mes-workflow.scss',
})
export class MesWorkflow implements AfterViewInit {
  private service = inject(CircuitService);
  private session = inject(SessionService);
  private auth = inject(AuthService);
  private employesApi = inject(EmployeService);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  view = signal<Vue>('pending');
  pending = signal<ATraiter[]>([]);
  history = signal<DecisionRendue[]>([]);
  anomalies = signal<Anomalie[]>([]);
  employes = signal<Employe[]>([]);
  loading = signal(true);

  /** Administrateur du workflow : gère les règles, voit les anomalies, réaffecte. */
  readonly admin = computed(() => this.auth.peut('GERER_REFERENTIELS'));

  readonly dsPending = new MatTableDataSource<ATraiter>([]);
  readonly dsHistory = new MatTableDataSource<DecisionRendue>([]);

  @ViewChild('pagPending') pagPending?: MatPaginator;
  @ViewChild('pagHistory') pagHistory?: MatPaginator;

  pagePending = signal(0);
  pageHistory = signal(0);

  /** Réaffectation en cours de saisie, par validateur : employé choisi et motif. */
  readonly reaffectation: Record<string, { employeId: string | null; motif: string }> = {};

  ngAfterViewInit(): void { this.brancherPagination(); }

  private brancherPagination(): void {
    if (this.pagPending) this.dsPending.paginator = this.pagPending;
    if (this.pagHistory) this.dsHistory.paginator = this.pagHistory;
  }

  onPage(vue: 'pending' | 'history', index: number): void {
    (vue === 'pending' ? this.pagePending : this.pageHistory).set(index);
  }

  readonly ECRAN_PENDING = 'Mes validations - a traiter';
  readonly ECRAN_HISTORY = 'Mes validations - historique';

  readonly COLONNES_PENDING: ColonneDef[] = [
    { cle: 'select', libelle: '', toujours: true },
    { cle: 'document', libelle: 'Document', toujours: true },
    { cle: 'validateur', libelle: 'En tant que' },
    { cle: 'initiateur', libelle: 'Déposé par' },
    { cle: 'version', libelle: 'Version' },
    { cle: 'ouvert', libelle: 'Depuis le' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];

  readonly COLONNES_HISTORY: ColonneDef[] = [
    { cle: 'document', libelle: 'Document', toujours: true },
    { cle: 'decision', libelle: 'Décision' },
    { cle: 'motif', libelle: 'Motif' },
    { cle: 'version', libelle: 'Version' },
    { cle: 'date', libelle: 'Date' },
    { cle: 'circuit', libelle: 'Circuit' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];

  readonly pendingCols = signal<string[]>(['select', 'document', 'validateur', 'initiateur', 'version', 'ouvert', 'actions']);
  readonly historyCols = signal<string[]>(['document', 'decision', 'motif', 'version', 'date', 'circuit', 'actions']);

  readonly modeSelection = signal(false);
  readonly selection = new SelectionModel<ATraiter>(true, []);

  basculerSelection(actif: boolean): void {
    this.modeSelection.set(actif);
    if (!actif) this.selection.clear();
  }

  private lignesVisibles(): ATraiter[] {
    return this.dsPending.filteredData ?? [];
  }

  toutSelectionne(): boolean {
    const visibles = this.lignesVisibles();
    return visibles.length > 0 && visibles.every(s => this.selection.isSelected(s));
  }

  basculerTout(): void {
    const visibles = this.lignesVisibles();
    if (this.toutSelectionne()) visibles.forEach(s => this.selection.deselect(s));
    else visibles.forEach(s => this.selection.select(s));
  }

  readonly recherche = signal('');

  constructor() {
    this.dsPending.filterPredicate = (s, f) =>
      [s.document, s.libelle, s.initiateur].some(c => (c ?? '').toLowerCase().includes(f));
    this.dsHistory.filterPredicate = (s, f) =>
      [s.document, s.libelle, this.libelleDecision(s.decision), s.motif, this.libelleStatut(s.statutCircuit)]
        .some(c => (c ?? '').toLowerCase().includes(f));

    this.session.ensureUser();
    effect(() => {
      const id = this.session.user()?.id ?? null;
      if (id == null) return;
      this.reload();
    });
  }

  setView(v: Vue): void {
    this.view.set(v);
    if (v === 'anomalies' && !this.employes().length) {
      this.employesApi.listApprovers().subscribe(l => this.employes.set(l));
    }
    setTimeout(() => this.brancherPagination());
  }

  applySearch(valeur: string): void {
    const filtre = (valeur ?? '').trim().toLowerCase();
    this.recherche.set(filtre);
    this.dsPending.filter = filtre;
    this.dsHistory.filter = filtre;
    this.dsPending.paginator?.firstPage();
    this.dsHistory.paginator?.firstPage();
    this.pagePending.set(0);
    this.pageHistory.set(0);
  }

  reload(): void {
    this.selection.clear();
    this.loading.set(true);
    this.service.aTraiter().subscribe({
      next: l => {
        this.pending.set(l);
        this.dsPending.data = l;
        this.dsPending.filter = this.recherche();
        this.loading.set(false);
        setTimeout(() => this.brancherPagination());
      },
      error: () => { this.loading.set(false); this.notify.error('Chargement impossible.'); },
    });
    this.service.historique().subscribe(l => {
      this.history.set(l);
      this.dsHistory.data = l;
      this.dsHistory.filter = this.recherche();
      setTimeout(() => this.brancherPagination());
    });
    if (this.admin()) {
      this.service.anomalies().subscribe({ next: l => this.anomalies.set(l), error: () => this.anomalies.set([]) });
    }
  }

  /* ---------- Décisions ---------- */

  valider(s: ATraiter): void {
    this.confirm.ask({
      title: 'Valider le document',
      message: `Confirmez-vous la validation de « ${s.document} » (version ${s.versionNumero ?? '—'}) en tant que « ${s.libelle} » ?`,
      confirmLabel: 'Valider',
    }).subscribe(ok => {
      if (!ok) return;
      this.service.decider(s.circuitId, 'VALIDE', null, s.validateurId).subscribe({
        next: c => { this.reload(); this.notify.success(this.messageApres(c, 'Validation enregistrée.')); },
        error: err => this.notify.error(err?.error?.message ?? 'Validation impossible.'),
      });
    });
  }

  refuser(s: ATraiter): void {
    this.confirm.askText({
      title: 'Refuser le document',
      message: `Indiquez le motif du refus de « ${s.document} ». Un refus suffit à arrêter le circuit.`,
      promptLabel: 'Motif du refus',
      promptPlaceholder: 'Ex. montant erroné, pièce manquante…',
      promptRequired: true,
      confirmLabel: 'Refuser',
      danger: true,
    }).subscribe(motif => {
      if (motif == null) return;
      this.service.decider(s.circuitId, 'REFUSE', motif, s.validateurId).subscribe({
        next: () => { this.reload(); this.notify.success('Refus enregistré.'); },
        error: err => this.notify.error(err?.error?.message ?? 'Refus impossible.'),
      });
    });
  }

  /** Changer d'avis : la décision n'est pas effacée, une annulation s'y ajoute. */
  retirer(d: DecisionRendue): void {
    this.confirm.ask({
      title: 'Retirer ma décision',
      message: `Votre décision sur « ${d.document} » sera retirée (elle reste dans l'historique) ; `
             + `le document reviendra dans votre liste à traiter.`,
      confirmLabel: 'Retirer',
    }).subscribe(ok => {
      if (!ok) return;
      this.service.decider(d.circuitId, 'ANNULEE', null).subscribe({
        next: () => { this.reload(); this.notify.success('Décision retirée.'); },
        error: err => this.notify.error(err?.error?.message ?? 'Retrait impossible.'),
      });
    });
  }

  /** Seule la dernière décision d'un circuit encore ouvert peut être retirée. */
  peutRetirer(d: DecisionRendue): boolean {
    if (d.decision === 'ANNULEE') return false;
    if (d.statutCircuit !== 'EN_COURS' && d.statutCircuit !== 'REFUSE') return false;
    const derniere = this.history().find(x => x.circuitId === d.circuitId);
    return derniere?.id === d.id;
  }

  validerLot(): void {
    const lot = this.selection.selected;
    if (!lot.length) return;
    this.confirm.ask({
      title: `Valider ${lot.length} document(s)`,
      message: `Confirmez-vous la validation de :\n${this.listerLot(lot)}`,
      confirmLabel: 'Valider',
    }).subscribe(ok => {
      if (!ok) return;
      this.executerLot(lot.map(s => this.service.decider(s.circuitId, 'VALIDE', null, s.validateurId)), 'validé');
    });
  }

  refuserLot(): void {
    const lot = this.selection.selected;
    if (!lot.length) return;
    this.confirm.askText({
      title: `Refuser ${lot.length} document(s)`,
      message: `Indiquez le motif du refus de :\n${this.listerLot(lot)}`,
      promptLabel: 'Motif du refus',
      promptPlaceholder: 'Ex. montant erroné, pièce manquante…',
      promptRequired: true,
      confirmLabel: 'Refuser',
      danger: true,
    }).subscribe(motif => {
      if (motif == null) return;
      this.executerLot(lot.map(s => this.service.decider(s.circuitId, 'REFUSE', motif, s.validateurId)), 'refusé');
    });
  }

  private listerLot(lot: ATraiter[]): string {
    const noms = lot.slice(0, 5).map(s => `• ${s.document}`);
    if (lot.length > 5) noms.push(`• … et ${lot.length - 5} autre(s)`);
    return noms.join('\n');
  }

  private executerLot(appels: Observable<Circuit>[], verbe: string): void {
    this.loading.set(true);
    forkJoin(appels).subscribe({
      next: resultats => {
        this.selection.clear();
        this.notify.success(`${resultats.length} document(s) ${verbe}(s).`);
        this.reload();
      },
      error: err => {
        this.loading.set(false);
        this.notify.error(err?.error?.message ?? 'Action groupée incomplète.');
        this.reload();
      },
    });
  }

  private messageApres(c: Circuit, defaut: string): string {
    return c.statut === 'VALIDE' ? `« ${c.document} » est validé : tous les validateurs ont donné leur accord.` : defaut;
  }

  /* ---------- Anomalies et réaffectation (Administrateur) ---------- */

  saisie(a: Anomalie): { employeId: string | null; motif: string } {
    return this.reaffectation[a.validateurId] ??= { employeId: null, motif: '' };
  }

  reaffecter(a: Anomalie): void {
    const s = this.saisie(a);
    if (!s.employeId || !s.motif.trim()) {
      this.notify.error('Choisissez le nouveau validateur et indiquez le motif.');
      return;
    }
    this.service.reaffecter(a.circuitId, a.validateurId, s.employeId, s.motif.trim()).subscribe({
      next: () => {
        delete this.reaffectation[a.validateurId];
        this.notify.success('Validateur réaffecté ; il est prévenu.');
        this.reload();
      },
      error: err => this.notify.error(err?.error?.message ?? 'Réaffectation impossible.'),
    });
  }

  libelleAnomalie(a: Anomalie): string {
    return LIBELLE_ANOMALIE[a.anomalie] ?? a.anomalie;
  }

  /* ---------- Libellés ---------- */

  libelleDecision(d: TypeDecision): string {
    return d === 'VALIDE' ? 'Validé' : d === 'REFUSE' ? 'Refusé' : 'Décision retirée';
  }

  libelleStatut(s: StatutCircuit): string {
    return LIBELLE_STATUT[s] ?? s;
  }

  badgeDecision(d: TypeDecision): string {
    return d === 'VALIDE' ? 'st-ok' : d === 'REFUSE' ? 'st-ko' : 'st-pending';
  }

  badgeStatut(s: StatutCircuit): string {
    return s === 'VALIDE' ? 'st-ok' : s === 'REFUSE' ? 'st-ko' : s === 'ANNULE' ? 'st-off' : 'st-pending';
  }
}
