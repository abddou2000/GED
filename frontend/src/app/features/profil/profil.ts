import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ProfilService } from './profil.service';
import { Profil } from './profil.model';
import { SessionService } from '../../core/session.service';
import { AuthService } from '../../core/auth.service';
import { teinteAvatar, encreAvatar, initialesDe } from '../../core/avatar';
import { dateCourte } from '../home/dashboard/dates';
import { CircuitService } from '../workflow/circuit.service';
import { DecisionRendue } from '../workflow/circuit.model';
import { ModulesService } from '../../core/modules.service';

/**
 * Page « Mon profil » — identité et activité de l'utilisateur.
 *
 * <p>Une seule page : bannière d'identité, puis une grille qui remplit la
 * hauteur restante. Le contenu vient entièrement de la GED : dossiers dont
 * la personne est propriétaire, groupes qui l'incluent, documents qu'elle a
 * déposés, signatures qui l'attendent. Les rubriques du modèle sans équivalent
 * ici — projets, budgets, réseaux sociaux — ne sont pas reproduites : les
 * remplir demanderait d'inventer.
 */
@Component({
  selector: 'app-profil',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './profil.html',
  styleUrl: './profil.scss',
})
export class ProfilPage implements OnInit {
  /** Date lisible pour la fiche : « il y a 2 h » plutôt qu'un horodatage. */
  protected readonly quand = dateCourte;

  private service = inject(ProfilService);
  private circuits = inject(CircuitService);
  private modules = inject(ModulesService);

  /** Validations, décisions et lien « Mes validations » relèvent du module
   *  workflow : masqués quand il est désactivé (T-088). */
  readonly workflowActif = () => this.modules.actif('workflow');

  /**
   * Mes dernières décisions de validation — accordées ou refusées (§12.8).
   *
   * <p>C'est la seule matière de cette fiche qui grandit avec l'usage : le
   * reste (identité, dossiers, groupes) est court et fixe. Sans
   * elle, la page ne pouvait être remplie qu'en étirant des cartes creuses.</p>
   */
  protected readonly decisions = signal<DecisionRendue[]>([]);
  private session = inject(SessionService);
  /** Fiche d'un groupe : écran d'administration (GERER_ROLES_HABILITATIONS, ANO-F-003). */
  protected auth = inject(AuthService);

  profil = signal<Profil | null>(null);
  chargement = signal(true);
  erreur = signal(false);

  /** Ce qui attend une décision, en clair : le chiffre du bandeau ne dit pas
   *  QUOI valider, et c'est pourtant la première question qu'on se pose. */

  /** Initiales pour l'avatar — la GED ne stocke aucune photo. */
  readonly initiales = computed(() => initialesDe(this.profil()?.fullName ?? ''));

  /* Teinte de la pastille : elle vient de la palette partagée, indexée sur
     l'identifiant de la personne. La même tête garde donc sa couleur ici et
     dans les tableaux où elle apparaît. */
  readonly teinte = computed(() => teinteAvatar(this.profil()?.id));
  readonly encre = computed(() => encreAvatar(this.profil()?.id));

  readonly email = computed(() => this.session.user()?.email ?? null);

  ngOnInit(): void {
    this.session.ensureUser();
    this.charger();
  }

  charger(): void {
    this.chargement.set(true);
    this.erreur.set(false);
    /* « Mon profil » interroge toujours la route sans identifiant : c'est le
       serveur qui sait qui appelle. Passer par /employes/{id}/profil avec l'id
       de session n'apportait rien et entretenait l'idée que le client choisit
       l'identité qu'il consulte. */
    this.service.courant().subscribe({
      next: p => {
        this.profil.set(p);
        this.chargement.set(false);
      },
      error: () => { this.chargement.set(false); this.erreur.set(true); },
    });
    /* L'historique est déjà filtré sur l'approbateur par le serveur : c'est
       bien MON activité, pas celle de la maison. Un échec laisse la carte vide
       sans empêcher le reste de la fiche de s'afficher. */
    // Module désactivé : ses routes répondent 404, rien à demander (T-088).
    this.modules.charger().subscribe(() => {
      if (!this.workflowActif()) { this.decisions.set([]); return; }
      this.circuits.historique().subscribe({
        next: l => this.decisions.set(
          l.filter(s => s.decision !== 'ANNULEE').sort((a, b) => b.le.localeCompare(a.le))),
        error: () => this.decisions.set([]),
      });
    });
  }

}
