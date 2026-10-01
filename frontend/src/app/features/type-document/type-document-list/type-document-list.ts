import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { FormsModule } from '@angular/forms';
import { SelectionModel } from '@angular/cdk/collections';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ColumnPicker } from '../../../core/column-picker/column-picker';
import { ColonneDef } from '../../../core/column-prefs.service';
import { TypeDocumentService } from '../type-document.service';
import { TypeDocument } from '../type-document.model';
import { TypeDocumentForm } from '../type-document-form/type-document-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { SelectionToggle } from '../../../core/selection-toggle/selection-toggle';
import { ModulesService } from '../../../core/modules.service';

/**
 * Écran « Type de document » — CRUD, corbeille, recherche. Création / édition
 * en boîte de dialogue.
 */
@Component({
  selector: 'app-type-document-list',
  imports: [
    SelectionToggle,
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, SkeletonTable, ColumnPicker, RouterLink,
  ],
  templateUrl: './type-document-list.html',
  styleUrl: './type-document-list.scss',
})
export class TypeDocumentList implements OnInit {
  /**
   * Glyphe d'un format de fichier : un tableur se distingue d'un document
   * texte, le reste retombe sur le document generique. Le format exact reste
   * lisible — il est porte par l'infobulle et par `aria-label`.
   */
  protected iconeFormat(format: string): string {
    const f = (format ?? '').toLowerCase();
    if (['xls', 'xlsx', 'csv'].includes(f)) return 'fmt-excel';
    if (['doc', 'docx', 'odt', 'rtf'].includes(f)) return 'fmt-word';
    if (f === 'pdf') return 'fmt-pdf';
    // Format non prévu : la feuille générique, l'extension reste en infobulle.
    return 'file-doc';
  }

  /**
   * Mode selection : les cases a cocher n'apparaissent que lorsqu'on le
   * demande. Sortir du mode vide la selection — laisser des lignes cochees
   * mais invisibles exposerait a une action groupee non voulue.
   */
  readonly modeSelection = signal(false);
  basculerSelection(actif: boolean): void {
    this.modeSelection.set(actif);
    if (!actif) this.selection.clear();
  }
  private service = inject(TypeDocumentService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private modules = inject(ModulesService);

  /** Re-typologie en lot (§12.7, ANO-F-015) : module « cycle de vie » (T-088). */
  retypageDisponible = () => this.modules.actif('cycledevie');

  dataSource = new MatTableDataSource<TypeDocument>([]);
  selection = new SelectionModel<TypeDocument>(true, []);
  corbeilleView = signal(false);
  loading = signal(true);
  /**
   * Clé des préférences de colonnes. Reprend le nom d'écran de l'application
   * d'origine pour qu'un utilisateur passant d'une GED à l'autre retrouve les
   * colonnes qu'il avait masquées.
   */
  readonly ECRAN = 'Type de Documents';

  readonly COLONNES: ColonneDef[] = [
    { cle: 'select', libelle: '', toujours: true },
    { cle: 'id', libelle: 'ID' },
    { cle: 'code', libelle: 'Code' },
    { cle: 'type', libelle: 'Type de document' },
    { cle: 'plan', libelle: "Plan d'indexation" },
    { cle: 'description', libelle: 'Description' },
    { cle: 'workspace', libelle: 'Espace de travail' },
    { cle: 'formats', libelle: 'Types autorisés' },
    { cle: 'taille', libelle: 'Taille max' },
    { cle: 'statut', libelle: 'Statut' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];
  colonnesVisibles = signal<string[]>(
    ['select', 'id', 'code', 'type', 'plan', 'description', 'workspace', 'formats', 'taille', 'statut', 'actions']);

  /** Taille de page mémorisée entre deux visites, comme l'original. */
  private static readonly CLE_TAILLE = 'type_de_documents-pagination';
  taillePage = signal(Number(localStorage.getItem(TypeDocumentList.CLE_TAILLE)) || 10);
  pageCourante = signal(0);
  recherche = signal('');
  total = signal(0);
  triChamp = signal('');
  triSens = signal<'asc' | 'desc'>('desc');
  readonly triDirection = computed<'' | 'asc' | 'desc'>(() =>
    this.triChamp() ? this.triSens() : '');

  ngOnInit(): void {
    // La corbeille vit dans l'URL : sans cela, recharger la page ou ouvrir le lien
    // ailleurs ramenait aux types actifs sans prévenir.
    this.route.queryParamMap.subscribe(q => {
      const corbeille = q.get('trashed') === '1';
      if (corbeille !== this.corbeilleView()) {
        this.corbeilleView.set(corbeille);
        this.pageCourante.set(0);
      }
      this.load();
    });
  }

  /** Recherche, tri et pagination côté serveur : charger 1000 lignes d'un coup
   *  pour filtrer localement casse dès que la base grossit. */
  load(): void {
    this.selection.clear();
    this.loading.set(true);
    const source = this.corbeilleView() ? this.service.trashed : this.service.list;
    source.call(this.service, this.pageCourante(), this.taillePage(), this.recherche(),
                this.triChamp(), this.triSens()).subscribe({
      next: res => {
        this.dataSource.data = res.content;
        this.total.set(res.total);
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); this.notify.error('Chargement impossible.'); },
    });
  }

  onSort(e: Sort): void {
    this.triChamp.set(e.direction ? e.active : '');
    this.triSens.set(e.direction === 'asc' ? 'asc' : 'desc');
    this.pageCourante.set(0);
    this.load();
  }

  onPage(e: { pageIndex: number; pageSize: number }): void {
    this.pageCourante.set(e.pageIndex);
    if (e.pageSize !== this.taillePage()) {
      this.taillePage.set(e.pageSize);
      localStorage.setItem(TypeDocumentList.CLE_TAILLE, String(e.pageSize));
    }
    this.load();
  }

  /** Ouvre la fiche du type. */
  ouvrir(id: string): void {
    this.router.navigate(['/type-de-document', id]);
  }

  applySearch(v: string): void {
    this.recherche.set((v ?? '').trim());
    this.pageCourante.set(0);
    this.load();
  }

  basculerCorbeille(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: this.corbeilleView() ? {} : { trashed: 1 },
    });
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
    const restoring = this.corbeilleView();
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
  edit(id: string): void {
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
  remove(id: string, name: string): void {
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
  restoreOne(id: string): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success('Type de document restauré.'); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
}
