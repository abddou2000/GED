import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { of } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { TypeDocumentService } from '../type-document.service';
import { TypeDocument } from '../type-document.model';
import { TypeDocumentForm } from '../type-document-form/type-document-form';
import { NotifyService } from '../../../core/notify.service';
import { AuthService } from '../../../core/auth.service';
import { WorkflowService } from '../../workflow/workflow.service';
import { CircuitService } from '../../workflow/circuit.service';
import { Workflow } from '../../workflow/workflow.model';
import { ConfirmService } from '../../../core/confirm.service';
import { ModulesService } from '../../../core/modules.service';

/**
 * Fiche d'un type de document — reprend l'en-tête de l'application d'origine :
 * nom, puis cartouches Code, Espace de travail, Types autorisés, Description.
 *
 * <p>L'original prévoit un onglet « Overview » sous cet en-tête, mais son
 * contenu y est un titre vide : rien à reproduire de ce côté.
 */
@Component({
  selector: 'app-type-document-detail',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatDialogModule],
  templateUrl: './type-document-detail.html',
  styleUrl: './type-document-detail.scss',
})
export class TypeDocumentDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private service = inject(TypeDocumentService);
  private dialog = inject(MatDialog);
  private notify = inject(NotifyService);
  private auth = inject(AuthService);
  private reglesApi = inject(WorkflowService);
  private circuits = inject(CircuitService);
  private confirm = inject(ConfirmService);
  private modules = inject(ModulesService);

  /** La re-typologisation relève du module « cycle de vie » (T-088). */
  retypageDisponible = () => this.modules.actif('cycledevie');
  /** La règle de workflow du type relève du module workflow (T-088). */
  workflowActif = () => this.modules.actif('workflow');

  regles = signal<Workflow[]>([]);
  /** Rattacher une règle relève de la gestion des référentiels. */
  gereReferentiels = () => this.auth.peut('GERER_REFERENTIELS');

  id = signal<string | null>(null);
  type = signal<TypeDocument | null>(null);
  chargement = signal(true);
  introuvable = signal(false);

  ngOnInit(): void {
    // Module workflow désactivé : ses routes répondent 404, rien à demander (T-088).
    this.modules.charger().subscribe(() => {
      if (this.gereReferentiels() && this.workflowActif()) {
        this.reglesApi.list(0, 200).subscribe({ next: p => this.regles.set(p.content), error: () => this.regles.set([]) });
      }
    });
    this.route.paramMap.subscribe(p => {
      const id = p.get('id');
      if (!id) {
        this.introuvable.set(true);
        this.chargement.set(false);
        return;
      }
      this.id.set(id);
      this.charger();
    });
  }

  charger(): void {
    const id = this.id();
    if (id == null) return;
    this.chargement.set(true);
    this.introuvable.set(false);
    this.service.get(id).subscribe({
      next: t => { this.type.set(t); this.chargement.set(false); },
      error: () => { this.chargement.set(false); this.introuvable.set(true); },
    });
  }

  /** Rattache (ou détache) la règle du type ; les circuits ouverts ne changent pas. */
  changerRegle(regleId: string): void {
    const t = this.type();
    if (!t) return;
    this.circuits.rattacherType(t.id, regleId || null).subscribe({
      next: () => { this.notify.success('Règle de workflow du type enregistrée (dépôts futurs).'); this.charger(); },
      error: err => this.notify.error(err?.error?.message ?? 'Rattachement impossible.'),
    });
  }

  modifier(): void {
    const t = this.type();
    if (!t) return;
    this.dialog.open(TypeDocumentForm, {
      data: { type: t }, width: '600px', maxWidth: '95vw', autoFocus: false,
    }).afterClosed().subscribe(ok => {
      if (!ok) return;
      this.charger();
      this.notify.success('Type de document modifié.');
    });
  }

  /**
   * Désactive ou réactive le type (§12.7, ANO-F-015) : un type utilisé ne se
   * supprime pas, il se désactive ; il n'accepte alors plus de dépôt, les
   * documents existants restent consultables.
   */
  basculerActif(): void {
    const t = this.type();
    if (!t) return;
    const actif = t.actif === false;
    const confirmation = actif ? of(true) : this.confirm.ask({
      title: 'Désactiver ce type',
      message: `« ${t.typeDeDocument} » n'acceptera plus de dépôt. Les documents existants restent consultables.`,
      confirmLabel: 'Désactiver',
      danger: true,
    });
    confirmation.subscribe(ok => {
      if (!ok) return;
      this.service.activer(t.id, actif).subscribe({
        next: maj => {
          this.type.set({ ...t, ...maj });
          this.notify.success(actif ? 'Type de document réactivé.' : 'Type de document désactivé.');
        },
        error: err => this.notify.error(err?.error?.message ?? 'Changement de statut impossible.'),
      });
    });
  }
}
