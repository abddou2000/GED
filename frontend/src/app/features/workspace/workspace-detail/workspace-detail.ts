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

  creerSousDossier(): void {
    const id = this.id();
    if (id == null) return;
    this.dialog.open(WorkspaceForm, {
      data: { workspace: null, parentId: id }, width: '540px', maxWidth: '95vw', autoFocus: false,
    }).afterClosed().subscribe(ok => {
      if (!ok) return;
      this.charger();
      this.notify.success('Sous-dossier créé.');
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
