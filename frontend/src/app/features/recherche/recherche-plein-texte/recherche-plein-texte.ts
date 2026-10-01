import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { RechercheService } from '../recherche.service';
import { CriteresRecherche, PageResultats, TriRecherche } from '../recherche.model';
import { TypeDocumentService } from '../../type-document/type-document.service';
import { WorkspaceService } from '../../workspace/workspace.service';
import { NotifyService } from '../../../core/notify.service';
import { TAILLES_PAGE, TAILLE_PAGE_DEFAUT } from '../../../core/pagination';

/**
 * Recherche plein texte dans le contenu des documents (§4.4) : syntaxe
 * « websearch » (guillemets, exclusion par « - »), critères de métadonnées,
 * tris et pagination. Les extraits arrivent en segments : ils sont affichés
 * comme du texte, le surlignage par une balise mark — jamais d'HTML venu du
 * serveur, le texte OCR pouvant contenir n'importe quoi.
 */
@Component({
  selector: 'app-recherche-plein-texte',
  imports: [FormsModule, RouterLink, DatePipe, MatButtonModule, MatIconModule, MatPaginatorModule],
  templateUrl: './recherche-plein-texte.html',
  styleUrl: './recherche-plein-texte.scss',
})
export class RecherchePleinTexte implements OnInit {
  private service = inject(RechercheService);
  private types = inject(TypeDocumentService);
  private espaces = inject(WorkspaceService);
  private notify = inject(NotifyService);

  readonly TRIS: { valeur: TriRecherche; libelle: string }[] = [
    { valeur: 'PERTINENCE', libelle: 'Pertinence' },
    { valeur: 'DATE_DOCUMENT', libelle: 'Date du document' },
    { valeur: 'DATE_DEPOT', libelle: 'Date de dépôt' },
    { valeur: 'NOM', libelle: 'Nom' },
    { valeur: 'TYPE', libelle: 'Type de document' },
  ];

  criteres: CriteresRecherche = { q: '', tri: 'PERTINENCE', typeDocumentId: null, workspaceId: null, du: null, au: null, archives: 'INCLURE' };
  listeTypes = signal<{ id: string; libelle: string }[]>([]);
  listeEspaces = signal<{ id: string; libelle: string }[]>([]);
  resultat = signal<PageResultats | null>(null);
  chargement = signal(false);
  /** Critères que le serveur a ignorés (en-tête GED-Champs-Ignores) : signalés, jamais tus. */
  champsIgnores = signal<string[]>([]);
  readonly taillesPage = TAILLES_PAGE;
  page = 0;
  taille = TAILLE_PAGE_DEFAUT;

  ngOnInit(): void {
    this.types.list(0, 500).subscribe({
      next: p => this.listeTypes.set(p.content.map(t => ({ id: t.id, libelle: t.typeDeDocument }))),
      error: () => this.listeTypes.set([]),
    });
    this.espaces.list(0, 500).subscribe({
      next: p => this.listeEspaces.set(p.content.map(w => ({ id: w.id, libelle: w.name }))),
      error: () => this.listeEspaces.set([]),
    });
  }

  lancer(): void {
    this.page = 0;
    this.charger();
  }

  pagination(e: PageEvent): void {
    this.page = e.pageIndex;
    this.taille = e.pageSize;
    this.charger();
  }

  private charger(): void {
    if (!this.criteres.q.trim()) {
      this.resultat.set(null);
      this.champsIgnores.set([]);
      return;
    }
    this.chargement.set(true);
    this.service.rechercher(this.criteres, this.page, this.taille).subscribe({
      next: r => {
        this.resultat.set(r.corps);
        this.champsIgnores.set(r.champsIgnores);
        this.chargement.set(false);
      },
      error: e => {
        this.chargement.set(false);
        this.champsIgnores.set([]);
        this.notify.error(e?.error?.message ?? 'La recherche a échoué.');
      },
    });
  }
}
