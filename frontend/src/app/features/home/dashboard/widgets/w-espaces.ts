import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { Encart } from './encart';
import { WorkspaceService } from '../../../workspace/workspace.service';
import { WorkSpace } from '../../../workspace/workspace.model';

/** Les espaces de travail les plus récents, avec leur état. */
@Component({
  selector: 'app-w-espaces',
  imports: [RouterLink, MatIconModule, Encart],
  template: `
    <app-encart titre="Espaces de travail" icone="nav-workspaces"
                lien="/espaces-de-travail" [chargement]="chargement()">
      @if (espaces().length) {
        <ul class="liste">
          @for (e of espaces(); track e.id) {
            <li>
              <a class="ligne" [routerLink]="['/espaces-de-travail', e.id]">
                <span class="pastille d-marine"><mat-icon svgIcon="folder"></mat-icon></span>
                <span class="txt">
                  <span class="nom">{{ e.name }}</span>
                  <span class="sous">
                    <span class="meta">{{ e.code }}</span>
                    @if (e.childrenCount) {
                      <span class="meta">· {{ e.childrenCount }} sous-dossier{{ e.childrenCount > 1 ? 's' : '' }}</span>
                    }
                  </span>
                </span>
                <!-- Seul l'écart à la norme se signale : marquer « Actif » sur
                     la quasi-totalité des lignes ne distingue rien. -->
                @if (e.status !== 'ACTIF') {
                  <span class="etat" [class.arch]="e.status === 'ARCHIVE'">
                    {{ e.status === 'ARCHIVE' ? 'Archivé' : 'Inactif' }}
                  </span>
                }
              </a>
            </li>
          }
        </ul>
      } @else {
        <div class="vide">
          <span class="vide-ic"><mat-icon svgIcon="folder"></mat-icon></span>
          <span class="vide-titre">Aucun espace</span>
          <span class="vide-sous">Créez un espace pour ranger vos documents.</span>
        </div>
      }
    </app-encart>`,
  styleUrl: './w-espaces.scss',
})
export class WEspaces implements OnInit {
  private service = inject(WorkspaceService);

  protected readonly chargement = signal(true);
  protected readonly espaces = signal<WorkSpace[]>([]);

  ngOnInit(): void {
    this.service.list(0, 6, '', 'createdAt', 'desc').subscribe({
      next: p => { this.espaces.set(p.content); this.chargement.set(false); },
      error: () => this.chargement.set(false),
    });
  }
}
