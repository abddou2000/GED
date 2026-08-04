import { Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { FormsModule } from '@angular/forms';
import { SelectionModel } from '@angular/cdk/collections';
import { WorkspaceService } from '../workspace.service';
import { SelectOption, TreeNode, WorkSpace } from '../workspace.model';
import { WorkspaceForm } from '../workspace-form/workspace-form';
import { StatTiles } from '../../../core/stat-tiles/stat-tiles';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';

/**
 * Écran « Espaces de travail » — 2 vues (Tableau / Arbre), CRUD, déplacement,
 * archivage, corbeille. Création / édition en boîte de dialogue.
 */
@Component({
  selector: 'app-workspace-list',
  imports: [
    NgTemplateOutlet, FormsModule, MatTableModule, MatPaginatorModule, MatSortModule,
    MatButtonModule, MatButtonToggleModule, MatIconModule, MatCheckboxModule, MatMenuModule,
    MatTooltipModule, MatDialogModule, StatTiles, SkeletonTable,
  ],
  templateUrl: './workspace-list.html',
  styleUrl: './workspace-list.scss',
})
export class WorkspaceList implements OnInit {
  private service = inject(WorkspaceService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  view = signal<'table' | 'tree'>('table');

  // ---- Vue Tableau ----
  dataSource = new MatTableDataSource<WorkSpace>([]);
  selection = new SelectionModel<WorkSpace>(true, []);
  archiveView = signal(false);
  loading = signal(true);
  displayedColumns = ['select', 'name', 'owner', 'workflow', 'status', 'actions'];

  @ViewChild(MatPaginator) set paginator(p: MatPaginator) { if (p) this.dataSource.paginator = p; }
  @ViewChild(MatSort) set sort(s: MatSort) { if (s) this.dataSource.sort = s; }

  // ---- Vue Arbre ----
  tree = signal<TreeNode[]>([]);
  expanded = signal<Set<number>>(new Set());
  parentOptions = signal<SelectOption[]>([]);

  ngOnInit(): void {
    this.dataSource.filterPredicate = (w, f) =>
      (w.name + ' ' + w.code).toLowerCase().includes(f);
    this.load();
  }

  /* =================== chargement =================== */
  load(): void {
    if (this.view() === 'tree') { this.loadTree(); return; }
    this.selection.clear();
    this.loading.set(true);
    const call = this.archiveView() ? this.service.trashed(0, 1000, '') : this.service.list(0, 1000, '');
    call.subscribe({
      next: res => { this.dataSource.data = res.content; this.loading.set(false); },
      error: () => { this.loading.set(false); this.notify.error('Chargement impossible.'); },
    });
  }

  loadTree(): void {
    this.service.tree().subscribe(t => this.tree.set(t));
    this.service.forSelect().subscribe(o => this.parentOptions.set(o));
  }

  setView(v: 'table' | 'tree'): void {
    this.view.set(v);
    this.load();
  }

  applySearch(v: string): void {
    this.dataSource.filter = (v ?? '').trim().toLowerCase();
  }

  toggleArchive(): void {
    this.archiveView.update(a => !a);
    this.load();
  }

  /* =================== sélection (tableau) =================== */
  isAllSelected(): boolean {
    return this.dataSource.data.length > 0 &&
      this.selection.selected.length === this.dataSource.data.length;
  }
  toggleAll(): void {
    this.isAllSelected() ? this.selection.clear() : this.dataSource.data.forEach(r => this.selection.select(r));
  }
  bulk(): void {
    const ids = this.selection.selected.map(w => w.id);
    if (!ids.length) return;
    const restoring = this.archiveView();
    this.confirm.ask({
      title: restoring ? 'Restaurer la sélection' : 'Supprimer la sélection',
      message: `Voulez-vous ${restoring ? 'restaurer' : 'supprimer'} ${ids.length} dossier(s) ?`,
      confirmLabel: restoring ? 'Restaurer' : 'Supprimer',
      danger: !restoring,
    }).subscribe(ok => {
      if (!ok) return;
      (restoring ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids)).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} dossier(s) ${restoring ? 'restauré(s)' : 'supprimé(s)'}.`); },
        error: () => this.notify.error('Opération impossible.'),
      });
    });
  }

  /* =================== arbre =================== */
  isExpanded(id: number): boolean { return this.expanded().has(id); }
  toggleExpand(id: number): void {
    const n = new Set(this.expanded());
    n.has(id) ? n.delete(id) : n.add(id);
    this.expanded.set(n);
  }

  /* =================== statut =================== */
  statusBadge(status: string): { label: string; cls: string } {
    if (status === 'ARCHIVE') return { label: 'Archivé', cls: 'st-arch' };
    if (status === 'INACTIF') return { label: 'Inactif', cls: 'st-inactif' };
    return { label: 'Actif', cls: 'st-actif' };
  }

  /* =================== présentation (avatars) =================== */
  initials(fullName: string): string {
    const parts = (fullName || '').trim().split(/\s+/);
    const a = parts[0]?.[0] ?? '';
    const b = parts.length > 1 ? parts[parts.length - 1][0] : '';
    return (a + b).toUpperCase() || '?';
  }
  avatarColor(seed: number): string {
    const palette = ['#16406b', '#1e7a46', '#9e1b32', '#a9791e', '#5b3fa0', '#0e7490'];
    return palette[(seed ?? 0) % palette.length];
  }

  /* =================== actions =================== */
  create(parentId: number | null = null): void { this.openDialog(null, parentId); }
  edit(id: number): void {
    this.service.get(id).subscribe(w => this.openDialog(w, null));
  }
  private openDialog(w: WorkSpace | null, parentId: number | null): void {
    const ref = this.dialog.open(WorkspaceForm, {
      data: { workspace: w, parentId }, width: '540px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => {
      if (!saved) return;
      this.load();
      this.notify.success(w ? 'Espace de travail modifié.' : 'Espace de travail créé.');
    });
  }

  remove(id: number, name: string): void {
    this.confirm.ask({
      title: 'Supprimer ce dossier',
      message: `« ${name} » sera déplacé vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.delete(id).subscribe({
        next: () => { this.load(); this.notify.success('Dossier supprimé.'); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }
  restoreOne(id: number): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success('Dossier restauré.'); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
  archiveOne(id: number): void {
    this.service.archive(id).subscribe({
      next: () => { this.load(); this.notify.success('Dossier archivé.'); },
      error: () => this.notify.error('Archivage impossible.'),
    });
  }
  moveTo(id: number, parentId: number | null): void {
    this.service.move(id, parentId).subscribe({
      next: () => { this.load(); this.notify.success('Dossier déplacé.'); },
      error: err => this.notify.error(err?.error?.message ?? 'Déplacement impossible.'),
    });
  }
}
