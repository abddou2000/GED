import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { WorkflowService } from '../workflow.service';
import { Workflow } from '../workflow.model';
import { WorkflowForm } from '../workflow-form/workflow-form';

type Tab = 'toutes' | 'actives' | 'brouillons' | 'archivees';

@Component({
  selector: 'app-workflow-list',
  imports: [
    FormsModule, MatButtonModule, MatIconModule, MatMenuModule, MatCheckboxModule,
    MatSelectModule, MatFormFieldModule, MatTooltipModule, MatDialogModule,
  ],
  templateUrl: './workflow-list.html',
  styleUrl: './workflow-list.scss',
})
export class WorkflowList implements OnInit {
  private service = inject(WorkflowService);
  private dialog = inject(MatDialog);

  private activeItems = signal<Workflow[]>([]);
  private trashedItems = signal<Workflow[]>([]);
  loading = signal(false);

  /** Tous les enregistrements, les archivés portant un drapeau. */
  private all = computed<Workflow[]>(() => [
    ...this.activeItems(),
    ...this.trashedItems().map(w => ({ ...w, archived: true })),
  ]);

  // filtres / onglets / pagination
  tab = signal<Tab>('toutes');
  search = signal('');
  statut = signal('Tous');
  espace = signal('Tous');
  tri = signal('recent');
  page = signal(0);
  pageSize = signal(10);

  // sélection (par id)
  selected = signal<Set<number>>(new Set());

  counts = computed(() => ({
    toutes: this.all().length,
    actives: this.activeItems().filter(w => w.status === 'ACTIVE').length,
    brouillons: this.activeItems().filter(w => w.status === 'DRAFT').length,
    archivees: this.trashedItems().length,
  }));

  espaces = computed(() => {
    const set = new Set<string>();
    this.all().forEach(w => { if (w.espaceDeTravail) set.add(w.espaceDeTravail); });
    return ['Tous', ...[...set].sort()];
  });

  private filtered = computed<Workflow[]>(() => {
    let rows = this.all();
    switch (this.tab()) {
      case 'actives': rows = rows.filter(w => !w.archived && w.status === 'ACTIVE'); break;
      case 'brouillons': rows = rows.filter(w => !w.archived && w.status === 'DRAFT'); break;
      case 'archivees': rows = rows.filter(w => w.archived); break;
    }
    const s = this.search().trim().toLowerCase();
    if (s) rows = rows.filter(w => w.name.toLowerCase().includes(s));

    if (this.statut() !== 'Tous') {
      if (this.statut() === 'Archivée') rows = rows.filter(w => w.archived);
      else if (this.statut() === 'Active') rows = rows.filter(w => !w.archived && w.status === 'ACTIVE');
      else if (this.statut() === 'Brouillon') rows = rows.filter(w => !w.archived && w.status === 'DRAFT');
    }
    if (this.espace() !== 'Tous') rows = rows.filter(w => w.espaceDeTravail === this.espace());

    rows = [...rows];
    if (this.tri() === 'recent') rows.sort((a, b) => this.ts(b) - this.ts(a));
    else if (this.tri() === 'ancien') rows.sort((a, b) => this.ts(a) - this.ts(b));
    else if (this.tri() === 'nom') rows.sort((a, b) => a.name.localeCompare(b.name));
    return rows;
  });

  /** Convertit « dd/MM/yyyy HH:mm » en timestamp comparable. */
  private ts(w: Workflow): number {
    if (!w.lastModified) return 0;
    const [d, t] = w.lastModified.split(' ');
    const [day, mon, yr] = d.split('/').map(Number);
    const [h, mi] = (t ?? '0:0').split(':').map(Number);
    return new Date(yr, mon - 1, day, h, mi).getTime();
  }

  total = computed(() => this.filtered().length);
  totalPages = computed(() => Math.max(1, Math.ceil(this.total() / this.pageSize())));
  pages = computed(() => Array.from({ length: this.totalPages() }, (_, i) => i));
  pageRows = computed(() => {
    const start = this.page() * this.pageSize();
    return this.filtered().slice(start, start + this.pageSize());
  });
  rangeStart = computed(() => (this.total() === 0 ? 0 : this.page() * this.pageSize() + 1));
  rangeEnd = computed(() => Math.min(this.total(), (this.page() + 1) * this.pageSize()));

  allPageSelected = computed(() =>
    this.pageRows().length > 0 && this.pageRows().every(w => this.selected().has(w.id)));

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading.set(true);
    this.selected.set(new Set());
    this.service.list(0, 1000, '').subscribe(res => this.activeItems.set(res.content));
    this.service.trashed(0, 1000, '').subscribe({
      next: res => { this.trashedItems.set(res.content); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  /* ---- filtres ---- */
  setTab(t: Tab) { this.tab.set(t); this.page.set(0); }
  onSearch(v: string) { this.search.set(v); this.page.set(0); }
  setStatut(v: string) { this.statut.set(v); this.page.set(0); }
  setEspace(v: string) { this.espace.set(v); this.page.set(0); }
  setTri(v: string) { this.tri.set(v); this.page.set(0); }
  reset() {
    this.search.set(''); this.statut.set('Tous'); this.espace.set('Tous');
    this.tri.set('recent'); this.page.set(0);
  }

  /* ---- pagination ---- */
  goToPage(p: number) { if (p >= 0 && p < this.totalPages()) this.page.set(p); }
  setPageSize(n: number) { this.pageSize.set(n); this.page.set(0); }

  /* ---- statut d'affichage ---- */
  statusOf(w: Workflow): { label: string; cls: string } {
    if (w.archived) return { label: 'Archivée', cls: 'st-arch' };
    if (w.status === 'DRAFT') return { label: 'Brouillon', cls: 'st-draft' };
    return { label: 'Active', cls: 'st-active' };
  }

  /* ---- sélection ---- */
  isSelected(id: number) { return this.selected().has(id); }
  toggle(id: number) {
    const n = new Set(this.selected());
    n.has(id) ? n.delete(id) : n.add(id);
    this.selected.set(n);
  }
  toggleAllPage() {
    const n = new Set(this.selected());
    if (this.allPageSelected()) this.pageRows().forEach(w => n.delete(w.id));
    else this.pageRows().forEach(w => n.add(w.id));
    this.selected.set(n);
  }

  /* ---- actions ---- */
  create() { this.openDialog(null); }
  edit(w: Workflow) { this.openDialog(w); }
  private openDialog(w: Workflow | null) {
    const ref = this.dialog.open(WorkflowForm, {
      data: { workflow: w }, width: '660px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => { if (saved) this.load(); });
  }
  remove(w: Workflow) {
    if (!confirm(`Supprimer « ${w.name} » ?`)) return;
    this.service.delete(w.id).subscribe(() => this.load());
  }
  restoreOne(w: Workflow) {
    this.service.restore(w.id).subscribe(() => this.load());
  }
}
