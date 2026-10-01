import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatMenuModule } from '@angular/material/menu';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { SelectionModel } from '@angular/cdk/collections';
import { ActivatedRoute, Router } from '@angular/router';
import { ColumnPicker } from '../../../core/column-picker/column-picker';
import { ColonneDef } from '../../../core/column-prefs.service';
import { WorkflowService } from '../workflow.service';
import { Workflow } from '../workflow.model';
import { WorkflowForm } from '../workflow-form/workflow-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { SelectionToggle } from '../../../core/selection-toggle/selection-toggle';

/**
 * Écran « Règles de Workflow » — liste (mat-table : tri, pagination, filtre,
 * sélection, toggle colonnes) + création/édition en boîte de dialogue.
 */
@Component({
  selector: 'app-workflow-list',
  imports: [
    SelectionToggle,
    MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule, MatIconModule,
    MatCheckboxModule, MatMenuModule, MatFormFieldModule, MatInputModule,
    MatTooltipModule, MatDialogModule, SkeletonTable, ColumnPicker,
  ],
  templateUrl: './workflow-list.html',
  styleUrl: './workflow-list.scss',
})
export class WorkflowList implements OnInit {

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
  private service = inject(WorkflowService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  dataSource = new MatTableDataSource<Workflow>([]);

  /** Taille de page mémorisée entre deux visites (CCISTTA : localStorage). */
  private static readonly CLE_TAILLE = 'workflows-pagination';
  taillePage = signal(Number(localStorage.getItem(WorkflowList.CLE_TAILLE)) || 10);
  pageCourante = signal(0);
  recherche = signal('');
  total = signal(0);
  /** Tri demandé au serveur ; vide = ordre par défaut (le plus récent d'abord). */
  triChamp = signal('');
  triSens = signal<'asc' | 'desc'>('desc');
  /**
   * Sens à réinjecter dans l'en-tête. Le tableau est retiré du DOM pendant le
   * chargement (squelette) : la directive de tri est alors détruite et
   * repartirait de zéro à chaque requête, sans cette restitution.
   */
  readonly triDirection = computed<'' | 'asc' | 'desc'>(() =>
    this.triChamp() ? this.triSens() : '');
  selection = new SelectionModel<Workflow>(true, []);
  loading = signal(true);
  corbeilleView = signal(false);
  fullscreen = signal(false);

  /** Identifiant d'écran : clé des préférences de colonnes et de pagination. */
  static readonly NOM_ECRAN = 'workflows';
  /**
   * Clé des préférences de colonnes. Reprend le nom d'écran de
   * l'application d'origine pour qu'un utilisateur passant d'une GED à
   * l'autre retrouve les colonnes qu'il avait masquées.
   */
  readonly ECRAN = 'Règles de Workflow';

  readonly COLONNES: ColonneDef[] = [
    { cle: 'select', libelle: '', toujours: true },
    { cle: 'id', libelle: 'ID' },
    { cle: 'name', libelle: 'Nom' },
    { cle: 'steps', libelle: 'Validateurs' },
    { cle: 'workspaces', libelle: 'Espaces de travail' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];
  /** Colonnes réellement rendues, pilotées par le sélecteur partagé — le
   *  sélecteur maison ne conservait rien d'une visite à l'autre. */
  colonnesVisibles = signal<string[]>(['select', 'id', 'name', 'steps', 'workspaces', 'actions']);

  ngOnInit(): void {
    // La corbeille vit dans l'URL : sans cela, recharger la page ou ouvrir le lien
    // ailleurs ramenait aux règles actives sans prévenir.
    this.route.queryParamMap.subscribe(q => {
      const corbeille = q.get('trashed') === '1';
      if (corbeille !== this.corbeilleView()) {
        this.corbeilleView.set(corbeille);
        this.pageCourante.set(0);
      }
      this.load();
    });
  }

  /**
   * Charge une page depuis le serveur. Filtrer et paginer côté client
   * imposerait de tout télécharger d'abord : au-delà du plafond demandé, les
   * règles suivantes deviendraient invisibles sans que rien ne le signale.
   */
  load(): void {
    this.loading.set(true);
    this.selection.clear();
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

  /** Toute nouvelle recherche repart de la première page. */
  applySearch(value: string): void {
    this.recherche.set((value ?? '').trim());
    this.pageCourante.set(0);
    this.load();
  }

  /**
   * Le tri est demandé au serveur : trier la seule page affichée réordonnerait
   * 10 lignes sur un ensemble bien plus large, ce qui serait faux.
   */
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
      localStorage.setItem(WorkflowList.CLE_TAILLE, String(e.pageSize));
    }
    this.load();
  }

  basculerCorbeille(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: this.corbeilleView() ? {} : { trashed: 1 },
    });
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
    ref.afterClosed().subscribe(saved => {
      if (!saved) return;
      this.load();
      this.notify.success(w ? 'Règle de workflow modifiée.' : 'Règle de workflow créée.');
    });
  }

  remove(w: Workflow): void {
    this.confirm.ask({
      title: 'Supprimer cette règle',
      message: `« ${w.name} » sera déplacée vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.delete(w.id).subscribe({
        next: () => { this.load(); this.notify.success('Règle de workflow supprimée.'); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }

  restoreOne(w: Workflow): void {
    this.service.restore(w.id).subscribe({
      next: () => { this.load(); this.notify.success('Règle de workflow restaurée.'); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }

  bulk(): void {
    const ids = this.selection.selected.map(w => w.id);
    if (!ids.length) return;
    const restoring = this.corbeilleView();
    this.confirm.ask({
      title: restoring ? 'Restaurer la sélection' : 'Supprimer la sélection',
      message: `Voulez-vous ${restoring ? 'restaurer' : 'supprimer'} ${ids.length} élément(s) ?`,
      confirmLabel: restoring ? 'Restaurer' : 'Supprimer',
      danger: !restoring,
    }).subscribe(ok => {
      if (!ok) return;
      (restoring ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids)).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} élément(s) ${restoring ? 'restauré(s)' : 'supprimé(s)'}.`); },
        error: () => this.notify.error('Opération impossible.'),
      });
    });
  }
}
