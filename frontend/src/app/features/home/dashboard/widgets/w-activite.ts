import { Component, computed, effect, inject, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { Encart } from './encart';
import { SessionService } from '../../../../core/session.service';
import { CircuitService } from '../../../workflow/circuit.service';
import { DecisionRendue } from '../../../workflow/circuit.model';
import { dateCourte } from '../dates';

/**
 * Ce que la personne connectée a décidé récemment : validations et refus (§12.8).
 *
 * <p>C'est bien SON activité, pas celle de l'application : l'API d'historique
 * est déjà filtrée sur l'approbateur. Annoncer « activité récente » en montrant
 * les actions de toute la maison serait un contresens.</p>
 */
@Component({
  selector: 'app-w-activite',
  imports: [MatIconModule, Encart],
  template: `
    <app-encart titre="Mon activité récente" icone="sign" lien="/mes-workflow"
                libelleLien="Historique" [chargement]="chargement()">
      @if (lignes().length) {
        <ul class="liste">
          @for (s of lignes(); track s.id) {
            <li class="ligne">
              <span class="pastille" [class]="s.decision === 'VALIDE' ? 'd-vert' : 'd-cramoisi'">
                <mat-icon [svgIcon]="s.decision === 'VALIDE' ? 'sign' : 'reject'"></mat-icon>
              </span>
              <span class="txt">
                <span class="nom">{{ s.document }}</span>
                <span class="sous">
                  <span class="meta">{{ s.decision === 'VALIDE' ? 'Validé' : 'Refusé' }} · {{ s.libelle }}</span>
                </span>
              </span>
              <span class="quand">{{ quand(s.le) }}</span>
            </li>
          }
        </ul>
      } @else {
        <div class="vide">
          <span class="vide-ic"><mat-icon svgIcon="clock"></mat-icon></span>
          <span class="vide-titre">Rien à afficher</span>
          <span class="vide-sous">Vos décisions apparaîtront ici.</span>
        </div>
      }
    </app-encart>`,
  styleUrl: './w-activite.scss',
})
export class WActivite {
  private session = inject(SessionService);
  private circuits = inject(CircuitService);

  protected readonly chargement = signal(true);
  private readonly historique = signal<DecisionRendue[]>([]);
  protected readonly quand = dateCourte;

  /* L'historique arrive dans l'ordre du serveur ; on le retrie sur la date de
     décision, la seule qui fasse sens ici, et on ne garde que les décisions
     prises — une ligne encore en attente n'est pas de l'activité passée. */
  protected readonly lignes = computed(() =>
    this.historique()
      .filter(s => s.decision !== 'ANNULEE')
      .sort((a, b) => b.le.localeCompare(a.le))
      .slice(0, 6));

  constructor() {
    effect(() => {
      if (this.session.user()?.id == null) return;
      this.chargement.set(true);
      this.circuits.historique().subscribe({
        next: l => { this.historique.set(l); this.chargement.set(false); },
        error: () => this.chargement.set(false),
      });
    });
  }
}
