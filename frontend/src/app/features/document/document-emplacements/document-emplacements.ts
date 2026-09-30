import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DocumentService } from '../document.service';
import { DocumentItem, Ref } from '../document.model';
import { WorkspaceService } from '../../workspace/workspace.service';
import { NotifyService } from '../../../core/notify.service';

/**
 * Emplacements d'un document (§4.3.5, §4.3.6, §12.4, §12.5 ; ANO-F-014) :
 * dossier principal et rattachements.
 *
 * <p>La fiche montrait les rattachements sans pouvoir en ajouter ni en
 * retirer, et un document ne se déplaçait qu'en changeant de type. Ce bloc
 * sert les trois opérations de l'API : déplacer vers un dossier choisi
 * (PATCH /emplacement : Déplacer sur le document, Déposer sur la
 * destination), rattacher à un dossier de plus (POST /rattachements) et
 * retirer un rattachement (DELETE /rattachements/{noeud}), ces deux derniers
 * avec Modifier. Les dossiers proposés sont ceux que l'appelant voit ; le
 * serveur revérifie chaque droit.</p>
 */
@Component({
  selector: 'app-document-emplacements',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <div class="card emplacements">
      <div class="bloc-tete"><mat-icon svgIcon="folder"></mat-icon><span>Emplacements</span></div>
      <dl class="ligne">
        <dt>Dossier principal</dt>
        <dd class="principal">{{ doc().workspace?.label || '—' }}</dd>
      </dl>
      @if (peutDeplacer()) {
        <div class="action">
          <select name="destination" aria-label="Dossier de destination" [(ngModel)]="destination">
            <option [ngValue]="null">Déplacer vers…</option>
            @for (d of dossiersHors(doc().workspace?.id); track d.id) { <option [ngValue]="d.id">{{ d.name }}</option> }
          </select>
          <button mat-stroked-button type="button" class="deplacer" [disabled]="!destination || enCours()" (click)="deplacer()">
            <mat-icon svgIcon="folder-open"></mat-icon> Déplacer
          </button>
        </div>
      }

      <dl class="ligne"><dt>Rattaché aussi à</dt><dd></dd></dl>
      @if (doc().rattachements?.length) {
        <ul class="liste">
          @for (r of doc().rattachements; track r.id) {
            <li>
              <span>{{ r.label }}</span>
              @if (peutRattacher()) {
                <button mat-icon-button type="button" class="detacher" (click)="detacher(r)" [disabled]="enCours()"
                        matTooltip="Retirer ce rattachement (le document reste dans ses autres emplacements)"
                        [attr.aria-label]="'Retirer le rattachement ' + r.label">
                  <mat-icon svgIcon="delete"></mat-icon>
                </button>
              }
            </li>
          }
        </ul>
      } @else {
        <p class="etat">Aucun rattachement.</p>
      }
      @if (peutRattacher()) {
        <div class="action">
          <select name="rattachement" aria-label="Dossier à rattacher" [(ngModel)]="cibleRattachement">
            <option [ngValue]="null">Rattacher à…</option>
            @for (d of dossiersRattachables(); track d.id) { <option [ngValue]="d.id">{{ d.name }}</option> }
          </select>
          <button mat-stroked-button type="button" class="rattacher" [disabled]="!cibleRattachement || enCours()" (click)="rattacher()">
            <mat-icon svgIcon="add"></mat-icon> Rattacher
          </button>
        </div>
      }
    </div>`,
  styles: [`
    .ligne { margin: 0 0 6px; display: flex; gap: 8px; font-size: 13px; }
    .ligne dt { color: var(--ink-soft); }
    .ligne dd { margin: 0; font-weight: 600; color: var(--ink); }
    .liste { list-style: none; margin: 0 0 8px; padding: 0; }
    .liste li { display: flex; align-items: center; justify-content: space-between; font-size: 13px;
                border-bottom: 1px solid var(--line, #eceff4); }
    .etat { font-size: 13px; color: var(--ink-soft); margin: 0 0 8px; }
    .action { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
    .action select { flex: 1 1 200px; height: 36px; padding: 0 8px; border: 1px solid var(--line, #d9dee7);
                     border-radius: 8px; background: var(--surface-0, #fff); color: var(--ink); font-size: 14px; }
  `],
})
export class DocumentEmplacements implements OnInit {
  private documents = inject(DocumentService);
  private espaces = inject(WorkspaceService);
  private notify = inject(NotifyService);

  readonly doc = input.required<DocumentItem>();
  /** Document verrouillé ou archivé : aucune écriture (le serveur refuse en 409). */
  readonly lectureSeule = input(false);
  /** La fiche à jour après une opération : l'appelant la recharge. */
  readonly modifie = output<void>();

  dossiers = signal<{ id: string; name: string }[]>([]);
  enCours = signal(false);
  destination: string | null = null;
  cibleRattachement: string | null = null;

  ngOnInit(): void {
    this.espaces.forSelect().subscribe({ next: l => this.dossiers.set(l), error: () => this.dossiers.set([]) });
  }

  private peut(p: string): boolean { return this.doc().permissions?.includes(p) ?? false; }

  peutDeplacer(): boolean { return !this.lectureSeule() && !this.doc().supprime && this.peut('DEPLACER'); }
  peutRattacher(): boolean { return !this.lectureSeule() && !this.doc().supprime && this.peut('MODIFIER'); }

  dossiersHors(id: string | undefined): { id: string; name: string }[] {
    return this.dossiers().filter(d => d.id !== id);
  }

  /** Ni le dossier principal ni un dossier déjà rattaché. */
  dossiersRattachables(): { id: string; name: string }[] {
    const exclus = new Set([this.doc().workspace?.id, ...(this.doc().rattachements ?? []).map(r => r.id)]);
    return this.dossiers().filter(d => !exclus.has(d.id));
  }

  deplacer(): void {
    const cible = this.destination;
    if (!cible || this.enCours()) return;
    this.executer(this.documents.deplacer(this.doc().id, cible), 'Document déplacé.', 'Déplacement impossible.',
      () => this.destination = null);
  }

  rattacher(): void {
    const cible = this.cibleRattachement;
    if (!cible || this.enCours()) return;
    this.executer(this.documents.rattacher(this.doc().id, cible), 'Document rattaché au dossier.',
      'Rattachement impossible.', () => this.cibleRattachement = null);
  }

  detacher(r: Ref): void {
    if (this.enCours()) return;
    this.executer(this.documents.detacher(this.doc().id, r.id), `Rattachement à « ${r.label} » retiré.`,
      'Retrait impossible.');
  }

  private executer(appel: Observable<unknown>, succes: string, echec: string, apres?: () => void): void {
    this.enCours.set(true);
    appel.subscribe({
      next: () => {
        this.enCours.set(false);
        apres?.();
        this.notify.success(succes);
        this.modifie.emit();
      },
      error: e => {
        this.enCours.set(false);
        this.notify.error(e?.error?.detail ?? e?.error?.message ?? echec);
      },
    });
  }
}
