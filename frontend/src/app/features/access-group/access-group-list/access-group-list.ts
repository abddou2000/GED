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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { SelectionModel } from '@angular/cdk/collections';
import { ColumnPicker } from '../../../core/column-picker/column-picker';
import { ColonneDef } from '../../../core/column-prefs.service';
import { AccessGroupService } from '../access-group.service';
import { AccessGroup } from '../access-group.model';
import { AccessGroupForm } from '../access-group-form/access-group-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { SelectionToggle } from '../../../core/selection-toggle/selection-toggle';
import { teinteAvatar, encreAvatar } from '../../../core/avatar';

/**
 * Écran « Groupe d'accès » — CRUD, corbeille, recherche. Création / édition
 * en boîte de dialogue (identité, espaces couverts, membres).
 */
@Component({
  selector: 'app-access-group-list',
  imports: [
    SelectionToggle,
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, RouterLink, SkeletonTable, ColumnPicker,
  ],
  templateUrl: './access-group-list.html',
  styleUrl: './access-group-list.scss',
})
export class AccessGroupList implements OnInit {

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
  private service = inject(AccessGroupService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  dataSource = new MatTableDataSource<AccessGroup>([]);
  selection = new SelectionModel<AccessGroup>(true, []);
  corbeilleView = signal(false);
  loading = signal(true);
  /** Identifiant d'écran : clé des préférences de colonnes et de pagination. */
  static readonly NOM_ECRAN = 'accessGroups';
  /**
   * Clé des préférences de colonnes. Reprend le nom d'écran de
   * l'application d'origine pour qu'un utilisateur passant d'une GED à
   * l'autre retrouve les colonnes qu'il avait masquées.
   */
  readonly ECRAN = 'accessGroups';

  readonly COLONNES: ColonneDef[] = [
    { cle: 'select', libelle: '', toujours: true },
    { cle: 'id', libelle: 'ID' },
    { cle: 'code', libelle: 'Code' },
    { cle: 'name', libelle: 'Groupe' },
    { cle: 'workspaces', libelle: 'Espaces' },
    { cle: 'users', libelle: 'Membres' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];
  /** Colonnes réellement rendues, pilotées par le sélecteur de colonnes. */
  colonnesVisibles = signal<string[]>(['select', 'id', 'code', 'name', 'workspaces', 'users', 'actions']);

  /** Taille de page mémorisée entre deux visites, comme l'original. */
  private static readonly CLE_TAILLE = `${AccessGroupList.NOM_ECRAN}-pagination`;
  taillePage = signal(Number(localStorage.getItem(AccessGroupList.CLE_TAILLE)) || 10);
  pageCourante = signal(0);
  recherche = signal('');
  total = signal(0);
  triChamp = signal('');
  triSens = signal<'asc' | 'desc'>('desc');
  readonly triDirection = computed<'' | 'asc' | 'desc'>(() =>
    this.triChamp() ? this.triSens() : '');

  ngOnInit(): void {
    // La corbeille vit dans l'URL : sans cela, recharger la page ou ouvrir le lien
    // ailleurs ramenait aux groupes actifs sans prévenir.
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
      localStorage.setItem(AccessGroupList.CLE_TAILLE, String(e.pageSize));
    }
    this.load();
  }

  basculerCorbeille(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: this.corbeilleView() ? {} : { trashed: 1 },
    });
  }

  /** Ouvre la fiche du groupe. */
  ouvrir(id: string): void {
    this.router.navigate(['/groupe-d-acces', id]);
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
    const restoring = this.corbeilleView();
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

  /* ---- présentation (avatars membres) ---- */
  initials(fullName: string): string {
    const parts = (fullName || '').trim().split(/\s+/);
    const a = parts[0]?.[0] ?? '';
    const b = parts.length > 1 ? parts[parts.length - 1][0] : '';
    return (a + b).toUpperCase() || '?';
  }
  /* Teinte et encre viennent de `core/avatar` : la palette etait recopiee dans
     chaque ecran, et corriger l'un laissait les autres derriere. */
  readonly avatarColor = teinteAvatar;
  readonly avatarInk = encreAvatar;

  /* ---- actions ---- */
  create(): void { this.openDialog(null); }
  edit(id: string): void {
    this.service.get(id).subscribe(g => this.openDialog(g));
  }
  private openDialog(g: AccessGroup | null): void {
    const ref = this.dialog.open(AccessGroupForm, {
      data: { group: g }, width: '620px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => {
      if (!saved) return;
      this.notify.success(g ? "Groupe d'accès modifié." : "Groupe d'accès créé.");
      if (!g && saved.id) {
        // Après création on ouvre la fiche : revenir à la liste obligeait à
        // retrouver, à la main, le groupe qu'on vient tout juste de créer.
        this.router.navigate(['/groupe-d-acces', saved.id]);
        return;
      }
      this.load();
    });
  }
  remove(id: string, name: string): void {
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
  restoreOne(id: string): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success("Groupe d'accès restauré."); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
}
