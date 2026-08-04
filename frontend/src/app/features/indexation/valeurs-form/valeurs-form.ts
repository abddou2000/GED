import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { forkJoin } from 'rxjs';
import { IndexationService } from '../indexation.service';
import { Critere, Resultat } from '../indexation.model';
import { NotifyService } from '../../../core/notify.service';

interface DialogData {
  document: Resultat;
}

/**
 * Saisie des valeurs d'index d'un document.
 * Les champs proposés viennent du plan d'indexation attaché au type du
 * document — ni ce composant ni son gabarit ne connaissent les index à l'avance.
 */
@Component({
  selector: 'app-valeurs-form',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatButtonModule,
  ],
  templateUrl: './valeurs-form.html',
  styleUrl: './valeurs-form.scss',
})
export class ValeursForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(IndexationService);
  private ref = inject(MatDialogRef<ValeursForm>);
  private notify = inject(NotifyService);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  champs = signal<Critere[]>([]);
  chargement = signal(true);
  loading = signal(false);
  serverError = signal<string | null>(null);

  form: FormGroup = this.fb.group({});

  ngOnInit(): void {
    const id = this.data.document.id;
    forkJoin({
      champs: this.service.champs(id),
      valeurs: this.service.valeurs(id),
    }).subscribe({
      next: r => {
        const dejaSaisi = new Map(r.valeurs.map(v => [v.indexFieldId, v.valeur]));
        for (const c of r.champs) {
          this.form.addControl(this.cle(c), this.fb.control(dejaSaisi.get(c.id) ?? ''));
        }
        this.champs.set(r.champs);
        this.chargement.set(false);
      },
      error: () => {
        this.chargement.set(false);
        this.serverError.set("Impossible de charger les champs d'indexation.");
      },
    });
  }

  cle(c: Critere): string { return `c${c.id}`; }

  submit(): void {
    this.loading.set(true);
    this.serverError.set(null);

    const valeurs = this.champs().map(c => ({
      indexFieldId: c.id,
      valeur: `${this.form.get(this.cle(c))?.value ?? ''}`.trim() || null,
    }));

    this.service.enregistrer(this.data.document.id, valeurs).subscribe({
      next: () => {
        this.notify.success('Valeurs d\'index enregistrées.');
        this.ref.close(true);
      },
      error: err => {
        this.loading.set(false);
        // Le serveur refuse une valeur incompatible avec le type de l'index
        this.serverError.set(err?.error?.message ?? err?.error?.error
          ?? 'Une valeur ne correspond pas au type de son index.');
      },
    });
  }
}
