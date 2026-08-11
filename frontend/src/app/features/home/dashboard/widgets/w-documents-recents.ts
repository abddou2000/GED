import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { Encart } from './encart';
import { DocumentService } from '../../../document/document.service';
import { DocumentItem } from '../../../document/document.model';
import { dateCourte } from '../dates';

/** Les derniers documents déposés, du plus récent au plus ancien. */
@Component({
  selector: 'app-w-documents-recents',
  imports: [RouterLink, MatIconModule, Encart],
  template: `
    <app-encart titre="Documents récents" icone="clock" lien="/televerser"
                [chargement]="chargement()">
      @if (documents().length) {
        <ul class="liste">
          @for (d of documents(); track d.id) {
            <li>
              <a class="ligne" [routerLink]="['/televerser', d.id]">
                <span class="pastille d-marine"><mat-icon svgIcon="nav-type"></mat-icon></span>
                <span class="txt">
                  <span class="nom">{{ d.name }}</span>
                  <span class="sous">
                    <span class="meta">{{ d.chemin || d.workspace?.label || '—' }}</span>
                    @if (d.sizeLabel) { <span class="meta">· {{ d.sizeLabel }}</span> }
                  </span>
                </span>
                <span class="quand">{{ quand(d.createdAt) }}</span>
              </a>
            </li>
          }
        </ul>
      } @else {
        <div class="vide">
          <span class="vide-ic"><mat-icon svgIcon="inbox"></mat-icon></span>
          <span class="vide-titre">Aucun document</span>
          <span class="vide-sous">Rien n'a encore été déposé.</span>
        </div>
      }
    </app-encart>`,
  styleUrl: './w-documents-recents.scss',
})
export class WDocumentsRecents implements OnInit {
  private service = inject(DocumentService);

  protected readonly chargement = signal(true);
  protected readonly documents = signal<DocumentItem[]>([]);
  protected readonly quand = dateCourte;

  ngOnInit(): void {
    /* Le tri est demandé au SERVEUR. Trier une page déjà découpée donnerait
       « les six premiers documents, remis dans l'ordre » — pas « les six
       derniers déposés ». */
    this.service.list(0, 6, '', undefined, 'createdAt', 'desc').subscribe({
      next: p => { this.documents.set(p.content); this.chargement.set(false); },
      error: () => this.chargement.set(false),
    });
  }
}
