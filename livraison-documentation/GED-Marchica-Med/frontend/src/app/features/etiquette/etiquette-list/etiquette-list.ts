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
import { ActivatedRoute, Router } from '@angular/router';
import { ColumnPicker } from '../../../core/column-picker/column-picker';
import { ColonneDef } from '../../../core/column-prefs.service';
import { EtiquetteService } from '../etiquette.service';
import { Etiquette } from '../etiquette.model';
import { EtiquetteForm } from '../etiquette-form/etiquette-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { SelectionToggle } from '../../../core/selection-toggle/selection-toggle';

/**
 * Écran « Étiquette » — CRUD, corbeille, recherche. Création / édition en boîte de dialogue.
 */
@Component({
  selector: 'app-etiquette-list',
  imports: [
    SelectionToggle,
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, SkeletonTable, ColumnPicker,
  ],
  templateUrl: './etiquette-list.html',
  styleUrl: './etiquette-list.scss',
})
export class EtiquetteList implements OnInit {

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
  private service = inject(EtiquetteService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  dataSource = new MatTableDataSource<Etiquette>([]);
  selection = new SelectionModel<Etiquette>(true, []);
  archiveView = signal(false);
  loading = signal(true);
  /**
   * Clé des préférences de colonnes. Reprend le nom d'écran de l'application
   * d'origine pour qu'un utilisateur passant d'une GED à l'autre retrouve les
   * colonnes qu'il avait masquées.
   */
  readonly ECRAN = 'etiquettes';

  readonly COLONNES: ColonneDef[] = [
    { cle: 'select', libelle: '', toujours: true },
    { cle: 'id', libelle: 'ID' },
    { cle: 'code', libelle: 'Code' },
    { cle: 'tag', libelle: 'Étiquette' },
    { cle: 'couleur', libelle: 'Couleur' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];
  colonnesVisibles = signal<string[]>(['select', 'id', 'code', 'tag', 'couleur', 'actions']);

  /** Taille de page mémorisée entre deux visites, comme l'original. */
  private static readonly CLE_TAILLE = 'etiquettes-pagination';
  taillePage = signal(Number(localStorage.getItem(EtiquetteList.CLE_TAILLE)) || 10);
  pageCourante = signal(0);
  recherche = signal('');
  total = signal(0);
  triChamp = signal('');
  triSens = signal<'asc' | 'desc'>('desc');
  readonly triDirection = computed<'' | 'asc' | 'desc'>(() =>
    this.triChamp() ? this.triSens() : '');

  ngOnInit(): void {
    // L'archive vit dans l'URL : sans cela, recharger la page ou ouvrir le lien
    // ailleurs ramenait aux étiquettes actives sans prévenir.
    this.route.queryParamMap.subscribe(q => {
      const archive = q.get('trashed') === '1';
      if (archive !== this.archiveView()) {
        this.archiveView.set(archive);
        this.pageCourante.set(0);
      }
      this.load();
    });
  }

  /** Recherche, tri et pagination côté serveur : tout charger pour filtrer
   *  localement casse dès que la base grossit. */
  load(): void {
    this.selection.clear();
    this.loading.set(true);
    const source = this.archiveView() ? this.service.trashed : this.service.list;
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
      localStorage.setItem(EtiquetteList.CLE_TAILLE, String(e.pageSize));
    }
    this.load();
  }

  applySearch(v: string): void {
    this.recherche.set((v ?? '').trim());
    this.pageCourante.set(0);
    this.load();
  }

  toggleArchive(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: this.archiveView() ? {} : { trashed: 1 },
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
    const ids = this.selection.selected.map(e => e.id);
    if (!ids.length) return;
    const restoring = this.archiveView();
    this.confirm.ask({
      title: restoring ? 'Restaurer la sélection' : 'Supprimer la sélection',
      message: `Voulez-vous ${restoring ? 'restaurer' : 'supprimer'} ${ids.length} étiquette(s) ?`,
      confirmLabel: restoring ? 'Restaurer' : 'Supprimer',
      danger: !restoring,
    }).subscribe(ok => {
      if (!ok) return;
      (restoring ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids)).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} étiquette(s) ${restoring ? 'restaurée(s)' : 'supprimée(s)'}.`); },
        error: () => this.notify.error('Opération impossible.'),
      });
    });
  }

  /* ---- actions ---- */
  create(): void { this.openDialog(null); }
  edit(id: number): void {
    this.service.get(id).subscribe(e => this.openDialog(e));
  }
  private openDialog(e: Etiquette | null): void {
    const ref = this.dialog.open(EtiquetteForm, {
      data: { etiquette: e }, width: '520px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => {
      if (!saved) return;
      this.load();
      this.notify.success(e ? 'Étiquette modifiée.' : 'Étiquette créée.');
    });
  }
  remove(id: number, name: string): void {
    this.confirm.ask({
      title: 'Supprimer cette étiquette',
      message: `« ${name} » sera déplacée vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.delete(id).subscribe({
        next: () => { this.load(); this.notify.success('Étiquette supprimée.'); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }
  restoreOne(id: number): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success('Étiquette restaurée.'); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
}
