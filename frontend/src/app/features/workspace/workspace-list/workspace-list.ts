import { AuthService } from '../../../core/auth.service';
import { ModulesService } from '../../../core/modules.service';
import { animate, style, transition, trigger } from '@angular/animations';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { FormsModule } from '@angular/forms';
import { SelectionModel } from '@angular/cdk/collections';
import { ColumnPicker } from '../../../core/column-picker/column-picker';
import { ColonneDef } from '../../../core/column-prefs.service';
import { WorkspaceService } from '../workspace.service';
import { SelectOption, TreeNode, WorkSpace } from '../workspace.model';
import { WorkspaceForm } from '../workspace-form/workspace-form';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DocumentService, messageErreurTelechargement } from '../../document/document.service';
import { DocumentItem } from '../../document/document.model';
import { SelectionToggle } from '../../../core/selection-toggle/selection-toggle';
import { teinteAvatar, encreAvatar } from '../../../core/avatar';

/**
 * Écran « Espaces de travail » — 2 vues (Tableau / Arbre), CRUD, déplacement,
 * archivage, corbeille. Création / édition en boîte de dialogue.
 */
@Component({
  selector: 'app-workspace-list',
  imports: [
    SelectionToggle,
    NgTemplateOutlet, FormsModule, MatTableModule, MatPaginatorModule, MatSortModule,
    MatButtonModule, MatButtonToggleModule, MatIconModule, MatCheckboxModule, MatMenuModule,
    MatTooltipModule, MatDialogModule, RouterLink, SkeletonTable, ColumnPicker,
  ],
  templateUrl: './workspace-list.html',
  styleUrl: './workspace-list.scss',
  /**
   * Ouverture d'un dossier.
   *
   * <p>Le contenu apparaissait d'un bloc : à l'écran, on ne voyait pas D'OÙ il
   * sortait, et sur un dossier fourni tout l'arbre sautait d'un coup. La
   * hauteur est donc dépliée, et les lignes entrent en cascade — l'œil suit le
   * mouvement et comprend la hiérarchie sans la relire.
   *
   * <p>La fermeture est plus rapide que l'ouverture (140 ms contre 240) : on
   * regarde ce qui s'ouvre, on ne regarde pas ce qui se referme.
   *
   * <p>Volontairement minimal : une seule transition, aucun `group` ni
   * `animateChild`. Sur un arbre récursif, une animation composée qui attend
   * ses enfants peut ne jamais se terminer — et le contenu resterait alors
   * invisible. Un dossier qui ne s'ouvre pas est bien pire qu'un dossier qui
   * s'ouvre sans effet. Chaque niveau joue sa propre transition à son entrée.
   */
  animations: [
    trigger('deplier', [
      transition(':enter', [
        style({ height: 0, opacity: 0, overflow: 'hidden' }),
        animate('240ms cubic-bezier(.22,.61,.36,1)',
                style({ height: '*', opacity: 1 })),
      ]),
      transition(':leave', [
        style({ overflow: 'hidden' }),
        animate('140ms cubic-bezier(.55,.06,.68,.19)',
                style({ height: 0, opacity: 0 })),
      ]),
    ]),
  ],
})
export class WorkspaceList implements OnInit {

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
  protected auth = inject(AuthService);
  private modules = inject(ModulesService);
  private service = inject(WorkspaceService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private documents = inject(DocumentService);

  view = signal<'table' | 'tree'>('tree');

  // ---- Vue Tableau ----
  dataSource = new MatTableDataSource<WorkSpace>([]);
  selection = new SelectionModel<WorkSpace>(true, []);
  corbeilleView = signal(false);
  loading = signal(true);
  /** Colonnes de l'original : identifiant, code, nom, parent, circuit, description, propriétaire, statut. */
  /** Identifiant d'écran : clé des préférences de colonnes et de pagination. */
  static readonly NOM_ECRAN = 'workspaces';
  /**
   * Clé des préférences de colonnes. Reprend le nom d'écran de
   * l'application d'origine pour qu'un utilisateur passant d'une GED à
   * l'autre retrouve les colonnes qu'il avait masquées.
   */
  readonly ECRAN = 'Workspace';

  readonly COLONNES: ColonneDef[] = [
    { cle: 'select', libelle: '', toujours: true },
    { cle: 'id', libelle: 'ID' },
    { cle: 'code', libelle: 'Code' },
    { cle: 'name', libelle: 'Dossier' },
    { cle: 'parent', libelle: 'Dossier parent' },
    { cle: 'workflow', libelle: 'Circuit' },
    { cle: 'description', libelle: 'Description' },
    { cle: 'owner', libelle: 'Propriétaire' },
    { cle: 'status', libelle: 'Statut' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];
  /** Colonnes proposées : « Circuit » relève du module workflow (T-088) et
   *  disparaît, du tableau comme du sélecteur, quand il est désactivé. */
  readonly colonnesProposees = computed(() =>
    this.COLONNES.filter(c => c.cle !== 'workflow' || this.modules.actif('workflow')));
  /** Colonnes réellement rendues, pilotées par le sélecteur de colonnes. */
  colonnesVisibles = signal<string[]>(['select', 'id', 'code', 'name', 'parent', 'workflow', 'description', 'owner', 'status', 'actions']);

  /** Taille de page mémorisée entre deux visites, comme l'original. */
  private static readonly CLE_TAILLE = `${WorkspaceList.NOM_ECRAN}-pagination`;
  taillePage = signal(Number(localStorage.getItem(WorkspaceList.CLE_TAILLE)) || 10);
  pageCourante = signal(0);
  recherche = signal('');
  total = signal(0);
  triChamp = signal('');
  triSens = signal<'asc' | 'desc'>('desc');
  readonly triDirection = computed<'' | 'asc' | 'desc'>(() =>
    this.triChamp() ? this.triSens() : '');

  // ---- Vue Arbre ----
  tree = signal<TreeNode[]>([]);
  expanded = signal<Set<string>>(new Set());
  parentOptions = signal<SelectOption[]>([]);
  /** Documents d'un dossier, chargés à la première ouverture du nœud.
   *  L'arbre ne montrait que des dossiers : impossible d'y voir ce qu'ils
   *  contiennent, alors que c'est la question que l'on se pose en l'ouvrant. */
  docsParDossier = signal<Record<string, DocumentItem[]>>({});
  docsEnCours = signal<Set<string>>(new Set());

  ngOnInit(): void {
    // La corbeille vit dans l'URL : sans cela, recharger la page ou ouvrir le lien
    // ailleurs ramenait aux dossiers actifs sans prévenir.
    this.route.queryParamMap.subscribe(q => {
      const corbeille = q.get('trashed') === '1';
      if (corbeille !== this.corbeilleView()) {
        this.corbeilleView.set(corbeille);
        this.pageCourante.set(0);
      }
      // L'arborescence ne connaît que les dossiers actifs : y rester en mode
      // corbeille afficherait exactement la même chose qu'avant le clic.
      if (corbeille) this.view.set('table');
      this.load();
    });
  }

  /* =================== chargement =================== */
  load(): void {
    if (this.view() === 'tree') { this.loadTree(); return; }
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

  loadTree(): void {
    this.service.tree().subscribe(t => this.tree.set(t));
    this.service.forSelect().subscribe(o => this.parentOptions.set(o));
  }

  setView(v: 'table' | 'tree'): void {
    this.view.set(v);
    this.load();
  }

  /** Recherche et pagination côté serveur : filtrer la seule page affichée
   *  masquerait silencieusement tous les dossiers des pages suivantes. */
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
      localStorage.setItem(WorkspaceList.CLE_TAILLE, String(e.pageSize));
    }
    this.load();
  }

  basculerCorbeille(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: this.corbeilleView() ? {} : { trashed: 1 },
    });
  }

  /** Ouvre la fiche détaillée d'un dossier. */
  ouvrir(id: string): void {
    this.router.navigate(['/espaces-de-travail', id]);
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
    const restoring = this.corbeilleView();
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
  isExpanded(id: string): boolean { return this.expanded().has(id); }

  toggleExpand(id: string): void {
    const n = new Set(this.expanded());
    if (n.has(id)) { n.delete(id); } else { n.add(id); this.chargerDocs(id); }
    this.expanded.set(n);
  }

  /** Documents déjà connus pour ce dossier (tableau vide tant qu'ils chargent). */
  docsDe(id: string): DocumentItem[] { return this.docsParDossier()[id] ?? []; }

  private chargerDocs(id: string): void {
    if (this.docsParDossier()[id] || this.docsEnCours().has(id)) return;
    this.docsEnCours.update(s => new Set(s).add(id));
    this.documents.list(0, 200, '', id).subscribe({
      next: r => {
        this.docsParDossier.update(m => ({ ...m, [id]: r.content }));
        this.docsEnCours.update(s => { const n = new Set(s); n.delete(id); return n; });
      },
      error: () => this.docsEnCours.update(s => { const n = new Set(s); n.delete(id); return n; }),
    });
  }

  /** Identifiants en cours de téléchargement : un clic répété sur la même ligne
   *  ne doit pas relancer la requête ni enregistrer deux fois le fichier. */
  readonly telechargements = signal(new Set<string>());

  /**
   * Télécharge par `HttpClient` (et non plus `window.open`) : seule cette voie
   * traverse les intercepteurs, donc seule elle porte le jeton — un onglet
   * ouvert sur l'URL brute recevait un 401 et restait blanc.
   */
  telecharger(doc: DocumentItem): void {
    if (this.telechargements().has(doc.id)) return;
    this.telechargements.update(s => new Set(s).add(doc.id));
    const fin = () => this.telechargements.update(s => { const n = new Set(s); n.delete(doc.id); return n; });
    this.documents.telechargerEtEnregistrer(doc).subscribe({
      next: fin,
      error: (e: HttpErrorResponse) => {
        fin();
        const msg = messageErreurTelechargement(e);
        if (msg) this.notify.error(msg);
      },
    });
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
  /* Teinte et encre viennent de `core/avatar` : la palette etait recopiee dans
     chaque ecran, et corriger l'un laissait les autres derriere. */
  readonly avatarColor = teinteAvatar;
  readonly avatarInk = encreAvatar;

  /* =================== actions =================== */
  /**
   * Modifier un espace ou un dossier (nom, code, propriétaire, statut, usage,
   * règle, parent) relève de la gestion des espaces (DF §4.3.4, ANO-F-026) :
   * le serveur refuse tout autre appelant. Confort d'affichage.
   */
  peutModifier(): boolean { return this.auth.peut('GERER_ESPACES'); }

  /*
   * Les autres actions suivent la permission que le serveur exige (ANO-F-018,
   * comme la fiche d'un dossier). La liste ne porte pas les permissions de
   * chaque nœud : l'action paraît si l'utilisateur exerce la permission
   * quelque part, et le serveur tranche nœud par nœud (403 tracé).
   */

  /** Archiver / désarchiver un dossier (D10) : ARCHIVER. */
  peutArchiver(): boolean { return this.auth.peut('ARCHIVER'); }
  /** Supprimer, restaurer (corbeille), un par un ou par lot : SUPPRIMER. */
  peutSupprimer(): boolean { return this.auth.peut('SUPPRIMER'); }
  /** Déplacer sous un autre dossier : DEPLACER (la racine relève de la gestion des espaces). */
  peutDeplacer(): boolean { return this.peutModifier() || this.auth.peut('DEPLACER'); }
  /**
   * Créer un sous-dossier par le formulaire d'administration : gestion des
   * espaces. Le membre d'un espace d'échange crée ses dossiers depuis la fiche
   * du dossier (« Nouveau dossier », ANO-F-016) : l'arborescence ne dit pas
   * quel nœud appartient à un espace d'échange.
   */
  peutCreerSousDossier(): boolean { return this.peutModifier(); }

  create(parentId: string | null = null): void { this.openDialog(null, parentId); }
  edit(id: string): void {
    this.service.get(id).subscribe(w => this.openDialog(w, null));
  }
  private openDialog(w: WorkSpace | null, parentId: string | null): void {
    const ref = this.dialog.open(WorkspaceForm, {
      data: { workspace: w, parentId }, width: '760px', maxWidth: '95vw', autoFocus: false,
    });
    ref.afterClosed().subscribe(saved => {
      if (!saved) return;
      this.load();
      this.notify.success(w ? 'Espace de travail modifié.' : 'Espace de travail créé.');
    });
  }

  remove(id: string, name: string): void {
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
  restoreOne(id: string): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success('Dossier restauré.'); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
  archiveOne(id: string): void {
    this.service.archive(id).subscribe({
      next: () => { this.load(); this.notify.success('Dossier archivé.'); },
      error: () => this.notify.error('Archivage impossible.'),
    });
  }
  moveTo(id: string, parentId: string | null): void {
    this.service.move(id, parentId).subscribe({
      next: () => { this.load(); this.notify.success('Dossier déplacé.'); },
      error: err => this.notify.error(err?.error?.message ?? 'Déplacement impossible.'),
    });
  }
}
