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
import { DocumentService } from '../document.service';
import { DocumentItem } from '../document.model';
import { DocumentUpload } from '../document-upload/document-upload';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';

/**
 * Écran « Téléverser un document » (Phase 1) — dépôt de fichier, liste,
 * téléchargement et corbeille.
 */
@Component({
  selector: 'app-document-list',
  imports: [
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, SkeletonTable,
  ],
  templateUrl: './document-list.html',
  styleUrl: './document-list.scss',
})
export class DocumentList implements OnInit {
  private service = inject(DocumentService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  dataSource = new MatTableDataSource<DocumentItem>([]);
  selection = new SelectionModel<DocumentItem>(true, []);
  archiveView = signal(false);
  loading = signal(true);
  displayedColumns = ['select', 'id', 'name', 'type', 'workspace', 'size', 'expiration', 'actions'];

  @ViewChild(MatPaginator) set paginator(p: MatPaginator) { if (p) this.dataSource.paginator = p; }
  @ViewChild(MatSort) set sort(s: MatSort) { if (s) this.dataSource.sort = s; }

  ngOnInit(): void {
    this.dataSource.filterPredicate = (d, f) => (d.name + ' ' + (d.fileName ?? '')).toLowerCase().includes(f);
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
    const ids = this.selection.selected.map(d => d.id);
    if (!ids.length) return;
    const restoring = this.archiveView();
    this.confirm.ask({
      title: restoring ? 'Restaurer la sélection' : 'Supprimer la sélection',
      message: `Voulez-vous ${restoring ? 'restaurer' : 'supprimer'} ${ids.length} document(s) ?`,
      confirmLabel: restoring ? 'Restaurer' : 'Supprimer',
      danger: !restoring,
    }).subscribe(ok => {
      if (!ok) return;
      (restoring ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids)).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} document(s) ${restoring ? 'restauré(s)' : 'supprimé(s)'}.`); },
        error: () => this.notify.error('Opération impossible.'),
      });
    });
  }

  /* ---- actions ---- */
  upload(): void {
    const ref = this.dialog.open(DocumentUpload, { width: '560px', maxWidth: '95vw', autoFocus: false });
    ref.afterClosed().subscribe(issue => {
      if (!issue) return;
      this.load();
      // Un seul message, et qui dit ce qui s'est réellement passé : un document
      // déposé sans index ne doit pas être annoncé comme « indexé ».
      const messages: Record<string, string> = {
        'indexe': 'Document déposé, indexé, et circuit de signature lancé.',
        'sans-plan': "Document déposé et circuit lancé. Son type n'a pas de plan d'indexation : aucun index à renseigner.",
        'a-indexer': 'Document déposé et circuit lancé, mais il reste à indexer.',
      };
      const message = messages[issue as string];
      if (issue === 'a-indexer') this.notify.info(message);
      else this.notify.success(message);
    });
  }
  download(id: number): void {
    window.open(this.service.downloadUrl(id), '_blank');
  }
  remove(id: number, name: string): void {
    this.confirm.ask({
      title: 'Supprimer ce document',
      message: `« ${name} » sera déplacé vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.delete(id).subscribe({
        next: () => { this.load(); this.notify.success('Document supprimé.'); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }
  restoreOne(id: number): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success('Document restauré.'); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
}
