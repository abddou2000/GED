import { Component, DestroyRef, OnChanges, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription, interval, switchMap } from 'rxjs';
import { CycleDeVieService } from '../cycle-de-vie.service';
import { ElementJob, JobArchivage } from '../cycle-de-vie.model';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';

/**
 * Actions de cycle de vie d'un dossier : export ZIP (§12.10) et archivage du
 * dossier entier (revue client D10) avec suivi de progression, annulation
 * entre deux tranches et rapport final.
 */
@Component({
  selector: 'app-cycle-dossier',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './cycle-dossier.html',
  styleUrl: './cycle-dossier.scss',
})
export class CycleDossier implements OnChanges {
  private service = inject(CycleDeVieService);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  readonly dossierId = input.required<string>();
  readonly dossierNom = input<string>('');
  /** Drapeau d'archivage du dossier. */
  readonly archive = input<boolean>(false);
  /** Le dossier a changé (drapeau posé ou retiré) : l'écran parent recharge. */
  readonly change = output<void>();

  job = signal<JobArchivage | null>(null);
  anomalies = signal<ElementJob[]>([]);
  exportEnCours = signal(false);

  readonly enCours = computed(() => {
    const j = this.job();
    return j != null && (j.etat === 'EN_ATTENTE' || j.etat === 'EN_COURS');
  });
  readonly progression = computed(() => {
    const j = this.job();
    return j && j.total > 0 ? Math.round((100 * j.traites) / j.total) : 0;
  });

  private suivi?: Subscription;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.suivi?.unsubscribe());
  }

  ngOnChanges(): void {
    this.service.jobs(this.dossierId()).subscribe({
      next: jobs => this.afficher(jobs[0] ?? null),
      error: () => this.job.set(null),
    });
  }

  private afficher(j: JobArchivage | null): void {
    this.job.set(j);
    this.suivi?.unsubscribe();
    if (j && (j.etat === 'EN_ATTENTE' || j.etat === 'EN_COURS')) {
      this.suivi = interval(2000).pipe(switchMap(() => this.service.job(j.id))).subscribe(maj => {
        this.job.set(maj);
        if (maj.etat === 'TERMINE' || maj.etat === 'ANNULE') {
          this.suivi?.unsubscribe();
          this.chargerRapport(maj);
          this.change.emit();
        }
      });
    } else if (j) {
      this.chargerRapport(j);
    }
  }

  private chargerRapport(j: JobArchivage): void {
    if (!j.anomalies && !j.echecs) { this.anomalies.set([]); return; }
    this.service.elements(j.id, j.echecs ? 'ECHEC' : 'ANOMALIE', 0, 20).subscribe({
      next: e => this.anomalies.set(e),
      error: () => this.anomalies.set([]),
    });
  }

  archiverDossier(): void {
    this.confirm.ask({
      title: 'Archiver tout le dossier ?',
      message: `Tous les documents actifs de « ${this.dossierNom()} » et de ses sous-dossiers passeront en lecture seule, `
        + "avec une copie de conservation PDF/A. Le dossier n'acceptera plus de dépôt. Le traitement se fait en arrière-plan.",
      confirmLabel: 'Archiver le dossier',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.service.archiverDossier(this.dossierId()).subscribe({
        next: j => { this.notify.success(`Archivage lancé : ${j.total} document(s).`); this.afficher(j); this.change.emit(); },
        error: err => this.notify.error(err?.error?.message ?? 'Archivage impossible.'),
      });
    });
  }

  annuler(): void {
    const j = this.job();
    if (!j) return;
    this.service.annuler(j.id).subscribe({
      next: maj => { this.notify.info('Annulation demandée : prise en compte à la fin de la tranche en cours.'); this.job.set(maj); },
      error: err => this.notify.error(err?.error?.message ?? 'Annulation impossible.'),
    });
  }

  retirerDrapeau(): void {
    this.confirm.ask({
      title: "Retirer le drapeau d'archivage ?",
      message: 'Le dossier acceptera de nouveau des dépôts. Ses documents archivés le restent.',
      confirmLabel: 'Retirer',
    }).subscribe(ok => {
      if (!ok) return;
      this.service.retirerDrapeau(this.dossierId()).subscribe({
        next: () => { this.notify.success('Drapeau retiré.'); this.change.emit(); },
        error: err => this.notify.error(err?.error?.message ?? 'Opération impossible.'),
      });
    });
  }

  exporter(): void {
    if (this.exportEnCours()) return;
    this.exportEnCours.set(true);
    this.service.exporterDossier(this.dossierId()).subscribe({
      next: r => {
        this.exportEnCours.set(false);
        if ('archive' in r) {
          this.service.enregistrer(r.archive, `${this.dossierNom() || 'export'}.zip`);
        } else {
          this.notify.info(`Export volumineux (${r.differe.nbDocuments} documents) préparé en arrière-plan : `
            + 'il sera disponible dans « Mes exports ».');
        }
      },
      error: () => { this.exportEnCours.set(false); this.notify.error('Export impossible.'); },
    });
  }
}
