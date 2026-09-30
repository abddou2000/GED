import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Observable, Subscription, forkJoin, of, timer } from 'rxjs';
import { catchError, map, shareReplay, switchMap } from 'rxjs/operators';
import { TypeDocumentService } from '../type-document.service';
import {
  JobRetypage, LIBELLES_STATUT_RETYPAGE, StatutRetypage, TypeDocument,
} from '../type-document.model';
import { PlanIndexationService } from '../../plan-indexation/plan-indexation.service';
import { IndexService } from '../../index/index.service';
import { IndexField } from '../../index/index.model';
import { DocumentService } from '../../document/document.service';
import { DocumentItem } from '../../document/document.model';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';
import { formaterDate } from '../../../core/dates';

/** Un champ du plan d'indexation d'un type : code (clé des métadonnées) et libellé. */
export interface ChampPlan {
  code: string;
  libelle: string;
}

/**
 * Re-typologie en lot (§4.2.4, §12.7, ANO-F-015) : l'administrateur des
 * référentiels fait passer les documents d'un type (tous, ou une sélection)
 * à un autre type, en disant ce que devient chaque champ du plan source.
 *
 * <p>Le serveur exécute le travail en arrière-plan, un document par
 * transaction ; l'écran suit sa progression puis affiche le rapport (réussis,
 * échecs et motifs, champs perdus). Un champ de même code est conservé sans
 * rien dire ; un champ sans équivalent dans le plan cible est perdu.
 */
@Component({
  selector: 'app-retypage-lot',
  imports: [RouterLink, MatButtonModule, MatIconModule],
  templateUrl: './retypage-lot.html',
  styleUrl: './retypage-lot.scss',
})
export class RetypageLot implements OnInit {
  private service = inject(TypeDocumentService);
  private plans = inject(PlanIndexationService);
  private indexApi = inject(IndexService);
  private documentsApi = inject(DocumentService);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);
  private route = inject(ActivatedRoute);
  private destroyRef = inject(DestroyRef);

  /** Intervalle de suivi d'un travail en cours, en millisecondes. */
  intervalleSuivi = 2000;
  readonly date = formaterDate;

  types = signal<TypeDocument[]>([]);
  sourceId = signal('');
  cibleId = signal('');
  champsSource = signal<ChampPlan[]>([]);
  champsCible = signal<ChampPlan[]>([]);
  /** Code source → code cible ; '' = champ abandonné. */
  correspondance = signal<Record<string, string>>({});

  /** `tous` : tous les documents vivants du type source ; sinon la sélection. */
  portee = signal<'tous' | 'selection'>('tous');
  documents = signal<DocumentItem[]>([]);
  choisis = signal<ReadonlySet<string>>(new Set());

  envoi = signal(false);
  suivi = signal<JobRetypage | null>(null);
  historique = signal<JobRetypage[]>([]);

  /** Index déjà lus (le plan ne donne que l'identifiant et le libellé, pas le code). */
  private indices = new Map<string, Observable<IndexField | null>>();
  private suiviEnCours: Subscription | null = null;

  /** Un type désactivé n'accepte plus de document : il ne peut pas être cible. */
  readonly ciblesPossibles = computed(() =>
    this.types().filter(t => t.id !== this.sourceId() && t.actif !== false));

  readonly perdus = computed(() => {
    const corr = this.correspondance();
    return this.champsSource().filter(c => !corr[c.code]).map(c => c.libelle);
  });

  readonly peutLancer = computed(() =>
    !!this.sourceId() && !!this.cibleId() && !this.envoi() &&
    (this.portee() === 'tous' || this.choisis().size > 0));

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => this.suiviEnCours?.unsubscribe());
    this.service.list(0, 200, '', 'typeDeDocument', 'asc').pipe(map(p => p.content)).subscribe({
      next: types => {
        this.types.set(types);
        const source = this.route.snapshot.queryParamMap.get('source');
        if (source && types.some(t => t.id === source)) this.choisirSource(source);
      },
      error: () => this.notify.error('Chargement des types impossible.'),
    });
    this.chargerHistorique();
  }

  chargerHistorique(): void {
    this.service.retypages().subscribe({
      next: h => this.historique.set(h),
      error: () => this.historique.set([]),
    });
  }

  nomType(id: string): string {
    return this.types().find(t => t.id === id)?.typeDeDocument ?? '—';
  }

  libelleStatut(s: StatutRetypage): string {
    return LIBELLES_STATUT_RETYPAGE[s] ?? s;
  }

  choisirSource(id: string): void {
    this.sourceId.set(id);
    if (this.cibleId() === id) this.cibleId.set('');
    this.choisis.set(new Set());
    this.documents.set([]);
    this.champs(id).subscribe(c => { this.champsSource.set(c); this.proposerCorrespondance(); });
    if (this.portee() === 'selection') this.chargerDocuments();
  }

  choisirCible(id: string): void {
    this.cibleId.set(id);
    this.champs(id).subscribe(c => { this.champsCible.set(c); this.proposerCorrespondance(); });
  }

  /** Par défaut, un champ va au champ de même code du plan cible, s'il existe. */
  private proposerCorrespondance(): void {
    const cibles = new Set(this.champsCible().map(c => c.code));
    const corr: Record<string, string> = {};
    for (const c of this.champsSource()) corr[c.code] = cibles.has(c.code) ? c.code : '';
    this.correspondance.set(corr);
  }

  associer(codeSource: string, codeCible: string): void {
    this.correspondance.update(c => ({ ...c, [codeSource]: codeCible }));
  }

  changerPortee(p: 'tous' | 'selection'): void {
    this.portee.set(p);
    if (p === 'selection' && this.sourceId() && !this.documents().length) this.chargerDocuments();
  }

  basculerDocument(id: string): void {
    this.choisis.update(s => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id); else n.add(id);
      return n;
    });
  }

  /** Documents du type source, rangés dans le dossier du type. */
  private chargerDocuments(): void {
    const t = this.types().find(x => x.id === this.sourceId());
    if (!t?.workspace) { this.documents.set([]); return; }
    this.documentsApi.list(0, 200, '', t.workspace.id).subscribe({
      next: p => this.documents.set(p.content.filter(d => d.typeDocument?.id === t.id)),
      error: () => this.documents.set([]),
    });
  }

  /** Champs du plan d'indexation d'un type (vide pour un type sans plan). */
  private champs(typeId: string) {
    const t = this.types().find(x => x.id === typeId);
    if (!t?.planIndexation) return of<ChampPlan[]>([]);
    return this.plans.get(t.planIndexation.id).pipe(
      switchMap(p => p.indices.length
        ? forkJoin(p.indices.map(r => this.index(r.id).pipe(
            map(i => ({ code: i?.code ?? '', libelle: i?.nomIndex ?? r.label })))))
        : of<ChampPlan[]>([])),
      map(champs => champs.filter(c => !!c.code)),
      catchError(() => of<ChampPlan[]>([])),
    );
  }

  private index(id: string): Observable<IndexField | null> {
    let i = this.indices.get(id);
    if (!i) {
      i = this.indexApi.get(id).pipe(catchError(() => of(null)), shareReplay(1));
      this.indices.set(id, i);
    }
    return i;
  }

  lancer(): void {
    if (!this.peutLancer()) return;
    const source = this.sourceId();
    const cible = this.cibleId();
    const nombre = this.portee() === 'tous' ? 'tous les documents' : `${this.choisis().size} document(s)`;
    const perdus = this.perdus();
    this.confirm.ask({
      title: 'Lancer la re-typologie',
      message: `${nombre} du type « ${this.nomType(source)} » passeront au type « ${this.nomType(cible)} ».`
        + (perdus.length ? ` Champs sans équivalent, perdus : ${perdus.join(', ')}.` : ''),
      confirmLabel: 'Lancer',
    }).subscribe(ok => {
      if (!ok) return;
      // Seuls les renommages partent : un champ de même code est conservé par le serveur.
      const correspondance: Record<string, string> = {};
      for (const [k, v] of Object.entries(this.correspondance())) if (v && v !== k) correspondance[k] = v;
      this.envoi.set(true);
      this.service.lancerRetypage({
        sourceTypeDocumentId: source,
        cibleTypeDocumentId: cible,
        correspondance,
        documentIds: this.portee() === 'selection' ? [...this.choisis()] : [],
      }).subscribe({
        next: job => {
          this.envoi.set(false);
          this.notify.success('Re-typologie lancée.');
          this.afficher(job);
        },
        error: err => {
          this.envoi.set(false);
          this.notify.error(err?.error?.message ?? 'Lancement impossible.');
        },
      });
    });
  }

  /** Affiche un travail ; le suit tant qu'il n'est pas terminé. */
  afficher(job: JobRetypage): void {
    this.suiviEnCours?.unsubscribe();
    this.suivi.set(job);
    if (job.statut === 'EN_ATTENTE' || job.statut === 'EN_COURS') {
      this.suiviEnCours = timer(this.intervalleSuivi).subscribe(() => this.actualiserSuivi());
    } else {
      this.chargerHistorique();
    }
  }

  actualiserSuivi(): void {
    const job = this.suivi();
    if (!job) return;
    this.service.retypage(job.id).subscribe({
      next: j => this.afficher(j),
      error: () => this.notify.error('Suivi du travail impossible.'),
    });
  }

  progression(j: JobRetypage): number {
    return j.total ? Math.round((j.traites / j.total) * 100) : 100;
  }
}
