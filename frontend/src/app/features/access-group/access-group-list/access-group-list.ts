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
import { AccessGroupService } from '../access-group.service';
import { AccessGroup, RIGHT_KEYS } from '../access-group.model';
import { AccessGroupForm } from '../access-group-form/access-group-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';

/**
 * Écran « Groupe d'accès » — CRUD, corbeille, recherche. Création / édition
 * en boîte de dialogue (3 sections + cascade des droits).
 */
@Component({
  selector: 'app-access-group-list',
  imports: [
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, SkeletonTable,
  ],
  templateUrl: './access-group-list.html',
  styleUrl: './access-group-list.scss',
})
export class AccessGroupList implements OnInit {
  private service = inject(AccessGroupService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  dataSource = new MatTableDataSource<AccessGroup>([]);
  selection = new SelectionModel<AccessGroup>(true, []);
  archiveView = signal(false);
  loading = signal(true);
  displayedColumns = ['select', 'id', 'name', 'rights', 'workspaces', 'users', 'actions'];

  @ViewChild(MatPaginator) set paginator(p: MatPaginator) { if (p) this.dataSource.paginator = p; }
  @ViewChild(MatSort) set sort(s: MatSort) { if (s) this.dataSource.sort = s; }

  ngOnInit(): void {
    this.dataSource.filterPredicate = (g, f) => (g.name + ' ' + g.code).toLowerCase().includes(f);
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
    const ids = this.selection.selected.map(g => g.id);
    if (!ids.length) return;
    const restoring = this.archiveView();
    this.confirm.ask({
      title: restoring ? 'Restaurer la sélection' : 'Supprimer la sélection',
      message: `Voulez-vous ${restoring ? 'restaurer' : 'supprimer'} ${ids.length} groupe(s) ?`,
      confirmLabel: restoring ? 'Restaurer' : 'Supprimer',
      danger: !restoring,
    }).subscribe(ok => {
      if (!ok) return;
      (restoring ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids)).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} groupe(s) ${restoring ? 'restauré(s)' : 'supprimé(s)'}.`); },
        error: () => this.notify.error('Opération impossible.'),
      });
    });
  }

  /** Libellés des droits actifs (pour les chips de la colonne Droits). */
  activeRights(g: AccessGroup): string[] {
    return RIGHT_KEYS.filter(r => g.rights[r.key]).map(r => r.label);
  }

  /* ---- présentation (avatars membres) ---- */
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

  /* ---- actions ---- */
  create(): void { this.openDialog(null); }
  edit(id: number): void {
    this.service.get(id).subscribe(g => this.openDialog(g));
  }
  private openDialog(g: AccessGroup | null): void {
    const ref = this.dialog.open(AccessGroupForm, {
      data: { group: g }, width: '620px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => {
      if (!saved) return;
      this.load();
      this.notify.success(g ? "Groupe d'accès modifié." : "Groupe d'accès créé.");
    });
  }
  remove(id: number, name: string): void {
    this.confirm.ask({
      title: 'Supprimer ce groupe',
      message: `« ${name} » sera déplacé vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.delete(id).subscribe({
        next: () => { this.load(); this.notify.success("Groupe d'accès supprimé."); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }
  restoreOne(id: number): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success("Groupe d'accès restauré."); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
}
