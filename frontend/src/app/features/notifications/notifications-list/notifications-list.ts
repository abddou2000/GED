import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { NotifyService } from '../../../core/notify.service';
import { messageErreur } from '../../../core/probleme';
import { NotificationGed, NotificationsService } from '../notifications.service';

/**
 * Centre de notifications (DAT §12.9) : circuits de validation, accès attribués
 * à un espace, fins de conservation — les trois seuls cas notifiés. Préférence :
 * l'e-mail peut être désactivé, la notification dans l'application subsiste.
 */
@Component({
  selector: 'app-notifications-list',
  imports: [DatePipe, MatButtonModule, MatSlideToggleModule],
  templateUrl: './notifications-list.html',
  styleUrl: './notifications-list.scss',
})
export class NotificationsList implements OnInit {
  private service = inject(NotificationsService);
  private notify = inject(NotifyService);
  private router = inject(Router);

  protected nonLues = this.service.nonLues;
  notifications = signal<NotificationGed[]>([]);
  total = signal(0);
  page = signal(0);
  chargement = signal(true);
  seulementNonLues = signal(false);
  courrielActif = signal(true);

  readonly familles: Record<NotificationGed['famille'], string> = {
    CIRCUIT_VALIDATION: 'Circuit de validation',
    ACCES_ESPACE: 'Accès à un espace',
    FIN_CONSERVATION: 'Fin de conservation',
  };

  ngOnInit(): void {
    this.charger();
    this.service.preference().subscribe({
      next: p => this.courrielActif.set(p.courrielActif),
      error: () => { /* la préférence reste affichée à sa valeur par défaut */ },
    });
  }

  charger(page = 0): void {
    this.chargement.set(true);
    this.service.lister(this.seulementNonLues(), page).subscribe({
      next: p => {
        this.notifications.set(p?.content ?? []);
        this.total.set(p?.total ?? 0);
        this.page.set(p?.page ?? 0);
        this.chargement.set(false);
      },
      error: err => { this.chargement.set(false); this.notify.error(messageErreur(err, 'Chargement impossible.')); },
    });
    this.service.rafraichirCompteur();
  }

  basculerFiltre(): void {
    this.seulementNonLues.update(v => !v);
    this.charger();
  }

  marquerLue(n: NotificationGed): void {
    if (n.lue) return;
    this.service.marquerLue(n.id).subscribe({
      next: maj => this.notifications.update(l => l.map(x => x.id === maj.id ? maj : x)),
      error: err => this.notify.error(messageErreur(err, 'Opération impossible.')),
    });
  }

  toutMarquerLu(): void {
    this.service.toutMarquerLu().subscribe({
      next: () => this.charger(this.page()),
      error: err => this.notify.error(messageErreur(err, 'Opération impossible.')),
    });
  }

  ouvrir(n: NotificationGed): void {
    this.marquerLue(n);
    if (n.lien) this.router.navigateByUrl('/' + n.lien);
  }

  changerCourriel(actif: boolean): void {
    this.service.definirPreference(actif).subscribe({
      next: p => {
        this.courrielActif.set(p.courrielActif);
        this.notify.success(p.courrielActif
          ? 'Vous recevrez aussi vos notifications par e-mail.'
          : 'E-mails désactivés : vos notifications restent visibles ici.');
      },
      error: err => {
        this.courrielActif.set(!actif);
        this.notify.error(messageErreur(err, 'Préférence non enregistrée.'));
      },
    });
  }

  pagePrecedente(): void { if (this.page() > 0) this.charger(this.page() - 1); }
  pageSuivante(): void { if ((this.page() + 1) * 50 < this.total()) this.charger(this.page() + 1); }
}
