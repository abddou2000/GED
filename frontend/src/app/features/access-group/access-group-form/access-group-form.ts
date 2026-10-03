import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { AccessGroupService } from '../access-group.service';
import { AccessGroup, AccessGroupRequest } from '../access-group.model';
import { WorkspaceService } from '../../workspace/workspace.service';
import { SelectOption } from '../../workspace/workspace.model';
import { EmployeService, Employe } from '../../../core/employe.service';

interface DialogData {
  group: AccessGroup | null;
}

/** Personne proposée dans le sélecteur des membres. */
export interface OptionMembre {
  /** Identifiant de la fiche employé : c'est lui que porte `userIds`. */
  id: string;
  nom: string;
  /** Pas d'identité GED : membre en attente de première connexion (T-025). */
  enAttente: boolean;
}

/** Libellé commun à l'écran pour un membre sans identité GED. */
export const LIBELLE_ATTENTE = 'en attente de première connexion';

/**
 * Formulaire créer / éditer un groupe d'accès — 2 sections : Identification,
 * Utilisateurs et espaces de travail.
 *
 * <p>Membres (T-025, ANO-F-029) : le sélecteur propose toutes les fiches
 * employé, y compris celles des personnes qui ne se sont encore jamais
 * connectées ; l'API en fait des membres en attente, convertis à la première
 * connexion. Un `mat-select` multiple ne renvoie que les valeurs qui ont une
 * option : un membre absent des options était donc effacé dès que
 * l'Administrateur cochait ou décochait quelqu'un. Chaque membre actuel du
 * groupe a donc toujours son option, même si la liste des fiches ne le
 * contient pas.
 */
@Component({
  selector: 'app-access-group-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule,
  ],
  templateUrl: './access-group-form.html',
  styleUrl: './access-group-form.scss',
})
export class AccessGroupForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(AccessGroupService);
  private workspaceService = inject(WorkspaceService);
  private employeService = inject(EmployeService);
  private ref = inject(MatDialogRef<AccessGroupForm>);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  readonly libelleAttente = LIBELLE_ATTENTE;
  workspaces = signal<SelectOption[]>([]);
  employes = signal<Employe[]>([]);
  loading = signal(false);
  serverError = signal<string | null>(null);
  /** Membres sélectionnés (identifiants de fiche), suivis pour la liste des membres en attente. */
  private selection = signal<string[]>([]);

  /** Fiches employé, puis membres actuels du groupe qu'elles ne contiendraient pas. */
  readonly options = computed<OptionMembre[]>(() => {
    const liste: OptionMembre[] = this.employes()
      .map(e => ({ id: e.id, nom: e.fullName, enAttente: !e.utilisateurId }));
    const connus = new Set(liste.map(o => o.id));
    const g = this.data.group;
    const attente = new Set(g?.pendingUserIds ?? []);
    for (const u of g?.users ?? []) {
      if (!connus.has(u.id)) liste.push({ id: u.id, nom: u.label, enAttente: attente.has(u.id) });
    }
    return liste.sort((a, b) => a.nom.localeCompare(b.nom, 'fr'));
  });

  /** Membres en attente actuellement sélectionnés, avec leur nom. */
  readonly membresEnAttente = computed<OptionMembre[]>(() => {
    const choisis = new Set(this.selection());
    return this.options().filter(o => o.enAttente && choisis.has(o.id));
  });

  form: FormGroup = this.fb.group({
    code: ['', Validators.required],
    name: ['', Validators.required],
    workspaceIds: [[] as string[]],
    userIds: [[] as string[]],
  });

  get isEdit(): boolean {
    return !!this.data.group;
  }

  ngOnInit(): void {
    this.form.get('userIds')!.valueChanges.subscribe(v => this.selection.set(v ?? []));
    this.workspaceService.forSelect().subscribe(l => this.workspaces.set(l));
    this.employeService.tous().subscribe(l => this.employes.set(l));

    if (this.data.group) {
      const g = this.data.group;
      this.form.patchValue({
        code: g.code, name: g.name,
        workspaceIds: g.workspaces.map(w => w.id),
        userIds: g.users.map(u => u.id),
      });
    }
  }

  /** Retire un membre (en attente) du groupe ; pris en compte à l'enregistrement. */
  retirer(id: string): void {
    const ctl = this.form.get('userIds')!;
    ctl.setValue((ctl.value as string[] ?? []).filter(x => x !== id));
    ctl.markAsDirty();
  }

  submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.value;
    const body: AccessGroupRequest = {
      code: v.code, name: v.name,
      workspaceIds: v.workspaceIds ?? [], userIds: v.userIds ?? [],
    };
    this.loading.set(true);
    const call = this.data.group
      ? this.service.update(this.data.group.id, body)
      : this.service.create(body);
    call.subscribe({
      // On renvoie le groupe enregistré, et non un simple booléen : l'appelant
      // a besoin de son identifiant pour ouvrir la fiche après une création.
      next: g => { this.loading.set(false); this.ref.close(g); },
      error: err => {
        this.loading.set(false);
        this.serverError.set(err?.error?.message ?? 'Une erreur est survenue.');
      },
    });
  }

  cancel(): void {
    this.ref.close(false);
  }
}
