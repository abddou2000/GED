import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { SessionService } from '../../../core/session.service';
import { BrandLogo } from '../../../core/brand-logo/brand-logo';

/**
 * Écran de connexion — COQUE UI (front). L'authentification réelle est assurée par
 * Spring Security côté serveur et reste hors périmètre : ici on valide la FORME du
 * formulaire puis on ouvre l'accueil. Aucun secret n'est transmis ni stocké.
 *
 * Présentation : fond « aurore » animé + carte en verre dépoli. Les champs sont
 * natifs (et non Material) pour maîtriser entièrement le rendu sur fond sombre.
 */
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, MatIconModule, BrandLogo],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private fb = inject(FormBuilder);
  private session = inject(SessionService);
  private router = inject(Router);

  protected hide = signal(true);

  protected form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(4)]],
  });

  /** Affiche l'erreur seulement après interaction (évite un formulaire rouge à l'ouverture). */
  protected showError(champ: 'email' | 'password'): boolean {
    const c = this.form.controls[champ];
    return c.invalid && (c.touched || c.dirty);
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.session.signIn(this.form.controls.email.value.trim());
    this.router.navigateByUrl('/accueil');
  }
}
