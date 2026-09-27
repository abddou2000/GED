import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { API_BASE } from '../../../core/api';
import { ConfirmService } from '../../../core/confirm.service';
import { NotifyService } from '../../../core/notify.service';

/** Identité listée par `GET /admin/utilisateurs`. */
interface IdentiteAdmin {
  id: string;
  identifiant: string;
  fullName: string;
  email: string | null;
  direction: string | null;
  roles: string[];
  derniereConnexion: string | null;
  sessionsOuvertes: number;
}

/**
 * Administration des sessions (risque R26, décision client D1).
 *
 * <p>La GED ne relit pas l'état des comptes dans l'annuaire : une personne
 * désactivée garde sa session jusqu'à sa borne absolue. L'Administrateur la
 * coupe ici, immédiatement — le serveur refuse le jeton d'accès dès la requête
 * suivante.
 */
@Component({
  selector: 'app-sessions-admin',
  imports: [MatIconModule, DatePipe],
  template: `
    <div class="page">
      <header class="entete">
        <h1>Sessions</h1>
        <p>Identités de l'annuaire connues de la GED et sessions ouvertes. Révoquer ferme
           immédiatement toutes les sessions de la personne (déconnexion forcée).</p>
      </header>

      @if (chargement()) {
        <p>Chargement…</p>
      } @else {
        <table class="table-sessions">
          <thead>
            <tr>
              <th>Identifiant</th><th>Nom</th><th>Direction</th><th>Rôles</th>
              <th>Dernière connexion</th><th>Sessions ouvertes</th><th></th>
            </tr>
          </thead>
          <tbody>
            @for (u of identites(); track u.id) {
              <tr>
                <td>{{ u.identifiant }}</td>
                <td>{{ u.fullName }}</td>
                <td>{{ u.direction || '—' }}</td>
                <td>{{ u.roles.length ? u.roles.join(', ') : 'aucun' }}</td>
                <td>{{ u.derniereConnexion ? (u.derniereConnexion | date:'dd/MM/yyyy HH:mm') : '—' }}</td>
                <td>{{ u.sessionsOuvertes }}</td>
                <td>
                  <button type="button" class="revoquer" [disabled]="u.sessionsOuvertes === 0"
                          (click)="revoquer(u)">Révoquer les sessions</button>
                </td>
              </tr>
            } @empty {
              <tr><td colspan="7">Aucune identité : personne ne s'est encore connecté.</td></tr>
            }
          </tbody>
        </table>
      }
    </div>
  `,
  styles: [`
    .entete h1 { margin: 0 0 6px; font-size: 22px; }
    .entete p { margin: 0 0 18px; color: var(--ink-soft, #555); }
    .table-sessions { width: 100%; border-collapse: collapse; font-size: 14px; }
    .table-sessions th, .table-sessions td { padding: 8px 10px; border-bottom: 1px solid var(--line, #e3e6ea); text-align: left; }
    .revoquer { padding: 6px 10px; border-radius: 6px; border: 1px solid var(--danger, #b3261e);
      background: transparent; color: var(--danger, #b3261e); cursor: pointer; }
    .revoquer:disabled { opacity: .4; cursor: default; }
  `],
})
export class SessionsAdmin implements OnInit {
  private http = inject(HttpClient);
  private confirm = inject(ConfirmService);
  private notify = inject(NotifyService);

  protected identites = signal<IdentiteAdmin[]>([]);
  protected chargement = signal(true);

  ngOnInit(): void {
    this.charger();
  }

  private charger(): void {
    this.http.get<IdentiteAdmin[]>(`${API_BASE}/admin/utilisateurs`).subscribe({
      next: l => { this.identites.set(l); this.chargement.set(false); },
      error: () => { this.chargement.set(false); this.notify.error('Liste des identités indisponible.'); },
    });
  }

  protected revoquer(u: IdentiteAdmin): void {
    this.confirm.ask({
      title: 'Révoquer les sessions',
      message: `Fermer immédiatement les ${u.sessionsOuvertes} session(s) de ${u.fullName} (${u.identifiant}) ?`,
      confirmLabel: 'Révoquer',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.http.delete<{ sessionsFermees: number }>(`${API_BASE}/admin/utilisateurs/${u.id}/sessions`).subscribe({
        next: r => { this.notify.success(`${r.sessionsFermees} session(s) fermée(s).`); this.charger(); },
        error: () => this.notify.error('Révocation impossible.'),
      });
    });
  }
}
