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
import { IndexService } from '../index.service';
import { IndexField, fieldTypeLabel } from '../index.model';
import { IndexForm } from '../index-form/index-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { ColumnPicker } from '../../../core/column-picker/column-picker';
import { ColonneDef } from '../../../core/column-prefs.service';
import { SelectionToggle } from '../../../core/selection-toggle/selection-toggle';

/**
 * Écran « Index » — CRUD, corbeille, recherche. Création / édition en boîte de dialogue.
 */
@Component({
  selector: 'app-index-list',
  imports: [
    SelectionToggle,
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, SkeletonTable, ColumnPicker,
  ],
  templateUrl: './index-list.html',
  styleUrl: './index-list.scss',
})
export class IndexList implements OnInit {

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
  private service = inject(IndexService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  /** Identifiant d'écran : clé des préférences de colonnes et de pagination. */
  static readonly NOM_ECRAN = 'indices';
  /**
   * Clé des préférences de colonnes. Reprend le nom d'écran de
   * l'application d'origine pour qu'un utilisateur passant d'une GED à
   * l'autre retrouve les colonnes qu'il avait masquées.
   */
  readonly ECRAN = 'indices';

  dataSource = new MatTableDataSource<IndexField>([]);
  selection = new SelectionModel<IndexField>(true, []);
  archiveView = signal(false);
  loading = signal(true);
  /** Colonnes de l'original : id, code, nom, obligatoire, type, valeurs,
   *  valeur par défaut, indexé pour recherche, index de groupage. */
  readonly COLONNES: ColonneDef[] = [
    { cle: 'select', libelle: '', toujours: true },
    { cle: 'id', libelle: 'ID' },
    { cle: 'code', libelle: 'Code' },
    { cle: 'nomIndex', libelle: 'Nom index' },
    { cle: 'obligatoire', libelle: 'Obligatoire' },
    { cle: 'type', libelle: 'Type champs' },
    { cle: 'valeurs', libelle: 'Valeurs' },
    { cle: 'valeurParDefaut', libelle: 'Valeur par défaut' },
    { cle: 'indexePourRecherche', libelle: 'Indexé pour recherche' },
    { cle: 'indexDeGroupage', libelle: 'Index de groupage' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];
  /** Colonnes réellement rendues, pilotées par le sélecteur de colonnes. */
  colonnesVisibles = signal<string[]>(this.COLONNES.map(c => c.cle));

  /** Taille de page mémorisée entre deux visites, comme l'original. */
  private static readonly CLE_TAILLE = `${IndexList.NOM_ECRAN}-pagination`;
  taillePage = signal(Number(localStorage.getItem(IndexList.CLE_TAILLE)) || 10);
  pageCourante = signal(0);
  recherche = signal('');
  total = signal(0);
  triChamp = signal('');
  triSens = signal<'asc' | 'desc'>('desc');
  readonly triDirection = computed<'' | 'asc' | 'desc'>(() =>
    this.triChamp() ? this.triSens() : '');

  ngOnInit(): void {
    // L'archive vit dans l'URL : sans cela, recharger la page ou ouvrir le lien
    // ailleurs ramenait aux index actifs sans prévenir.
    this.route.queryParamMap.subscribe(q => {
      const archive = q.get('trashed') === '1';
      if (archive !== this.archiveView()) {
        this.archiveView.set(archive);
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

  applySearch(v: string): void {
    this.recherche.set((v ?? '').trim());
    this.pageCourante.set(0);
    this.load();
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
      localStorage.setItem(IndexList.CLE_TAILLE, String(e.pageSize));
    }
    this.load();
  }

  toggleArchive(): void {
    // On ne bascule pas l'état ici : la navigation le fait, et l'abonnement
    // aux paramètres recharge. Sinon un retour arrière du navigateur laisserait
    // l'URL et l'écran en désaccord.
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

  /** Glyphe du type de champ — un par nature, dans le registre d'icônes. */
  protected iconeType(x: IndexField): string {
    return { TEXTE: 'type-texte', NOMBRE: 'type-nombre',
             DATE: 'calendar', LISTE: 'type-liste' }[x.fieldType] ?? 'type-texte';
  }
  ouiNon(v: boolean): string { return v ? 'Oui' : 'Non'; }

  /** Valeurs d'une liste, une puce par option. */
  valeurs(x: IndexField): string[] {
    return (x.valeurs ?? '').split(',').map(v => v.trim()).filter(Boolean);
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
