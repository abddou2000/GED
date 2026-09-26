import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { TypeDocumentService } from '../type-document.service';
import { TypeDocument } from '../type-document.model';
import { TypeDocumentForm } from '../type-document-form/type-document-form';
import { NotifyService } from '../../../core/notify.service';

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

  id = signal<string | null>(null);
  type = signal<TypeDocument | null>(null);
  chargement = signal(true);
  introuvable = signal(false);

  ngOnInit(): void {
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
}
