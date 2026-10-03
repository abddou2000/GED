import { Component, ElementRef, Injector, OnInit, afterNextRender, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTableModule } from '@angular/material/table';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { DocumentService } from '../../document/document.service';
import {
  Confidentialite, CritereIndexRecherche, DocumentItem, NIVEAUX_CONFIDENTIALITE, PageResult, RequeteRecherche, etatOcr,
} from '../../document/document.model';
import { IndexationService } from '../../indexation/indexation.service';
import { Critere } from '../../indexation/indexation.model';
import { TypeDocumentService } from '../../type-document/type-document.service';
import { WorkspaceService } from '../../workspace/workspace.service';
import { EmployeService, Personne } from '../../../core/employe.service';
import { NotifyService } from '../../../core/notify.service';
import { formaterDate } from '../../../core/dates';
import { TAILLES_PAGE, TAILLE_PAGE_DEFAUT } from '../../../core/pagination';

/** Saisie d'un critère d'index : valeur (texte, liste, booléen) ou bornes (date, nombre). */
interface SaisieIndex { valeur: string; de: string; a: string; }

/** Critères du socle commun (§4.4.3) : type, emplacement, texte, dates, confidentialité, déposant. */
interface Socle {
  texte: string;
  typeDocumentId: string | null;
  noeudId: string | null;
  statutConservation: '' | 'ACTIF' | 'ARCHIVE';
  echeanceDepassee: boolean;
  dateDocumentDu: string;
  dateDocumentAu: string;
  /** Plage de date de dépôt (ANO-F-028). */
  dateDepotDu: string;
  dateDepotAu: string;
  confidentialite: Confidentialite | null;
  deposantUtilisateurId: string | null;
}

const SOCLE_VIDE: Socle = {
  texte: '', typeDocumentId: null, noeudId: null, statutConservation: '', echeanceDepassee: false,
  dateDocumentDu: '', dateDocumentAu: '', dateDepotDu: '', dateDepotAu: '', confidentialite: null,
  deposantUtilisateurId: null,
};

/**
 * Recherche multicritère sur les index (§4.4.3, F-39 à F-42 ; ANO-F-010).
 *
 * <p>Les critères d'index ne sont pas écrits ici : ils sont générés depuis les
 * index cochés « indexé pour recherche » (GET /indexation/criteres) — un index
 * de plus, un critère de plus. Chaque nature a son contrôle : texte
 * (contient), liste et booléen (valeur exacte), date et nombre (plage). S'y
 * ajoutent les critères imposés du socle : type, emplacement, nom ou objet,
 * plage de date du document, confidentialité et déposant (ANO-F-011), plage
 * de date de dépôt (ANO-F-028).
 * Tous les critères renseignés se combinent en ET ; les vides ne filtrent pas.</p>
 *
 * <p>Appelle POST /documents/recherche : le serveur n'y renvoie que le
 * périmètre autorisé (total compris), paginé et trié par date du document,
 * nom ou date de dépôt.</p>
 */
@Component({
  selector: 'app-recherche-index',
  imports: [
    FormsModule, RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, MatTableModule, MatSortModule,
    MatPaginatorModule,
  ],
  templateUrl: './recherche-index.html',
  styleUrl: './recherche-index.scss',
})
export class RechercheIndex implements OnInit {
  private documents = inject(DocumentService);
  private indexation = inject(IndexationService);
  private types = inject(TypeDocumentService);
  private espaces = inject(WorkspaceService);
  private employes = inject(EmployeService);
  private notify = inject(NotifyService);
  private hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private injecteur = inject(Injector);

  readonly niveaux = NIVEAUX_CONFIDENTIALITE;
  readonly colonnes = ['name', 'type', 'emplacement', 'dateDocument', 'createdAt', 'createdBy'];
  readonly dateCourte = formaterDate;
  readonly etatOcr = etatOcr;

  criteresIndex = signal<Critere[]>([]);
  listeTypes = signal<{ id: string; name: string }[]>([]);
  listeEspaces = signal<{ id: string; name: string }[]>([]);
  personnes = signal<Personne[]>([]);

  socle: Socle = { ...SOCLE_VIDE };
  /** Saisies par code d'index. */
  saisies: Record<string, SaisieIndex> = {};

  resultat = signal<PageResult<DocumentItem> | null>(null);
  chargement = signal(false);
  erreur = signal<string | null>(null);
  /** Critères que le serveur a ignorés (en-tête GED-Champs-Ignores) : signalés, jamais tus. */
  champsIgnores = signal<string[]>([]);
  readonly taillesPage = TAILLES_PAGE;
  page = 0;
  taille = TAILLE_PAGE_DEFAUT;
  triChamp = 'dateDocument';
  triSens: 'asc' | 'desc' = 'desc';

  ngOnInit(): void {
    this.indexation.criteres().subscribe({
      next: c => {
        this.criteresIndex.set(c);
        for (const x of c) this.saisies[x.code] ??= { valeur: '', de: '', a: '' };
      },
      error: () => this.criteresIndex.set([]),
    });
    this.types.forSelect().subscribe({ next: l => this.listeTypes.set(l), error: () => this.listeTypes.set([]) });
    this.espaces.forSelect().subscribe({ next: l => this.listeEspaces.set(l), error: () => this.listeEspaces.set([]) });
    this.employes.personnes().subscribe(p => this.personnes.set(p));
  }

  /** Saisie d'un critère (créée à la volée si l'index est arrivé après l'écran). */
  saisie(code: string): SaisieIndex {
    return this.saisies[code] ??= { valeur: '', de: '', a: '' };
  }

  lancer(): void {
    this.page = 0;
    this.charger(true);
  }

  effacer(): void {
    this.socle = { ...SOCLE_VIDE };
    for (const code of Object.keys(this.saisies)) this.saisies[code] = { valeur: '', de: '', a: '' };
    this.erreur.set(null);
    this.resultat.set(null);
    this.champsIgnores.set([]);
  }

  trier(s: Sort): void {
    this.triChamp = s.active || 'dateDocument';
    this.triSens = s.direction === 'asc' ? 'asc' : 'desc';
    if (!s.direction) this.triChamp = 'dateDocument';
    this.page = 0;
    if (this.resultat()) this.charger();
  }

  pagination(e: PageEvent): void {
    this.page = e.pageIndex;
    this.taille = e.pageSize;
    this.charger();
  }

  /** Corps envoyé : seuls les critères renseignés ; `null` si une plage est incohérente. */
  requete(): RequeteRecherche | null {
    const s = this.socle;
    if (s.dateDocumentDu && s.dateDocumentAu && s.dateDocumentDu > s.dateDocumentAu) {
      this.erreur.set('Date du document : la date de début doit précéder la date de fin.');
      return null;
    }
    if (s.dateDepotDu && s.dateDepotAu && s.dateDepotDu > s.dateDepotAu) {
      this.erreur.set('Date de dépôt : la date de début doit précéder la date de fin.');
      return null;
    }
    const criteres: CritereIndexRecherche[] = [];
    for (const c of this.criteresIndex()) {
      const v = this.saisie(c.code);
      if (c.fieldType === 'DATE' || c.fieldType === 'NOMBRE') {
        const de = String(v.de ?? '').trim(), a = String(v.a ?? '').trim();
        if (!de && !a) continue;
        if (de && a && (c.fieldType === 'NOMBRE' ? Number(de) > Number(a) : de > a)) {
          this.erreur.set(`« ${c.libelle} » : la borne de début doit précéder la borne de fin.`);
          return null;
        }
        criteres.push({ code: c.code, de: de || null, a: a || null });
      } else {
        const valeur = String(v.valeur ?? '').trim();
        if (valeur) criteres.push({ code: c.code, valeur });
      }
    }
    this.erreur.set(null);
    return {
      texte: s.texte.trim() || null,
      typeDocumentId: s.typeDocumentId,
      noeudId: s.noeudId,
      criteres,
      statutConservation: s.statutConservation || null,
      echeanceDepassee: s.echeanceDepassee || null,
      dateDocumentDu: s.dateDocumentDu || null,
      dateDocumentAu: s.dateDocumentAu || null,
      dateDepotDu: s.dateDepotDu || null,
      dateDepotAu: s.dateDepotAu || null,
      confidentialite: s.confidentialite,
      deposantUtilisateurId: s.deposantUtilisateurId,
      page: this.page,
      size: this.taille,
    };
  }

  /** @param amener vrai pour une nouvelle recherche : les résultats sont amenés à l'écran. */
  private charger(amener = false): void {
    const r = this.requete();
    if (!r) return;
    this.chargement.set(true);
    this.documents.rechercher(r, this.triChamp, this.triSens).subscribe({
      next: r => {
        this.resultat.set(r.corps);
        this.champsIgnores.set(r.champsIgnores);
        this.chargement.set(false);
        if (amener) this.amenerResultats();
      },
      error: e => {
        this.chargement.set(false);
        this.champsIgnores.set([]);
        const msg = e?.error?.detail ?? e?.error?.message ?? 'La recherche a échoué.';
        this.erreur.set(msg);
        this.notify.error(msg);
      },
    });
  }

  /**
   * ANO-F-034 : les critères occupent le haut de l'écran ; sur un portable
   * (1366×768) les résultats commençaient sous le pli. Après une nouvelle
   * recherche, la zone de l'écran défile jusqu'au total et au tableau.
   */
  private amenerResultats(): void {
    afterNextRender(() => {
      const cible = this.hote.nativeElement.querySelector<HTMLElement>('.total');
      cible?.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
    }, { injector: this.injecteur });
  }
}
