import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RechercheService } from '../recherche.service';
import { EtatOcr, OcrJob, ProgressionReindexation, StatutOcr } from '../recherche.model';
import { NotifyService } from '../../../core/notify.service';

/**
 * Supervision des traitements OCR (§4.3.4) : compteurs par état, jobs en
 * attente ou en échec avec leur motif, relance manuelle d'un échec, et
 * réindexation complète en tâche de fond avec sa progression (§4.4.1).
 * Réservé à l'Administrateur (contrôle posé côté serveur par le lot autorisation).
 */
@Component({
  selector: 'app-supervision-ocr',
  imports: [DatePipe, RouterLink, MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './supervision-ocr.html',
  styleUrl: './supervision-ocr.scss',
})
export class SupervisionOcr implements OnInit, OnDestroy {
  private service = inject(RechercheService);
  private notify = inject(NotifyService);

  readonly STATUTS: { valeur: StatutOcr; libelle: string }[] = [
    { valeur: 'EN_ATTENTE_OCR', libelle: 'En attente' },
    { valeur: 'EN_COURS_OCR', libelle: 'En cours' },
    { valeur: 'OCR_ECHEC', libelle: 'En échec' },
    { valeur: 'OCR_TERMINE', libelle: 'Terminés' },
  ];

  etat = signal<EtatOcr | null>(null);
  compteurs = signal<Record<StatutOcr, number> | null>(null);
  statut = signal<StatutOcr>('OCR_ECHEC');
  jobs = signal<OcrJob[]>([]);
  reindexation = signal<ProgressionReindexation | null>(null);
  private suivi: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.service.etat().subscribe({ next: e => this.etat.set(e), error: () => this.etat.set(null) });
    this.rafraichir();
    this.service.progressionReindexation().subscribe({ next: p => this.suivre(p), error: () => {} });
  }

  ngOnDestroy(): void {
    this.arreterSuivi();
  }

  rafraichir(): void {
    this.service.compteurs().subscribe({ next: c => this.compteurs.set(c), error: () => this.compteurs.set(null) });
    this.service.jobs(this.statut()).subscribe({ next: j => this.jobs.set(j), error: () => this.jobs.set([]) });
  }

  choisir(s: StatutOcr): void {
    this.statut.set(s);
    this.rafraichir();
  }

  relancer(job: OcrJob): void {
    this.service.relancer(job.id).subscribe({
      next: () => { this.notify.success('Traitement relancé.'); this.rafraichir(); },
      error: e => this.notify.error(e?.error?.message ?? 'Relance impossible.'),
    });
  }

  reindexer(): void {
    this.service.lancerReindexation().subscribe({
      next: p => { this.notify.info('Réindexation complète lancée.'); this.suivre(p); },
      error: e => {
        if (e?.status === 409) {
          this.notify.info('Une réindexation est déjà en cours.');
          this.suivre(e.error);
        } else {
          this.notify.error(e?.error?.message ?? 'Réindexation impossible.');
        }
      },
    });
  }

  pourcentage(p: ProgressionReindexation): number {
    return p.total > 0 ? Math.min(100, Math.round(p.traites * 100 / p.total)) : (p.etat === 'TERMINEE' ? 100 : 0);
  }

  /** Suit la progression toutes les 2 s tant que la réindexation tourne. */
  private suivre(p: ProgressionReindexation): void {
    this.reindexation.set(p);
    if (p?.etat === 'EN_COURS' && !this.suivi) {
      this.suivi = setInterval(() => this.service.progressionReindexation().subscribe({
        next: q => { this.reindexation.set(q); if (q.etat !== 'EN_COURS') this.arreterSuivi(); },
        error: () => this.arreterSuivi(),
      }), 2000);
    }
  }

  private arreterSuivi(): void {
    if (this.suivi) { clearInterval(this.suivi); this.suivi = null; }
  }
}
