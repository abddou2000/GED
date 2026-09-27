import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, JsonPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NotifyService } from '../../../core/notify.service';
import { messageErreur } from '../../../core/probleme';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { AuditService, FiltreAudit, LigneAudit } from '../audit.service';

/**
 * Écran « Journal d'audit » (DAT §7.4.3) : consultation filtrée par période,
 * utilisateur, application, action, objet et résultat ; export CSV et JSON
 * avec empreinte ; vérification du scellement.
 *
 * <p>Lecture seule : aucune action ne modifie ni ne supprime un enregistrement
 * (revue technique D11). Chaque consultation et chaque export sont eux-mêmes
 * tracés par le serveur.
 */
@Component({
  selector: 'app-journal-audit',
  imports: [FormsModule, DatePipe, JsonPipe, MatButtonModule, MatIconModule, MatPaginatorModule,
    MatTooltipModule, SkeletonTable],
  templateUrl: './journal-audit.html',
  styleUrl: './journal-audit.scss',
})
export class JournalAudit implements OnInit {
  private service = inject(AuditService);
  private notify = inject(NotifyService);

  readonly RESULTATS = [
    { valeur: '', libelle: 'Tous' },
    { valeur: 'SUCCES', libelle: 'Succès' },
    { valeur: 'REFUS', libelle: 'Refus' },
    { valeur: 'ECHEC', libelle: 'Échec' },
  ];

  /** Saisie des critères ; `du`/`au` au format du champ datetime-local (heure locale). */
  criteres: FiltreAudit = {};
  lignes = signal<LigneAudit[]>([]);
  total = signal(0);
  page = signal(0);
  taille = signal(50);
  chargement = signal(true);
  ouverte = signal<number | null>(null);
  verification = signal<string | null>(null);

  ngOnInit(): void {
    this.charger();
  }

  rechercher(): void {
    this.page.set(0);
    this.charger();
  }

  reinitialiser(): void {
    this.criteres = {};
    this.rechercher();
  }

  changerPage(e: PageEvent): void {
    this.page.set(e.pageIndex);
    this.taille.set(e.pageSize);
    this.charger();
  }

  basculer(id: number): void {
    this.ouverte.set(this.ouverte() === id ? null : id);
  }

  charger(): void {
    this.chargement.set(true);
    this.service.evenements(this.filtre(), this.page(), this.taille()).subscribe({
      next: p => {
        this.lignes.set(p.content);
        this.total.set(p.total);
        this.chargement.set(false);
      },
      error: err => {
        this.chargement.set(false);
        this.notify.error(messageErreur(err, 'Consultation du journal impossible.'));
      },
    });
  }

  exporter(format: 'csv' | 'json'): void {
    this.service.exporter(this.filtre(), format).subscribe({
      next: rep => {
        const nom = nomDepuisEntete(rep.headers.get('Content-Disposition')) ?? `journal-audit.${format}`;
        if (rep.body) enregistrer(rep.body, nom);
        const empreinte = rep.headers.get('X-Empreinte-SHA256');
        this.notify.success(`Export enregistré${empreinte ? ` — SHA-256 ${empreinte.slice(0, 16)}…` : ''}`);
      },
      error: err => this.notify.error(messageErreur(err, 'Export impossible.')),
    });
  }

  verifier(): void {
    this.verification.set('Vérification en cours…');
    this.service.verifier().subscribe({
      next: r => {
        const texte = r.anomalies.length === 0
          ? `Chaîne intègre : ${r.periodesVerifiees} périodes, ${r.enregistrementsVerifies} enregistrements vérifiés.`
          : `${r.anomalies.length} anomalie(s) : ${r.anomalies[0].type}${r.anomalies[0].periodeDebut ? ' (' + r.anomalies[0].periodeDebut + ')' : ''}.`;
        this.verification.set(texte);
        if (r.anomalies.length === 0) this.notify.success('Scellement vérifié : aucune anomalie.');
        else this.notify.error('Scellement : anomalies détectées.');
      },
      error: err => {
        this.verification.set(null);
        this.notify.error(messageErreur(err, 'Vérification impossible.'));
      },
    });
  }

  /** Les champs datetime-local sont en heure locale : on les convertit en instants UTC. */
  private filtre(): FiltreAudit {
    return {
      ...this.criteres,
      du: this.criteres.du ? new Date(this.criteres.du).toISOString() : undefined,
      au: this.criteres.au ? new Date(this.criteres.au).toISOString() : undefined,
    };
  }
}

function nomDepuisEntete(entete: string | null): string | null {
  if (!entete) return null;
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(entete);
  if (utf8) return decodeURIComponent(utf8[1]);
  const simple = /filename="?([^";]+)"?/i.exec(entete);
  return simple ? simple[1] : null;
}

function enregistrer(blob: Blob, nom: string): void {
  const href = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = href;
    a.download = nom;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(href));
  }
}
