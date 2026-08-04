import { AfterViewInit, Component, ViewChild, effect, inject, signal } from '@angular/core';
import { SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SignatureService } from '../signature.service';
import { Signature, SignatureStatus } from '../signature.model';
import { SessionService } from '../../../core/session.service';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';

/**
 * Écran « Mes workflow » — les documents en attente de MA signature (approuver /
 * rejeter) et mon historique. L'identité vient de la session affichée dans la
 * barre supérieure : l'écran ne demande plus « au nom de qui » agir.
 */
@Component({
  selector: 'app-mes-workflow',
  imports: [
    SlicePipe, FormsModule, MatTableModule, MatButtonModule, MatButtonToggleModule,
    MatIconModule, MatTooltipModule,
    MatPaginatorModule, SkeletonTable,
  ],
  templateUrl: './mes-workflow.html',
  styleUrl: './mes-workflow.scss',
})
export class MesWorkflow implements AfterViewInit {
  private service = inject(SignatureService);
  private session = inject(SessionService);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  view = signal<'pending' | 'history'>('pending');
  pending = signal<Signature[]>([]);
  history = signal<Signature[]>([]);
  loading = signal(true);

  /**
   * Une source de données paginée par vue. Les deux tableaux ne sont jamais
   * affichés en même temps, mais chacun garde sa propre page courante : basculer
   * vers l'historique puis revenir ne doit pas renvoyer l'opérateur en page 1.
   */
  readonly dsPending = new MatTableDataSource<Signature>([]);
  readonly dsHistory = new MatTableDataSource<Signature>([]);

  @ViewChild('pagPending') pagPending?: MatPaginator;
  @ViewChild('pagHistory') pagHistory?: MatPaginator;

  /** Page courante mémorisée par vue : changer d'onglet détruit le paginateur,
      qui repartirait donc de la page 1 à chaque aller-retour. */
  pagePending = signal(0);
  pageHistory = signal(0);

  ngAfterViewInit(): void { this.brancherPagination(); }

  /** Le paginateur n'existe qu'une fois sa vue rendue (@if sur la vue courante). */
  private brancherPagination(): void {
    if (this.pagPending) this.dsPending.paginator = this.pagPending;
    if (this.pagHistory) this.dsHistory.paginator = this.pagHistory;
  }

  /** Mémorise la page quittée pour la restituer au retour sur l'onglet. */
  onPage(vue: 'pending' | 'history', index: number): void {
    (vue === 'pending' ? this.pagePending : this.pageHistory).set(index);
  }

  readonly pendingCols = ['document', 'type', 'workspace', 'step', 'actions'];
  readonly historyCols = ['document', 'type', 'step', 'status', 'date', 'motif'];

  constructor() {
    // L'identité vient de la session (barre supérieure), plus d'un sélecteur :
    // elle est résolue de façon asynchrone, d'où l'effet plutôt qu'un ngOnInit.
    this.session.ensureUser();
    effect(() => {
      const id = this.session.user()?.id ?? null;
      if (id == null) return;
      this.reload();
    });
  }

  /** Identifiant de l'utilisateur au nom duquel on signe. */
  private acteur(): number | null { return this.session.user()?.id ?? null; }

  setView(v: 'pending' | 'history'): void {
    this.view.set(v);
    // La vue change, donc le tableau — et son paginateur — vient d'être créé.
    setTimeout(() => this.brancherPagination());
  }

  reload(): void {
    const id = this.acteur();
    if (id == null) return;
    this.loading.set(true);
    this.service.pending(id).subscribe({
      next: l => {
        this.pending.set(l);
        this.dsPending.data = l;
        this.loading.set(false);
        setTimeout(() => this.brancherPagination());
      },
      error: () => { this.loading.set(false); this.notify.error('Chargement impossible.'); },
    });
    this.service.history(id).subscribe(l => {
      this.history.set(l);
      this.dsHistory.data = l;
      setTimeout(() => this.brancherPagination());
    });
  }

  approve(sig: Signature): void {
    const id = this.acteur();
    if (id == null) return;
    this.confirm.ask({
      title: 'Signer le document',
      message: `Confirmez-vous la signature de « ${sig.document?.label} » (étape ${sig.stepOrder} · ${sig.stepLabel}) ?`,
      confirmLabel: 'Signer',
    }).subscribe(ok => {
      if (!ok) return;
      this.service.approve(sig.id, id, null).subscribe({
        next: () => { this.reload(); this.notify.success('Document signé.'); },
        error: err => this.notify.error(err?.error?.message ?? 'Approbation impossible.'),
      });
    });
  }

  reject(sig: Signature): void {
    const id = this.acteur();
    if (id == null) return;
    this.confirm.askText({
      title: 'Rejeter la signature',
      message: `Indiquez le motif du rejet de « ${sig.document?.label} ».`,
      promptLabel: 'Motif du rejet',
      promptPlaceholder: 'Ex. montant erroné, pièce manquante…',
      promptRequired: true,
      confirmLabel: 'Rejeter',
      danger: true,
    }).subscribe(motif => {
      if (motif == null) return;
      this.service.reject(sig.id, id, motif).subscribe({
        next: () => { this.reload(); this.notify.success('Signature rejetée.'); },
        error: err => this.notify.error(err?.error?.message ?? 'Rejet impossible.'),
      });
    });
  }

  statusBadge(status: SignatureStatus): { label: string; cls: string } {
    if (status === 'SIGNED') return { label: 'Signé', cls: 'st-ok' };
    if (status === 'REJECTED') return { label: 'Rejeté', cls: 'st-ko' };
    return { label: 'En attente', cls: 'st-pending' };
  }
}
