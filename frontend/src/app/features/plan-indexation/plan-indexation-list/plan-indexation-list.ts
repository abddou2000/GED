import { Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { FormsModule } from '@angular/forms';
import { SelectionModel } from '@angular/cdk/collections';
import { PlanIndexationService } from '../plan-indexation.service';
import { PlanIndexation } from '../plan-indexation.model';
import { PlanIndexationForm } from '../plan-indexation-form/plan-indexation-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';

/**
 * Écran « Plan d'indexation » — CRUD, corbeille, recherche. Création / édition
 * en boîte de dialogue.
 */
@Component({
  selector: 'app-plan-indexation-list',
  imports: [
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, SkeletonTable,
  ],
  templateUrl: './plan-indexation-list.html',
  styleUrl: './plan-indexation-list.scss',
})
export class PlanIndexationList implements OnInit {
  private service = inject(PlanIndexationService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  dataSource = new MatTableDataSource<PlanIndexation>([]);
  selection = new SelectionModel<PlanIndexation>(true, []);
  archiveView = signal(false);
  loading = signal(true);
  displayedColumns = ['select', 'id', 'code', 'nomDuPlan', 'indices', 'preview', 'actions'];

  @ViewChild(MatPaginator) set paginator(p: MatPaginator) { if (p) this.dataSource.paginator = p; }
  @ViewChild(MatSort) set sort(s: MatSort) { if (s) this.dataSource.sort = s; }

  ngOnInit(): void {
    this.dataSource.filterPredicate = (p, f) => (p.nomDuPlan + ' ' + p.code).toLowerCase().includes(f);
    this.load();
  }

  load(): void {
    this.selection.clear();
    this.loading.set(true);
    const call = this.archiveView() ? this.service.trashed(0, 1000, '') : this.service.list(0, 1000, '');
    call.subscribe({
      next: res => { this.dataSource.data = res.content; this.loading.set(false); },
      error: () => { this.loading.set(false); this.notify.error('Chargement impossible.'); },
    });
  }

  applySearch(v: string): void {
    this.dataSource.filter = (v ?? '').trim().toLowerCase();
  }

  toggleArchive(): void {
    this.archiveView.update(a => !a);
    this.load();
  }

  /* ---- sélection ---- */
  isAllSelected(): boolean {
    return this.dataSource.data.length > 0 &&
      this.selection.selected.length === this.dataSource.data.length;
  }
  toggleAll(): void {
    this.isAllSelected() ? this.selection.clear() : this.dataSource.data.forEach(r => this.selection.select(r));
  }
  bulk(): void {
    const ids = this.selection.selected.map(p => p.id);
    if (!ids.length) return;
    const restoring = this.archiveView();
    this.confirm.ask({
      title: restoring ? 'Restaurer la sélection' : 'Supprimer la sélection',
      message: `Voulez-vous ${restoring ? 'restaurer' : 'supprimer'} ${ids.length} plan(s) ?`,
      confirmLabel: restoring ? 'Restaurer' : 'Supprimer',
      danger: !restoring,
    }).subscribe(ok => {
      if (!ok) return;
      (restoring ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids)).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} plan(s) ${restoring ? 'restauré(s)' : 'supprimé(s)'}.`); },
        error: () => this.notify.error('Opération impossible.'),
      });
    });
  }

  /* ---- actions ---- */
  create(): void { this.openDialog(null); }
  edit(id: number): void {
    this.service.get(id).subscribe(p => this.openDialog(p));
  }
  private openDialog(p: PlanIndexation | null): void {
    const ref = this.dialog.open(PlanIndexationForm, {
      data: { plan: p }, width: '600px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => {
      if (!saved) return;
      this.load();
      this.notify.success(p ? "Plan d'indexation modifié." : "Plan d'indexation créé.");
    });
  }
  remove(id: number, name: string): void {
    this.confirm.ask({
      title: 'Supprimer ce plan',
      message: `« ${name} » sera déplacé vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.delete(id).subscribe({
        next: () => { this.load(); this.notify.success("Plan d'indexation supprimé."); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }
  restoreOne(id: number): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success("Plan d'indexation restauré."); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
}
