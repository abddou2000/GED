import { Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { StatTiles } from '../../../core/stat-tiles/stat-tiles';
import { SessionService } from '../../../core/session.service';
import { SignatureService } from '../../signature/signature.service';
import { Signature } from '../../signature/signature.model';

interface Shortcut { label: string; hint: string; icon: string; route: string; cls: string; }

/**
 * Tableau de bord d'accueil — la PREMIÈRE vue. Il explique la valeur et oriente :
 * indicateurs, documents à valider et raccourcis d'action.
 * 100 % lecture : `GET /stats/overview`, `GET /signatures/pending`.
 */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MatIconModule, MatButtonModule, StatTiles],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  private session = inject(SessionService);
  private signatures = inject(SignatureService);

  protected readonly firstName = this.session.firstName;
  protected readonly initials = this.session.initials;
  protected readonly todayLabel = this.capitalize(
    new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(new Date()),
  );

  protected pending = signal<Signature[]>([]);
  protected pendingLoading = signal(true);

  protected readonly shortcuts: Shortcut[] = [
    { label: 'Déposer un document', hint: 'Téléverser et lancer le circuit', icon: 'nav-upload', route: '/televerser', cls: 'sc-cramoisi' },
    { label: 'Créer un espace', hint: 'Nouvel espace de travail', icon: 'folder-plus', route: '/espaces-de-travail', cls: 'sc-marine' },
    { label: 'Mes workflow', hint: 'Tout ce que je dois valider', icon: 'nav-mesworkflow', route: '/mes-workflow', cls: 'sc-vert' },
  ];

  constructor() {
    this.session.ensureUser();       // identité si l'on arrive directement sur /accueil

    // Recharge « À valider » dès que l'identité de session est résolue (id disponible).
    effect(() => {
      const u = this.session.user();
      if (u?.id == null) return;
      this.pendingLoading.set(true);
      this.signatures.pending(u.id).subscribe({
        next: l => { this.pending.set(l); this.pendingLoading.set(false); },
        error: () => this.pendingLoading.set(false),
      });
    });
  }

  private capitalize(s: string): string {
    return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
  }
}
