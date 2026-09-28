import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { Encart } from './encart';
import { SessionService } from '../../../../core/session.service';
import { CircuitService } from '../../../workflow/circuit.service';
import { ATraiter } from '../../../workflow/circuit.model';

/** Les documents qui attendent la décision de la personne connectée (§12.8). */
@Component({
  selector: 'app-w-a-valider',
  imports: [RouterLink, MatIconModule, Encart],
  template: `
    <app-encart titre="À valider" icone="nav-mesworkflow" lien="/mes-workflow"
                [compteur]="total() || null" [chargement]="chargement()">
      @if (lignes().length) {
        <ul class="liste">
          @for (s of lignes(); track s.validateurId) {
            <li class="ligne">
              <span class="pastille d-ambre"><mat-icon svgIcon="nav-type"></mat-icon></span>
              <span class="txt">
                <span class="nom">{{ s.document }}</span>
                <span class="sous">
                  <span class="meta">{{ s.libelle }}</span>
                  @if (s.initiateur) { <span class="meta">{{ s.initiateur }}</span> }
                </span>
              </span>
              <a class="action" routerLink="/mes-workflow">
                Traiter <mat-icon svgIcon="chevron-right"></mat-icon>
              </a>
            </li>
          }
        </ul>
      } @else {
        <div class="vide">
          <span class="vide-ic ok"><mat-icon svgIcon="nav-mesworkflow"></mat-icon></span>
          <span class="vide-titre">Vous êtes à jour</span>
          <span class="vide-sous">Aucun document n'attend votre décision.</span>
        </div>
      }
    </app-encart>`,
  styleUrl: './w-a-valider.scss',
})
export class WAValider {
  private session = inject(SessionService);
  private circuits = inject(CircuitService);

  protected readonly chargement = signal(true);
  private readonly attente = signal<ATraiter[]>([]);

  /* Le compteur porte le TOTAL, pas le nombre de lignes montrées : c'est le
     chiffre qui a un sens pour la personne, et « Tout voir » mène au reste.
     Un compteur calé sur l'aperçu afficherait éternellement « 6 ». */
  protected readonly total = computed(() => this.attente().length);
  protected readonly lignes = computed(() => this.attente().slice(0, 6));

  constructor() {
    effect(() => {
      // L'identité porte l'identifiant d'employé : sans elle, l'API ne sait pas
      // de quelles signatures on parle.
      if (this.session.user()?.id == null) return;
      this.chargement.set(true);
      this.circuits.aTraiter().subscribe({
        next: l => { this.attente.set(l); this.chargement.set(false); },
        error: () => this.chargement.set(false),
      });
    });
  }
}
