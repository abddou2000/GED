import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { AccessGroupService } from '../access-group.service';
import { AccessGroup } from '../access-group.model';
import { AccessGroupForm } from '../access-group-form/access-group-form';
import { NotifyService } from '../../../core/notify.service';
import { teinteAvatar, encreAvatar } from '../../../core/avatar';

/**
 * Fiche d'un groupe d'accès : ses membres et les espaces qu'il couvre.
 *
 * <p>Le groupe ne porte aucune autorisation — l'application n'a qu'un utilisateur,
 * l'administrateur, à qui tout est déjà ouvert. La fiche sert donc uniquement à
 * lire, d'un coup d'œil, qui est rattaché à quoi.
 */
@Component({
  selector: 'app-access-group-detail',
  imports: [
    RouterLink, MatButtonModule, MatIconModule, MatTooltipModule,
    MatDialogModule,
  ],
  templateUrl: './access-group-detail.html',
  styleUrl: './access-group-detail.scss',
})
export class AccessGroupDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private service = inject(AccessGroupService);
  private dialog = inject(MatDialog);
  private notify = inject(NotifyService);

  id = signal<string | null>(null);
  groupe = signal<AccessGroup | null>(null);
  chargement = signal(true);
  introuvable = signal(false);

  ngOnInit(): void {
    this.route.paramMap.subscribe(p => {
      const id = p.get('id');
      if (!id) { this.introuvable.set(true); this.chargement.set(false); return; }
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
      next: g => {
        this.groupe.set(g);
        this.chargement.set(false);
      },
      error: () => { this.chargement.set(false); this.introuvable.set(true); },
    });
  }

  modifier(): void {
    const g = this.groupe();
    if (!g) return;
    this.dialog.open(AccessGroupForm, {
      data: { group: g }, width: '620px', maxWidth: '95vw', autoFocus: false,
    }).afterClosed().subscribe(ok => {
      if (!ok) return;
      this.charger();
      this.notify.success("Groupe d'accès modifié.");
    });
  }

  initials(fullName: string): string {
    const parts = (fullName || '').trim().split(/\s+/);
    const a = parts[0]?.[0] ?? '';
    const b = parts.length > 1 ? parts[parts.length - 1][0] : '';
    return (a + b).toUpperCase() || '?';
  }
  /* Teinte et encre viennent de `core/avatar` : la palette etait recopiee dans
     chaque ecran, et corriger l'un laissait les autres derriere. */
  readonly avatarColor = teinteAvatar;
  readonly avatarInk = encreAvatar;
}
