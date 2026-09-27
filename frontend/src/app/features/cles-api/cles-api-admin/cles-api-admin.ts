import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NotifyService } from '../../../core/notify.service';
import { erreursParChamp, messageErreur } from '../../../core/probleme';
import { ApplicationApi, ApplicationRequest, CleApi, ClesApiService } from '../cles-api.service';

/**
 * Écran « Applications et clés d'API » (DAT §5.4) : applications clientes,
 * adresses autorisées, quotas ; génération, régénération (chevauchement) et
 * révocation des clés ; clés proches de l'expiration signalées.
 *
 * <p>La valeur complète d'une clé n'est montrée qu'une fois, juste après sa
 * génération : elle n'est ni conservée par le serveur ni relisible ensuite.
 */
@Component({
  selector: 'app-cles-api-admin',
  imports: [FormsModule, DatePipe, MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './cles-api-admin.html',
  styleUrl: './cles-api-admin.scss',
})
export class ClesApiAdmin implements OnInit {
  private service = inject(ClesApiService);
  private notify = inject(NotifyService);

  applications = signal<ApplicationApi[]>([]);
  chargement = signal(true);
  /** Clé générée à montrer une seule fois, avec l'application concernée. */
  cleAffichee = signal<{ application: string; valeur: string } | null>(null);
  erreurs = signal<Record<string, string>>({});

  formulaireOuvert = signal(false);
  enEdition = signal<string | null>(null);
  saisie = { code: '', nom: '', description: '', adresses: '', quotaMinute: 600, quotaJour: 100000 };
  delegation: Record<string, boolean> = {};
  motifs: Record<string, string> = {};

  ngOnInit(): void {
    this.charger();
  }

  charger(): void {
    this.chargement.set(true);
    this.service.lister().subscribe({
      next: l => { this.applications.set(l); this.chargement.set(false); },
      error: err => { this.chargement.set(false); this.notify.error(messageErreur(err, 'Chargement impossible.')); },
    });
  }

  nouvelle(): void {
    this.enEdition.set(null);
    this.saisie = { code: '', nom: '', description: '', adresses: '', quotaMinute: 600, quotaJour: 100000 };
    this.erreurs.set({});
    this.formulaireOuvert.set(true);
  }

  editer(a: ApplicationApi): void {
    this.enEdition.set(a.id);
    this.saisie = {
      code: a.code, nom: a.nom, description: a.description ?? '', adresses: a.adressesAutorisees.join(', '),
      quotaMinute: a.quotaMinute, quotaJour: a.quotaJour,
    };
    this.erreurs.set({});
    this.formulaireOuvert.set(true);
  }

  enregistrer(): void {
    const req: ApplicationRequest = {
      code: this.saisie.code.trim(),
      nom: this.saisie.nom.trim(),
      description: this.saisie.description.trim() || null,
      adressesAutorisees: this.saisie.adresses.split(',').map(s => s.trim()).filter(s => s),
      quotaMinute: Number(this.saisie.quotaMinute),
      quotaJour: Number(this.saisie.quotaJour),
    };
    const id = this.enEdition();
    const appel = id ? this.service.modifier(id, req) : this.service.creer(req);
    appel.subscribe({
      next: () => {
        this.formulaireOuvert.set(false);
        this.notify.success(id ? 'Application modifiée.' : 'Application créée.');
        this.charger();
      },
      error: err => {
        this.erreurs.set(erreursParChamp(err));
        this.notify.error(messageErreur(err, 'Enregistrement impossible.'));
      },
    });
  }

  basculerActivation(a: ApplicationApi): void {
    this.service.activer(a.id, !a.active).subscribe({
      next: () => this.charger(),
      error: err => this.notify.error(messageErreur(err, 'Opération impossible.')),
    });
  }

  generer(a: ApplicationApi): void {
    this.service.generer(a.id, !!this.delegation[a.id]).subscribe({
      next: g => { this.cleAffichee.set({ application: a.code, valeur: g.cle }); this.charger(); },
      error: err => this.notify.error(messageErreur(err, 'Génération impossible.')),
    });
  }

  regenerer(a: ApplicationApi, c: CleApi): void {
    this.service.regenerer(c.id).subscribe({
      next: g => {
        this.cleAffichee.set({ application: a.code, valeur: g.cle });
        this.notify.info("L'ancienne clé reste valide pendant la période de chevauchement.");
        this.charger();
      },
      error: err => this.notify.error(messageErreur(err, 'Régénération impossible.')),
    });
  }

  revoquer(c: CleApi): void {
    const motif = (this.motifs[c.id] ?? '').trim();
    if (!motif) {
      this.notify.error('Indiquez le motif de la révocation.');
      return;
    }
    this.service.revoquer(c.id, motif).subscribe({
      next: () => { this.notify.success('Clé révoquée : elle est refusée dès maintenant.'); this.charger(); },
      error: err => this.notify.error(messageErreur(err, 'Révocation impossible.')),
    });
  }

  copier(valeur: string): void {
    navigator.clipboard?.writeText(valeur).then(
      () => this.notify.success('Clé copiée.'),
      () => this.notify.error('Copie impossible : sélectionnez la clé et copiez-la.'));
  }

  fermerCle(): void {
    this.cleAffichee.set(null);
  }

  libelleEtat(c: CleApi): string {
    switch (c.etat) {
      case 'ACTIVE': return c.expireBientot ? 'Expire bientôt' : 'Active';
      case 'EN_CHEVAUCHEMENT': return 'Remplacée (chevauchement)';
      case 'EXPIREE': return 'Expirée';
      case 'REVOQUEE': return 'Révoquée';
    }
  }
}
