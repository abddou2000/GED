import { AfterViewInit, Component, ViewChild, effect, inject, signal } from '@angular/core';
import { SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { SelectionModel } from '@angular/cdk/collections';
import { forkJoin } from 'rxjs';
import { SignatureService } from '../signature.service';
import { Signature, SignatureStatus } from '../signature.model';
import { SessionService } from '../../../core/session.service';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { ColumnPicker } from '../../../core/column-picker/column-picker';
import { ColonneDef } from '../../../core/column-prefs.service';
import { SelectionToggle } from '../../../core/selection-toggle/selection-toggle';

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
    MatPaginatorModule, SkeletonTable, ColumnPicker, SelectionToggle, MatCheckboxModule,
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

  /* ---------- Colonnes réglables ----------
     Deux catalogues, donc deux clés de préférences : les colonnes de la liste à
     traiter n'ont rien à voir avec celles de l'historique, et une clé commune
     ferait qu'un masquage dans l'une déréglerait l'autre.

     « Document » et « Actions » sont marquées `toujours` : sans le nom on ne
     sait plus de quoi on parle, et sans les actions la liste ne sert à rien. */
  readonly ECRAN_PENDING = 'Mes workflow - a traiter';
  readonly ECRAN_HISTORY = 'Mes workflow - historique';

  readonly COLONNES_PENDING: ColonneDef[] = [
    { cle: 'select', libelle: '', toujours: true },
    { cle: 'document', libelle: 'Document', toujours: true },
    { cle: 'type', libelle: 'Type' },
    { cle: 'workspace', libelle: 'Espace de travail' },
    { cle: 'step', libelle: 'Étape' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];

  readonly COLONNES_HISTORY: ColonneDef[] = [
    { cle: 'document', libelle: 'Document', toujours: true },
    { cle: 'type', libelle: 'Type' },
    { cle: 'step', libelle: 'Étape' },
    { cle: 'status', libelle: 'Statut' },
    { cle: 'date', libelle: 'Date' },
    { cle: 'motif', libelle: 'Motif' },
    /* Colonne d'action de l'historique : elle ne porte que la relance d'un
       circuit rejeté, seule issue d'un refus. `toujours` — la masquer rendrait
       cette sortie introuvable. */
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];

  /* Le sélecteur de colonnes pousse la liste visible dans ces signaux ; les
     valeurs posées ici ne servent qu'au tout premier rendu, avant qu'il ait
     émis. */
  readonly pendingCols = signal<string[]>(['select', 'document', 'type', 'workspace', 'step', 'actions']);
  readonly historyCols = signal<string[]>(['document', 'type', 'step', 'status', 'date', 'motif', 'actions']);

  /* ---------- Mode sélection ----------
     Les cases à cocher n'apparaissent que sur demande, comme sur les autres
     écrans de liste. Quitter le mode vide la sélection : des lignes cochées
     mais devenues invisibles exposeraient à signer des documents qu'on ne voit
     plus. */
  readonly modeSelection = signal(false);
  readonly selection = new SelectionModel<Signature>(true, []);

  basculerSelection(actif: boolean): void {
    this.modeSelection.set(actif);
    if (!actif) this.selection.clear();
  }

  /** « Tout » = la page affichée, filtre compris — pas la liste entière. */
  private lignesVisibles(): Signature[] {
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

  /* ---------- Recherche ----------
     Le filtrage est LOCAL, et c'est correct ici : l'API sert les deux listes
     en entier (ce sont mes signatures, pas tout le référentiel). Interroger le
     serveur à chaque frappe ferait un aller-retour pour filtrer une liste
     déjà en mémoire. */
  readonly recherche = signal('');

  constructor() {
    /* Le filtre porte sur ce que l'opérateur voit à l'écran — nom du document,
       type, dossier, étape, statut, motif — et non sur l'objet entier : le
       prédicat par défaut de Material sérialise tout, identifiants compris, et
       « 12 » y ramènerait des lignes sans aucun 12 visible. */
    const predicat = (s: Signature, filtre: string): boolean => {
      const champs = [
        s.document?.label, s.type, s.workspace, s.stepLabel,
        this.statusBadge(s.status).label, s.motif,
      ];
      return champs.some(c => (c ?? '').toLowerCase().includes(filtre));
    };
    this.dsPending.filterPredicate = predicat;
    this.dsHistory.filterPredicate = predicat;

    /* L'identité n'est plus transmise à l'API : le serveur la lit dans le jeton.
       La session reste observée pour une autre raison — changer d'utilisateur
       doit recharger la liste, sans quoi l'écran continuerait d'afficher les
       signatures du précédent. */
    this.session.ensureUser();
    effect(() => {
      const id = this.session.user()?.id ?? null;
      if (id == null) return;
      this.reload();
    });
  }

  setView(v: 'pending' | 'history'): void {
    this.view.set(v);
    // La vue change, donc le tableau — et son paginateur — vient d'être créé.
    setTimeout(() => this.brancherPagination());
  }

  /**
   * Filtre les deux vues d'un coup. Ne filtrer que la vue courante donnerait
   * un onglet « Historique » qui ignore la recherche affichée juste au-dessus
   * de lui.
   */
  applySearch(valeur: string): void {
    const filtre = (valeur ?? '').trim().toLowerCase();
    this.recherche.set(filtre);
    this.dsPending.filter = filtre;
    this.dsHistory.filter = filtre;
    // Un filtre qui réduit la liste à trois lignes doit ramener en page 1 :
    // sinon on reste sur une page 4 devenue vide.
    this.dsPending.paginator?.firstPage();
    this.dsHistory.paginator?.firstPage();
    this.pagePending.set(0);
    this.pageHistory.set(0);
  }

  reload(): void {
    /* Les objets rechargés sont de NOUVELLES instances : une sélection gardée
       pointerait sur des lignes qui ne sont plus dans le tableau. */
    this.selection.clear();
    this.loading.set(true);
    this.service.pending().subscribe({
      next: l => {
        this.pending.set(l);
        this.dsPending.data = l;
        // Réaffecter `data` remet le filtre à zéro : on le repose.
        this.dsPending.filter = this.recherche();
        this.loading.set(false);
        setTimeout(() => this.brancherPagination());
      },
      error: () => { this.loading.set(false); this.notify.error('Chargement impossible.'); },
    });
    this.service.history().subscribe(l => {
      this.history.set(l);
      this.dsHistory.data = l;
      this.dsHistory.filter = this.recherche();
      setTimeout(() => this.brancherPagination());
    });
  }

  /* ---------- Actions groupées ----------
     Une signature engage : la confirmation NOMME les documents concernés au
     lieu d'annoncer un nombre. « Signer 7 documents ? » ne permet pas de
     vérifier qu'on n'a pas coché une ligne de trop. */
  signerLot(): void {
    const lot = this.selection.selected;
    if (!lot.length) return;
    this.confirm.ask({
      title: `Signer ${lot.length} document(s)`,
      message: `Confirmez-vous la signature de :\n${this.listerLot(lot)}`,
      confirmLabel: 'Signer',
    }).subscribe(ok => {
      if (!ok) return;
      this.executerLot(lot.map(s => this.service.approve(s.id, null)), 'signé');
    });
  }

  rejeterLot(): void {
    const lot = this.selection.selected;
    if (!lot.length) return;
    this.confirm.askText({
      title: `Rejeter ${lot.length} document(s)`,
      message: `Indiquez le motif du rejet de :\n${this.listerLot(lot)}`,
      promptLabel: 'Motif du rejet',
      promptPlaceholder: 'Ex. montant erroné, pièce manquante…',
      promptRequired: true,
      confirmLabel: 'Rejeter',
      danger: true,
    }).subscribe(motif => {
      if (motif == null) return;
      this.executerLot(lot.map(s => this.service.reject(s.id, motif)), 'rejeté');
    });
  }

  /** Au plus cinq noms, puis un décompte : une liste de trente noms n'est plus
      lisible et déborde de la boîte de confirmation. */
  private listerLot(lot: Signature[]): string {
    const noms = lot.slice(0, 5).map(s => `• ${s.document?.label ?? 'document'}`);
    if (lot.length > 5) noms.push(`• … et ${lot.length - 5} autre(s)`);
    return noms.join('\n');
  }

  /**
   * Exécute les appels en parallèle et ne rend la main qu'une fois TOUS
   * terminés. Recharger après chaque réponse ferait clignoter la liste et
   * ferait perdre le compte de ce qui a réellement abouti.
   */
  private executerLot(appels: ReturnType<SignatureService['approve']>[], verbe: string): void {
    this.loading.set(true);
    forkJoin(appels).subscribe({
      next: resultats => {
        this.selection.clear();
        this.notify.success(`${resultats.length} document(s) ${verbe}(s).`);
        this.reload();
      },
      error: err => {
        /* Un échec partiel est possible : on recharge pour montrer l'état réel
           plutôt que de laisser croire que rien n'est passé. */
        this.loading.set(false);
        this.notify.error(err?.error?.message ?? 'Action groupée incomplète.');
        this.reload();
      },
    });
  }

  approve(sig: Signature): void {
    this.confirm.ask({
      title: 'Signer le document',
      message: `Confirmez-vous la signature de « ${sig.document?.label} » (étape ${sig.stepOrder} · ${sig.stepLabel}) ?`,
      confirmLabel: 'Signer',
    }).subscribe(ok => {
      if (!ok) return;
      this.service.approve(sig.id, null).subscribe({
        next: () => { this.reload(); this.notify.success('Document signé.'); },
        error: err => this.notify.error(err?.error?.message ?? 'Approbation impossible.'),
      });
    });
  }

  reject(sig: Signature): void {
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
      this.service.reject(sig.id, motif).subscribe({
        next: () => { this.reload(); this.notify.success('Signature rejetée.'); },
        error: err => this.notify.error(err?.error?.message ?? 'Rejet impossible.'),
      });
    });
  }

  /**
   * Relance un circuit arrêté par un rejet.
   *
   * <p>C'est la seule porte de sortie d'un refus. Sans elle, l'étape restait
   * rejetée pour toujours et le document quittait toutes les files : une
   * facture refusée une fois était perdue, il fallait la redéposer. La
   * confirmation nomme le document et rappelle ce qui se passe ensuite — le
   * circuit repart à son début, pas à l'étape refusée.</p>
   */
  relancer(sig: Signature): void {
    const documentId = sig.document?.id;
    if (documentId == null) return;
    this.confirm.ask({
      title: 'Relancer la validation',
      message: `Le circuit de « ${sig.document?.label} » est arrêté par un rejet.\n`
             + `Le relancer remet l'étape refusée à traiter ; les étapes déjà signées le restent.`,
      confirmLabel: 'Relancer',
    }).subscribe(ok => {
      if (!ok) return;
      this.service.relancer(documentId).subscribe({
        next: () => { this.reload(); this.notify.success('Circuit relancé.'); },
        error: err => this.notify.error(err?.error?.message ?? 'Relance impossible.'),
      });
    });
  }

  statusBadge(status: SignatureStatus): { label: string; cls: string } {
    if (status === 'SIGNED') return { label: 'Signé', cls: 'st-ok' };
    if (status === 'REJECTED') return { label: 'Rejeté', cls: 'st-ko' };
    return { label: 'En attente', cls: 'st-pending' };
  }
}
