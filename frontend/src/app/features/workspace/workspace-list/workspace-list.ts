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

/**
 * Écran « Espaces de travail » — 2 vues (Tableau / Arbre), CRUD, déplacement,
 * archivage, corbeille. Création / édition en boîte de dialogue.
 */
@Component({
  selector: 'app-workspace-list',
  imports: [
    NgTemplateOutlet, FormsModule, MatTableModule, MatPaginatorModule, MatSortModule,
    MatButtonModule, MatButtonToggleModule, MatIconModule, MatCheckboxModule, MatMenuModule,
    MatTooltipModule, MatDialogModule,
  ],
  templateUrl: './workspace-list.html',
  styleUrl: './workspace-list.scss',
})
export class WorkspaceList implements OnInit {
  private service = inject(WorkspaceService);
  private dialog = inject(MatDialog);

  view = signal<'table' | 'tree'>('table');

  // ---- Vue Tableau ----
  dataSource = new MatTableDataSource<WorkSpace>([]);
  selection = new SelectionModel<WorkSpace>(true, []);
  archiveView = signal(false);
  displayedColumns = ['select', 'id', 'code', 'name', 'parent', 'workflow', 'owner', 'status', 'actions'];

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
    const call = this.archiveView() ? this.service.trashed(0, 1000, '') : this.service.list(0, 1000, '');
    call.subscribe(res => this.dataSource.data = res.content);
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
    const verb = this.archiveView() ? 'restaurer' : 'supprimer';
    if (!confirm(`Voulez-vous ${verb} ${ids.length} dossier(s) ?`)) return;
    (this.archiveView() ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids))
      .subscribe(() => this.load());
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

  /* =================== actions =================== */
  create(parentId: number | null = null): void { this.openDialog(null, parentId); }
  edit(id: number): void {
    this.service.get(id).subscribe(w => this.openDialog(w, null));
  }
  private openDialog(w: WorkSpace | null, parentId: number | null): void {
    const ref = this.dialog.open(WorkspaceForm, {
      data: { workspace: w, parentId }, width: '540px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => { if (saved) this.load(); });
  }

  remove(id: number, name: string): void {
    if (!confirm(`Supprimer « ${name} » ?`)) return;
    this.service.delete(id).subscribe(() => this.load());
  }
  restoreOne(id: number): void {
    this.service.restore(id).subscribe(() => this.load());
  }
  archiveOne(id: number): void {
    this.service.archive(id).subscribe(() => this.load());
  }
  moveTo(id: number, parentId: number | null): void {
    this.service.move(id, parentId).subscribe({
      next: () => this.load(),
      error: err => alert(err?.error?.message ?? 'Déplacement impossible.'),
    });
  }
}
