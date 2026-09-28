import { AuthService } from '../../../core/auth.service';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
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
import { formaterDate } from '../../../core/dates';
import { DocumentService, messageErreurTelechargement } from '../document.service';
import { DocumentItem } from '../document.model';
import { DocumentUpload } from '../document-upload/document-upload';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { SelectionToggle } from '../../../core/selection-toggle/selection-toggle';
import { CycleDeVieService } from '../../cycle-de-vie/cycle-de-vie.service';
import { teinteAvatar, encreAvatar, initialesDe, graineDepuisTexte } from '../../../core/avatar';

/**
 * Écran « Téléverser un document » (Phase 1) — dépôt de fichier, liste,
 * téléchargement et corbeille.
 */
@Component({
  selector: 'app-document-list',
  imports: [
    SelectionToggle,
    FormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatTooltipModule, MatDialogModule, SkeletonTable, ColumnPicker, RouterLink,
  ],
  templateUrl: './document-list.html',
  styleUrl: './document-list.scss',
})
export class DocumentList implements OnInit {
  private cycleDeVie = inject(CycleDeVieService);

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
  private service = inject(DocumentService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  dataSource = new MatTableDataSource<DocumentItem>([]);
  selection = new SelectionModel<DocumentItem>(true, []);
  archiveView = signal(false);
  /** Filtre « échéance dépassée » (§12.9), porté par l'URL comme l'archive. */
  echeanceView = signal(false);
  loading = signal(true);
  /**
   * Cle des preferences de colonnes. Reprend le nom d'ecran de l'application
   * d'origine pour qu'un utilisateur passant d'une GED a l'autre retrouve les
   * colonnes qu'il avait masquees.
   */
  readonly ECRAN = 'Upload de document';

  readonly COLONNES: ColonneDef[] = [
    { cle: 'select', libelle: '', toujours: true },
    { cle: 'id', libelle: 'ID', masqueeParDefaut: true },
    { cle: 'name', libelle: 'Nom' },
    { cle: 'size', libelle: 'Taille', masqueeParDefaut: true },
    { cle: 'extension', libelle: 'Extension', masqueeParDefaut: true },
    /* Le chemin repete mot pour mot « Espace de travail / Type de document » :
       la meme information sur trois colonnes. Masque par defaut, il reste
       disponible dans le selecteur de colonnes. */
    { cle: 'chemin', libelle: 'Chemin', masqueeParDefaut: true },
    { cle: 'createdAt', libelle: 'Date de création' },
    { cle: 'createdBy', libelle: 'Créateur' },
    { cle: 'workspace', libelle: 'Espace de travail' },
    { cle: 'type', libelle: 'Type de document' },
    { cle: 'expiration', libelle: "Date d'expiration" },
    { cle: 'echeance', libelle: 'Échéance de conservation', masqueeParDefaut: true },
    { cle: 'etiquettes', libelle: 'Étiquettes' },
    { cle: 'actions', libelle: 'Actions', toujours: true },
  ];
  /**
   * Colonnes visibles au premier affichage. ID, Taille et Extension sont
   * masquees par defaut, comme dans l'original : elles restent disponibles dans
   * le selecteur sans surcharger un tableau deja large.
   */
  colonnesVisibles = signal<string[]>(
    ['select', 'name', 'chemin', 'createdAt', 'createdBy', 'workspace', 'type',
     'expiration', 'etiquettes', 'actions']);

  /** Taille de page memorisee entre deux visites, comme l'original. */
  private static readonly CLE_TAILLE = 'upload_documents-pagination';
  taillePage = signal(Number(localStorage.getItem(DocumentList.CLE_TAILLE)) || 10);
  pageCourante = signal(0);
  recherche = signal('');
  total = signal(0);
  triChamp = signal('');
  triSens = signal<'asc' | 'desc'>('desc');
  readonly triDirection = computed<'' | 'asc' | 'desc'>(() =>
    this.triChamp() ? this.triSens() : '');

  ngOnInit(): void {
    // L'archive vit dans l'URL : sans cela, recharger la page ou ouvrir le lien
    // ailleurs ramenait aux documents actifs sans prevenir.
    this.route.queryParamMap.subscribe(q => {
      const archive = q.get('trashed') === '1';
      const echeance = !archive && q.get('echeance') === '1';
      if (archive !== this.archiveView() || echeance !== this.echeanceView()) {
        this.archiveView.set(archive);
        this.echeanceView.set(echeance);
        this.pageCourante.set(0);
      }
      this.load();
    });
  }

  /** Recherche, tri et pagination cote serveur : charger 1000 lignes d'un coup
   *  pour filtrer localement casse des que la base grossit. */
  load(): void {
    this.selection.clear();
    this.loading.set(true);
    const requete = this.archiveView()
      ? this.service.trashed(this.pageCourante(), this.taillePage(), this.recherche(),
                             undefined, this.triChamp(), this.triSens())
      : this.service.list(this.pageCourante(), this.taillePage(), this.recherche(),
                          undefined, this.triChamp(), this.triSens(), this.echeanceView());
    requete.subscribe({
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
      localStorage.setItem(DocumentList.CLE_TAILLE, String(e.pageSize));
    }
    this.load();
  }

  /** Ouvre la fiche du document. */
  ouvrir(id: string): void {
    this.router.navigate(['/televerser', id]);
  }

  /** Date lisible ; mutualisée pour que tous les écrans lisent pareil. */
  readonly dateCourte = formaterDate;

  /* Pastille du createur. On n'a que son nom, pas son identifiant : la graine
     est derivee du texte pour que la teinte reste la meme d'un ecran a
     l'autre. */
  readonly initiales = initialesDe;
  teinte(nom: string): string { return teinteAvatar(graineDepuisTexte(nom)); }
  encre(nom: string): string { return encreAvatar(graineDepuisTexte(nom)); }

  /** Vrai si la date d'expiration est deja passee (jour en cours exclu). */
  estPerimee(iso: string | null | undefined): boolean {
    if (!iso) return false;
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return false;
    const aujourdhui = new Date();
    aujourdhui.setHours(0, 0, 0, 0);
    return d.getTime() < aujourdhui.getTime();
  }

  applySearch(v: string): void {
    this.recherche.set((v ?? '').trim());
    this.pageCourante.set(0);
    this.load();
  }

  /** Info-bulle de la pastille « Échéance dépassée ». */
  infoEcheance(d: DocumentItem): string {
    return `Échéance de conservation atteinte le ${this.dateCourte(d.echeanceConservation ?? '')} : `
      + "à examiner par l'Agent d'archive (aucune suppression automatique)";
  }

  /** Bascule le filtre « échéance dépassée » (documents actifs seulement). */
  toggleEcheance(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: this.echeanceView() ? {} : { echeance: 1 },
    });
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
    const ids = this.selection.selected.map(d => d.id);
    if (!ids.length) return;
    const restoring = this.archiveView();
    this.confirm.ask({
      title: restoring ? 'Restaurer la sélection' : 'Supprimer la sélection',
      message: `Voulez-vous ${restoring ? 'restaurer' : 'supprimer'} ${ids.length} document(s) ?`,
      confirmLabel: restoring ? 'Restaurer' : 'Supprimer',
      danger: !restoring,
    }).subscribe(ok => {
      if (!ok) return;
      (restoring ? this.service.multipleRestore(ids) : this.service.multipleDelete(ids)).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} document(s) ${restoring ? 'restauré(s)' : 'supprimé(s)'}.`); },
        error: () => this.notify.error('Opération impossible.'),
      });
    });
  }

  /* ---- actions ---- */
  upload(): void {
    const ref = this.dialog.open(DocumentUpload, { width: '560px', maxWidth: '95vw', autoFocus: false });
    ref.afterClosed().subscribe(issue => {
      if (!issue) return;
      this.load();
      // Un seul message, et qui dit ce qui s'est réellement passé : un document
      // déposé sans index ne doit pas être annoncé comme « indexé ».
      const messages: Record<string, string> = {
        'indexe': 'Document déposé, indexé, et circuit de validation ouvert.',
        'sans-plan': "Document déposé et circuit lancé. Son type n'a pas de plan d'indexation : aucun index à renseigner.",
        'a-indexer': 'Document déposé et circuit lancé, mais il reste à indexer.',
      };
      const message = messages[issue as string];
      if (issue === 'a-indexer') this.notify.info(message);
      else this.notify.success(message);
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
  download(doc: DocumentItem): void {
    if (this.telechargements().has(doc.id)) return;
    this.telechargements.update(s => new Set(s).add(doc.id));
    const fin = () => this.telechargements.update(s => { const n = new Set(s); n.delete(doc.id); return n; });
    this.service.telechargerEtEnregistrer(doc).subscribe({
      next: fin,
      error: (e: HttpErrorResponse) => {
        fin();
        const msg = messageErreurTelechargement(e);
        if (msg) this.notify.error(msg);
      },
    });
  }

  remove(id: string, name: string): void {
    this.confirm.ask({
      title: 'Supprimer ce document',
      message: `« ${name} » sera déplacé vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.delete(id).subscribe({
        next: () => { this.load(); this.notify.success('Document supprimé.'); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }
  /**
   * Purge définitive (§12.5) : depuis la corbeille seulement, jamais
   * automatique. Lignes, clés de chiffrement et fichiers sont détruits ; le
   * journal d'audit est conservé.
   */
  purger(d: DocumentItem): void {
    this.confirm.ask({
      title: 'Purger définitivement',
      message: `« ${d.name} », toutes ses versions et ses fichiers seront détruits sans retour possible.`,
      confirmLabel: 'Purger',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.cycleDeVie.purger(d.id).subscribe({
        next: () => { this.load(); this.notify.success('Document purgé définitivement.'); },
        error: err => this.notify.error(err?.error?.message ?? 'Purge impossible.'),
      });
    });
  }

  purgerSelection(): void {
    const ids = this.selection.selected.map(d => d.id);
    if (!ids.length) return;
    this.confirm.ask({
      title: 'Purger définitivement la sélection',
      message: `${ids.length} document(s), leurs versions et leurs fichiers seront détruits sans retour possible.`,
      confirmLabel: 'Purger',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.cycleDeVie.purgerPlusieurs(ids).subscribe({
        next: () => { this.load(); this.notify.success(`${ids.length} document(s) purgé(s).`); },
        error: err => this.notify.error(err?.error?.message ?? 'Purge impossible.'),
      });
    });
  }

  restoreOne(id: string): void {
    this.service.restore(id).subscribe({
      next: () => { this.load(); this.notify.success('Document restauré.'); },
      error: () => this.notify.error('Restauration impossible.'),
    });
  }
}
