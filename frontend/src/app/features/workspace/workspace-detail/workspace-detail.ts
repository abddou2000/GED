import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { forkJoin } from 'rxjs';
import { WorkspaceService } from '../workspace.service';
import { TreeNode, WorkSpace } from '../workspace.model';
import { WorkspaceForm } from '../workspace-form/workspace-form';
import { DocumentService, messageErreurTelechargement } from '../../document/document.service';
import { DocumentItem } from '../../document/document.model';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { CycleDossier } from '../../cycle-de-vie/cycle-dossier/cycle-dossier';
import { DossierSimpleForm } from '../dossier-simple-form/dossier-simple-form';
import { DocumentUpload, DonneesDepot } from '../../document/document-upload/document-upload';
import { AuthService } from '../../../core/auth.service';

/**
 * Fiche d'un espace de travail — reprend la page « Overview » de l'application
 * d'origine : identité du dossier, sous-dossiers, documents qu'il contient.
 *
 * <p>Sans elle, cliquer sur le nom d'un dossier ne menait nulle part : la liste
 * était le seul écran, et le contenu d'un espace restait invisible.
 */
@Component({
  selector: 'app-workspace-detail',
  imports: [
    RouterLink, MatTableModule, MatButtonModule, MatIconModule,
    MatTooltipModule, MatDialogModule, CycleDossier,
  ],
  templateUrl: './workspace-detail.html',
  styleUrl: './workspace-detail.scss',
})
export class WorkspaceDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private service = inject(WorkspaceService);
  private documents = inject(DocumentService);
  private dialog = inject(MatDialog);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  protected auth = inject(AuthService);

  /** Arborescence reçue au chargement : les dossiers de l'espace d'échange en viennent (ANO-F-016). */
  private arbre: TreeNode[] = [];

  id = signal<string | null>(null);
  espace = signal<WorkSpace | null>(null);
  sousDossiers = signal<TreeNode[]>([]);
  docs = signal<DocumentItem[]>([]);
  chargement = signal(true);
  introuvable = signal(false);

  /** Chemin depuis la racine, reconstruit à partir de l'arborescence complète. */
  chemin = signal<{ id: string; name: string }[]>([]);

  readonly colonnesDocs = ['name', 'type', 'size', 'actions'];

  ngOnInit(): void {
    this.route.paramMap.subscribe(p => {
      const id = p.get('id');
      if (!id) { this.introuvable.set(true); return; }
      this.id.set(id);
      this.charger();
    });
  }

  charger(): void {
    const id = this.id();
    if (id == null) return;
    this.chargement.set(true);
    this.introuvable.set(false);

    forkJoin({
      espace: this.service.get(id),
      arbre: this.service.tree(),
      // Le service documents accepte déjà un filtre par espace : inutile de tout
      // charger pour ne garder ensuite qu'une poignée de lignes.
      docs: this.documents.list(0, 200, '', id),
    }).subscribe({
      next: r => {
        this.espace.set(r.espace);
        this.arbre = r.arbre;
        this.sousDossiers.set(this.enfantsDe(r.arbre, id));
        this.chemin.set(this.cheminVers(r.arbre, id));
        this.docs.set(r.docs.content);
        this.chargement.set(false);
      },
      error: () => {
        this.chargement.set(false);
        this.introuvable.set(true);
      },
    });
  }

  /** Retrouve les enfants directs du dossier dans l'arborescence renvoyée à plat. */
  private enfantsDe(arbre: TreeNode[], id: string): TreeNode[] {
    const trouve = (noeuds: TreeNode[]): TreeNode | null => {
      for (const n of noeuds) {
        if (n.id === id) return n;
        const t = trouve(n.children ?? []);
        if (t) return t;
      }
      return null;
    };
    return trouve(arbre)?.children ?? [];
  }

  /** Fil d'Ariane : la suite des dossiers menant de la racine à celui-ci. */
  private cheminVers(arbre: TreeNode[], id: string): { id: string; name: string }[] {
    const parcours = (noeuds: TreeNode[], acc: { id: string; name: string }[]): { id: string; name: string }[] | null => {
      for (const n of noeuds) {
        const suite = [...acc, { id: n.id, name: n.name }];
        if (n.id === id) return suite;
        const t = parcours(n.children ?? [], suite);
        if (t) return t;
      }
      return null;
    };
    return parcours(arbre, []) ?? [];
  }

  statutLibelle(status: string): { label: string; cls: string } {
    if (status === 'ARCHIVE') return { label: 'Archivé', cls: 'st-arch' };
    if (status === 'INACTIF') return { label: 'Inactif', cls: 'st-inactif' };
    return { label: 'Actif', cls: 'st-actif' };
  }

  ouvrir(id: string): void {
    this.router.navigate(['/espaces-de-travail', id]);
  }

  modifier(): void {
    const w = this.espace();
    if (!w) return;
    this.dialog.open(WorkspaceForm, {
      data: { workspace: w, parentId: null }, width: '540px', maxWidth: '95vw', autoFocus: false,
    }).afterClosed().subscribe(ok => {
      if (!ok) return;
      this.charger();
      this.notify.success('Espace de travail modifié.');
    });
  }

  /** Espace d'échange (R-03, D12) : dossiers et dépôts ouverts aux membres qui peuvent Déposer. */
  echange(): boolean {
    return this.espace()?.usageEspace === 'ECHANGE';
  }

  creerSousDossier(): void {
    const id = this.id();
    if (id == null) return;
    if (this.echange()) { this.creerDossierEchange(id); return; }
    this.dialog.open(WorkspaceForm, {
      data: { workspace: null, parentId: id }, width: '540px', maxWidth: '95vw', autoFocus: false,
    }).afterClosed().subscribe(ok => {
      if (!ok) return;
      this.charger();
      this.notify.success('Sous-dossier créé.');
    });
  }

  /**
   * Espace d'échange (ANO-F-016) : formulaire simple (nom, description) sur
   * POST /noeuds/{id}/dossiers, et non le formulaire d'administration des
   * espaces (POST /workspaces, réservé à l'Administrateur).
   */
  private creerDossierEchange(parentId: string): void {
    this.dialog.open(DossierSimpleForm, {
      data: { parentId, parentNom: this.espace()?.name ?? '' }, width: '480px', maxWidth: '95vw',
    }).afterClosed().subscribe(cree => {
      if (!cree) return;
      this.charger();
      this.notify.success('Dossier créé.');
    });
  }

  /**
   * Dossiers de l'espace d'échange qui contient ce dossier (l'espace et tous
   * ses descendants), libellés par leur chemin : le dépôt peut y ranger le
   * document (D12, ANO-F-016).
   */
  dossiersDeLEspace(): { id: string; name: string }[] {
    const racine = this.chemin()[0];
    const trouve = (noeuds: TreeNode[]): TreeNode | null => {
      for (const n of noeuds) {
        if (n.id === racine?.id) return n;
        const t = trouve(n.children ?? []);
        if (t) return t;
      }
      return null;
    };
    const depart = racine ? trouve(this.arbre) : null;
    const liste: { id: string; name: string }[] = [];
    const parcourir = (n: TreeNode, prefixe: string) => {
      const nom = prefixe ? `${prefixe} / ${n.name}` : n.name;
      if (!n.passage) liste.push({ id: n.id, name: nom });
      for (const e of n.children ?? []) parcourir(e, nom);
    };
    if (depart) parcourir(depart, '');
    return liste;
  }

  /** Dépôt dans ce dossier d'un espace d'échange, avec choix du dossier cible (ANO-F-016). */
  deposerIci(): void {
    const id = this.id();
    if (id == null) return;
    const data: DonneesDepot = { dossiers: this.dossiersDeLEspace(), dossierId: id };
    this.dialog.open(DocumentUpload, { data, width: '560px', maxWidth: '95vw', autoFocus: false })
      .afterClosed().subscribe(issue => {
        if (!issue) return;
        this.charger();
        this.notify.success('Document déposé.');
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

  supprimerDocument(doc: DocumentItem): void {
    this.confirm.ask({
      title: 'Supprimer ce document',
      message: `« ${doc.name} » sera déplacé vers la corbeille.`,
      confirmLabel: 'Supprimer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.documents.delete(doc.id).subscribe({
        next: () => { this.charger(); this.notify.success('Document supprimé.'); },
        error: () => this.notify.error('Suppression impossible.'),
      });
    });
  }
}
