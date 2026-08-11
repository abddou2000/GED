import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../../core/auth.service';
import { SessionService } from '../../../core/session.service';
import { BrandLogo } from '../../../core/brand-logo/brand-logo';

/**
 * Écran de connexion.
 *
 * <p>Il transmet les identifiants à `POST /auth/login` et n'ouvre l'application
 * que si le serveur délivre un jeton. Le mot de passe n'est ni journalisé, ni
 * conservé après l'appel : le champ est vidé dès la réponse, succès ou échec.
 *
 * <p>Les messages d'erreur reprennent ceux du serveur sans les préciser : c'est
 * lui qui décide de ne pas distinguer un e-mail inconnu d'un mot de passe faux,
 * et l'interface ne doit pas rétablir cette distinction.
 */
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, MatIconModule, BrandLogo],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private session = inject(SessionService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  protected hide = signal(true);
  protected enCours = signal(false);
  protected erreur = signal<string | null>(null);

  /** Signalé par l'intercepteur quand le serveur a refusé un jeton périmé. */
  protected readonly sessionExpiree = this.route.snapshot.queryParamMap.get('expire') === '1';

  protected form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(4)]],
    memoriser: [false],
  });

  /** Affiche l'erreur seulement après interaction (évite un formulaire rouge à l'ouverture). */
  protected showError(champ: 'email' | 'password'): boolean {
    const c = this.form.controls[champ];
    return c.invalid && (c.touched || c.dirty);
  }

  submit(): void {
    if (this.form.invalid || this.enCours()) {
      this.form.markAllAsTouched();
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);

    const email = this.form.controls.email.value.trim();
    const motDePasse = this.form.controls.password.value;
    const memoriser = this.form.controls.memoriser.value;

    this.auth.connexion(email, motDePasse, memoriser).subscribe({
      next: r => {
        // Le mot de passe ne doit pas survivre à l'appel, même en mémoire du
        // formulaire : un écran resté ouvert le garderait à disposition.
        this.form.controls.password.reset('');
        this.session.adopter(r.employeId, r.fullName, r.email);
        this.enCours.set(false);
        const suite = this.route.snapshot.queryParamMap.get('suite');
        this.router.navigateByUrl(suite && suite !== '/login' ? suite : '/accueil');
      },
      error: (e: HttpErrorResponse) => {
        this.enCours.set(false);
        this.form.controls.password.reset('');
        this.erreur.set(this.message(e));
      },
    });
  }

  private message(e: HttpErrorResponse): string {
    if (e.status === 0) {
      return "Le serveur ne répond pas. Vérifiez qu'il est démarré.";
    }
    // Le serveur formule déjà ses refus (identifiants, compte désactivé, trop
    // de tentatives) : on les relaie tels quels plutôt que de les réinventer.
    const detail = e.error?.message ?? e.error?.detail;
    if (typeof detail === 'string' && detail.trim()) return detail;
    if (e.status === 429) return 'Trop de tentatives. Réessayez dans quelques minutes.';
    if (e.status === 403) return 'Ce compte est désactivé.';
    return 'E-mail ou mot de passe incorrect.';
  }
}
