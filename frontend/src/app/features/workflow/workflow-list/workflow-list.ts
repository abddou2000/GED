import { AfterViewInit, Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatMenuModule } from '@angular/material/menu';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { SelectionModel } from '@angular/cdk/collections';
import { WorkflowService } from '../workflow.service';
import { Workflow } from '../workflow.model';
import { WorkflowForm } from '../workflow-form/workflow-form';

/**
 * Écran « Règles de Workflow » — liste (mat-table : tri, pagination, filtre,
 * sélection, toggle colonnes) + création/édition en boîte de dialogue.
 */
@Component({
  selector: 'app-workflow-list',
  imports: [
    MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule, MatIconModule,
    MatCheckboxModule, MatMenuModule, MatFormFieldModule, MatInputModule,
    MatTooltipModule, MatDialogModule,
  ],
  templateUrl: './workflow-list.html',
  styleUrl: './workflow-list.scss',
})
export class WorkflowList implements OnInit, AfterViewInit {
  private service = inject(WorkflowService);
  private dialog = inject(MatDialog);

  dataSource = new MatTableDataSource<Workflow>([]);
  selection = new SelectionModel<Workflow>(true, []);
  loading = signal(false);
  archiveView = signal(false);
  fullscreen = signal(false);

  readonly allColumns = [
    { key: 'id', label: 'ID' },
    { key: 'name', label: 'Nom' },
    { key: 'steps', label: 'Steps' },
    { key: 'workspaces', label: 'Workspaces' },
  ];
  visible = signal<Record<string, boolean>>({ id: true, name: true, steps: true, workspaces: true });

  displayedColumns = computed(() => [
    'select',
    ...this.allColumns.filter(c => this.visible()[c.key]).map(c => c.key),
    'actions',
  ]);

  @ViewChild(MatPaginator) paginator!: MatPaginator;
  @ViewChild(MatSort) sort!: MatSort;

  ngOnInit(): void {
    this.dataSource.filterPredicate = (w, f) => w.name.toLowerCase().includes(f);
    this.load();
  }

  ngAfterViewInit(): void {
    this.dataSource.paginator = this.paginator;
    this.dataSource.sort = this.sort;
  }

  load(): void {
    this.loading.set(true);
    this.selection.clear();
    const call = this.archiveView()
      ? this.service.trashed(0, 1000, '')
      : this.service.list(0, 1000, '');
    call.subscribe({
      next: res => { this.dataSource.data = res.content; this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  applySearch(value: string): void {
    this.dataSource.filter = (value ?? '').trim().toLowerCase();
  }

  toggleArchive(): void {
    this.archiveView.update(v => !v);
    this.load();
  }

  toggleCol(key: string): void {
    this.visible.update(v => ({ ...v, [key]: !v[key] }));
  }

  isAllSelected(): boolean {
    return this.dataSource.data.length > 0 &&
      this.selection.selected.length === this.dataSource.data.length;
  }

  toggleAll(): void {
    this.isAllSelected()
      ? this.selection.clear()
      : this.dataSource.data.forEach(r => this.selection.select(r));
  }

  create(): void { this.openDialog(null); }
  edit(w: Workflow): void { this.openDialog(w); }

  private openDialog(w: Workflow | null): void {
    const ref = this.dialog.open(WorkflowForm, {
      data: { workflow: w }, width: '660px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => { if (saved) this.load(); });
  }

  remove(w: Workflow): void {
    if (!confirm(`Supprimer « ${w.name} » ?`)) return;
    this.service.delete(w.id).subscribe(() => this.load());
  }

  restoreOne(w: Workflow): void {
    this.service.restore(w.id).subscribe(() => this.load());
  }

  bulk(): void {
    const ids = this.selection.selected.map(w => w.id);
    if (!ids.length) return;
    const verb = this.archiveView() ? 'restaurer' : 'supprimer';
    if (!confirm(`Voulez-vous ${verb} ${ids.length} élément(s) ?`)) return;
    const action = this.archiveView()
      ? this.service.multipleRestore(ids)
      : this.service.multipleDelete(ids);
    action.subscribe(() => this.load());
  }
}
