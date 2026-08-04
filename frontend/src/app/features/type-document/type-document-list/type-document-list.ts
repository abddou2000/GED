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
import { TypeDocumentService } from '../type-document.service';
import { TypeDocument } from '../type-document.model';
import { TypeDocumentForm } from '../type-document-form/type-document-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';

/**
 * Écran « Type de document » — CRUD, corbeille, recherche. Création / édition
 * en boîte de dialogue.
 */
@Component({
  selector: 'app-type-document-list',
  imports: [
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, SkeletonTable,
  ],
  templateUrl: './type-document-list.html',
  styleUrl: './type-document-list.scss',
})
export class TypeDocumentList implements OnInit {
  private service = inject(TypeDocumentService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  dataSource = new MatTableDataSource<TypeDocument>([]);
  selection = new SelectionModel<TypeDocument>(true, []);
  archiveView = signal(false);
  loading = signal(true);
  displayedColumns = ['select', 'id', 'code', 'type', 'workspace', 'plan', 'formats', 'taille', 'actions'];

  @ViewChild(MatPaginator) set paginator(p: MatPaginator) { if (p) this.dataSource.paginator = p; }
  @ViewChild(MatSort) set sort(s: MatSort) { if (s) this.dataSource.sort = s; }

  ngOnInit(): void {
    this.dataSource.filterPredicate = (t, f) => (t.typeDeDocument + ' ' + t.code).toLowerCase().includes(f);
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
    const ids = this.selection.selected.map(t => t.id);
    if (!ids.length) return;
    const restoring = this.archiveView();
    this.confirm.ask({
      title: restoring ? 'Restaurer la sélection' : 'Supprimer la sélection',
      message: `Voulez-vous ${restoring ? 'restaurer' : 'supprimer'} ${ids.length} type(s) ?`,
      confirmLabel: restoring ? 'Restaurer' : 'Supprimer',
      danger: !restoring,
    }).subscribe(ok => {
      if (!ok) return;
      (restoring ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids)).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} type(s) ${restoring ? 'restauré(s)' : 'supprimé(s)'}.`); },
        error: () => this.notify.error('Opération impossible.'),
      });
    });
  }

  /* ---- actions ---- */
  create(): void { this.openDialog(null); }
  edit(id: number): void {
    this.service.get(id).subscribe(t => this.openDialog(t));
  }
  private openDialog(t: TypeDocument | null): void {
    const ref = this.dialog.open(TypeDocumentForm, {
      data: { type: t }, width: '620px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => {
      if (!saved) return;
      this.load();
      this.notify.success(t ? 'Type de document modifié.' : 'Type de document créé.');
    });
  }
  remove(id: number, name: string): void {
    this.confirm.ask({
      title: 'Supprimer ce type',
      message: `« ${name} » sera déplacé vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.delete(id).subscribe({
        next: () => { this.load(); this.notify.success('Type de document supprimé.'); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }
  restoreOne(id: number): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success('Type de document restauré.'); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
}
