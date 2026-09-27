import { Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { CycleDeVieService } from '../cycle-de-vie.service';
import { ExportDossier } from '../cycle-de-vie.model';
import { NotifyService } from '../../../core/notify.service';
import { formaterDate } from '../../../core/dates';

/**
 * Exports de dossier volumineux (au-delà de 500 documents ou de 2 Go, §12.10),
 * produits en arrière-plan : téléchargeables par leur seul demandeur jusqu'à
 * leur expiration.
 */
@Component({
  selector: 'app-mes-exports',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './mes-exports.html',
  styleUrl: './mes-exports.scss',
})
export class MesExports implements OnInit {
  private service = inject(CycleDeVieService);
  private notify = inject(NotifyService);

  exports = signal<ExportDossier[]>([]);
  chargement = signal(true);
  readonly date = formaterDate;

  ngOnInit(): void {
    this.charger();
  }

  charger(): void {
    this.chargement.set(true);
    this.service.mesExports().subscribe({
      next: e => { this.exports.set(e); this.chargement.set(false); },
      error: () => { this.exports.set([]); this.chargement.set(false); },
    });
  }

  libelle(etat: ExportDossier['etat']): string {
    return {
      EN_ATTENTE: 'En attente', EN_COURS: 'En préparation', TERMINE: 'Disponible',
      ECHEC: 'En échec', EXPIRE: 'Expiré',
    }[etat];
  }

  taille(o: number | null): string {
    if (o == null) return '—';
    const mo = o / (1024 * 1024);
    return mo >= 1024 ? `${(mo / 1024).toFixed(1).replace('.', ',')} Go` : `${mo.toFixed(1).replace('.', ',')} Mo`;
  }

  telecharger(e: ExportDossier): void {
    this.service.telechargerExport(e.id).subscribe({
      next: blob => this.service.enregistrer(blob, `${e.dossierNom || 'export'}.zip`),
      error: () => this.notify.error('Téléchargement impossible.'),
    });
  }
}
