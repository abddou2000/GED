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
import { IndexService } from '../index.service';
import { IndexField, fieldTypeLabel } from '../index.model';
import { IndexForm } from '../index-form/index-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';

/**
 * Écran « Index » — CRUD, corbeille, recherche. Création / édition en boîte de dialogue.
 */
@Component({
  selector: 'app-index-list',
  imports: [
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, SkeletonTable,
  ],
  templateUrl: './index-list.html',
  styleUrl: './index-list.scss',
})
export class IndexList implements OnInit {
  private service = inject(IndexService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  dataSource = new MatTableDataSource<IndexField>([]);
  selection = new SelectionModel<IndexField>(true, []);
  archiveView = signal(false);
  loading = signal(true);
  displayedColumns = ['select', 'id', 'code', 'nomIndex', 'type', 'options', 'actions'];

  @ViewChild(MatPaginator) set paginator(p: MatPaginator) { if (p) this.dataSource.paginator = p; }
  @ViewChild(MatSort) set sort(s: MatSort) { if (s) this.dataSource.sort = s; }

  ngOnInit(): void {
    this.dataSource.filterPredicate = (x, f) => (x.nomIndex + ' ' + x.code).toLowerCase().includes(f);
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
    const ids = this.selection.selected.map(x => x.id);
    if (!ids.length) return;
    const restoring = this.archiveView();
    this.confirm.ask({
      title: restoring ? 'Restaurer la sélection' : 'Supprimer la sélection',
      message: `Voulez-vous ${restoring ? 'restaurer' : 'supprimer'} ${ids.length} index ?`,
      confirmLabel: restoring ? 'Restaurer' : 'Supprimer',
      danger: !restoring,
    }).subscribe(ok => {
      if (!ok) return;
      (restoring ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids)).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} index ${restoring ? 'restauré(s)' : 'supprimé(s)'}.`); },
        error: () => this.notify.error('Opération impossible.'),
      });
    });
  }

  /* ---- affichage ---- */
  typeLabel(x: IndexField): string {
    return fieldTypeLabel(x.fieldType);
  }
  options(x: IndexField): string[] {
    const o: string[] = [];
    if (x.obligatoire) o.push('Obligatoire');
    if (x.indexePourRecherche) o.push('Recherche');
    if (x.indexDeGroupage) o.push('Groupage');
    return o;
  }

  /* ---- actions ---- */
  create(): void { this.openDialog(null); }
  edit(id: number): void {
    this.service.get(id).subscribe(x => this.openDialog(x));
  }
  private openDialog(x: IndexField | null): void {
    const ref = this.dialog.open(IndexForm, {
      data: { index: x }, width: '560px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => {
      if (!saved) return;
      this.load();
      this.notify.success(x ? 'Index modifié.' : 'Index créé.');
    });
  }
  remove(id: number, name: string): void {
    this.confirm.ask({
      title: 'Supprimer cet index',
      message: `« ${name} » sera déplacé vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.delete(id).subscribe({
        next: () => { this.load(); this.notify.success('Index supprimé.'); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }
  restoreOne(id: number): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success('Index restauré.'); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
}
