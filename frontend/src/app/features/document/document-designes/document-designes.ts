import { Component, OnChanges, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DocumentService } from '../document.service';
import { Ref } from '../document.model';
import { EmployeService, Personne } from '../../../core/employe.service';
import { NotifyService } from '../../../core/notify.service';

/**
 * Personnes désignées d'un document Confidentiel (§4.4.5, §12.3 ; ANO-F-013).
 *
 * <p>Le niveau se choisissait à l'écran, mais la liste des personnes qui ont
 * le droit de lire le document ne se tenait que par l'API
 * (GET/POST/DELETE /documents/{id}/designes). Ce bloc la montre et permet
 * d'ajouter ou de retirer une personne. Le serveur décide : il accepte
 * l'Administrateur ou toute personne qui voit le document, et refuse sur un
 * document verrouillé ou archivé ; l'écran masque alors les actions.</p>
 */
@Component({
  selector: 'app-document-designes',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <div class="card designes">
      <div class="bloc-tete">
        <mat-icon svgIcon="nav-groups"></mat-icon><span>Personnes autorisées (Confidentiel)</span>
        <span class="cpt">{{ designes().length }}</span>
      </div>
      <p class="aide">Seules ces personnes, et les porteurs de la permission « Voir confidentiel », peuvent lire ce document.</p>
      @if (designes().length) {
        <ul class="liste">
          @for (p of designes(); track p.id) {
            <li>
              <span class="nom">{{ p.label }}</span>
              @if (!lectureSeule()) {
                <button mat-icon-button type="button" class="retirer" (click)="retirer(p)"
                        [disabled]="enCours()" matTooltip="Retirer cette personne" [attr.aria-label]="'Retirer ' + p.label">
                  <mat-icon svgIcon="delete"></mat-icon>
                </button>
              }
            </li>
          }
        </ul>
      } @else {
        <p class="etat">Aucune personne désignée.</p>
      }
      @if (!lectureSeule()) {
        <div class="ajout">
          <select name="personne" aria-label="Personne à désigner" [(ngModel)]="choix">
            <option [ngValue]="null">Choisir une personne…</option>
            @for (p of candidats(); track p.utilisateurId) { <option [ngValue]="p.utilisateurId">{{ p.nom }}</option> }
          </select>
          <button mat-stroked-button type="button" class="designer" [disabled]="!choix || enCours()" (click)="designer()">
            <mat-icon svgIcon="add"></mat-icon> Désigner
          </button>
        </div>
      }
    </div>`,
  styles: [`
    .designes .aide { margin: 0 0 8px; font-size: 12px; color: var(--ink-soft); }
    .etat { font-size: 13px; color: var(--ink-soft); margin: 0 0 8px; }
    .liste { list-style: none; margin: 0 0 8px; padding: 0; }
    .liste li { display: flex; align-items: center; justify-content: space-between; padding: 2px 0;
                border-bottom: 1px solid var(--line, #eceff4); }
    .ajout { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .ajout select { flex: 1 1 200px; height: 36px; padding: 0 8px; border: 1px solid var(--line, #d9dee7);
                    border-radius: 8px; background: var(--surface-0, #fff); color: var(--ink); font-size: 14px; }
  `],
})
export class DocumentDesignes implements OnChanges {
  private documents = inject(DocumentService);
  private employes = inject(EmployeService);
  private notify = inject(NotifyService);

  readonly documentId = input.required<string>();
  /** Document verrouillé ou archivé : lecture de la liste seulement. */
  readonly lectureSeule = input(false);

  designes = signal<Ref[]>([]);
  personnes = signal<Personne[]>([]);
  enCours = signal(false);
  choix: string | null = null;
  private personnesChargees = false;

  /** Personnes pas encore désignées. */
  candidats(): Personne[] {
    const deja = new Set(this.designes().map(d => d.id));
    return this.personnes().filter(p => !deja.has(p.utilisateurId));
  }

  ngOnChanges(): void {
    this.documents.designes(this.documentId()).subscribe({
      next: l => this.designes.set(l),
      error: () => this.designes.set([]),
    });
    if (!this.personnesChargees) {
      this.personnesChargees = true;
      this.employes.personnes().subscribe(p => this.personnes.set(p));
    }
  }

  designer(): void {
    const utilisateurId = this.choix;
    if (!utilisateurId || this.enCours()) return;
    this.enCours.set(true);
    this.documents.designer(this.documentId(), utilisateurId).subscribe({
      next: l => {
        this.designes.set(l);
        this.choix = null;
        this.enCours.set(false);
        this.notify.success('Personne désignée : elle peut lire ce document.');
      },
      error: e => {
        this.enCours.set(false);
        this.notify.error(e?.error?.detail ?? e?.error?.message ?? 'Désignation impossible.');
      },
    });
  }

  retirer(p: Ref): void {
    if (this.enCours()) return;
    this.enCours.set(true);
    this.documents.retirerDesignation(this.documentId(), p.id).subscribe({
      next: () => {
        this.designes.update(l => l.filter(x => x.id !== p.id));
        this.enCours.set(false);
        this.notify.success(`${p.label} n'est plus désigné(e).`);
      },
      error: e => {
        this.enCours.set(false);
        this.notify.error(e?.error?.detail ?? e?.error?.message ?? 'Retrait impossible.');
      },
    });
  }
}
