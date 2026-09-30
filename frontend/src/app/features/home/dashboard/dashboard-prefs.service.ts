import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { SessionService } from '../../../core/session.service';
import { ModulesService } from '../../../core/modules.service';
import { CATALOGUE, CleWidget, DISPOSITION_PAR_DEFAUT, DefinitionWidget, definition } from './widgets';

/**
 * Disposition du tableau de bord, mémorisée par utilisateur.
 *
 * <p>Le réglage est propre à une personne, pas à un poste : deux comptes qui se
 * relaient sur le même navigateur ne doivent pas hériter du tableau de bord de
 * l'autre. La clé de stockage porte donc l'identifiant de session.</p>
 *
 * <p>La disposition n'est qu'une LISTE ORDONNÉE de clés visibles. Un encart
 * absent de la liste est masqué ; l'ordre de la liste est l'ordre à l'écran.
 * Deux notions dans une seule donnée, impossible à désynchroniser.</p>
 */
@Injectable({ providedIn: 'root' })
export class DashboardPrefs {
  private session = inject(SessionService);
  private modules = inject(ModulesService);

  private readonly etat = signal<CleWidget[]>([...DISPOSITION_PAR_DEFAUT]);

  /** Clés visibles, dans l'ordre d'affichage. */
  readonly disposition = this.etat.asReadonly();

  /**
   * Définitions correspondantes — ce que le gabarit d'accueil parcourt. Un
   * encart d'un module désactivé n'est pas rendu (T-088) ; il reste dans la
   * disposition et revient si le module est réactivé.
   */
  readonly visibles = computed<DefinitionWidget[]>(
    () => this.etat().map(definition).filter((d): d is DefinitionWidget => !!d)
      .filter(d => !d.module || this.modules.actif(d.module)));

  /** Vrai si la disposition diffère de celle servie par défaut. */
  readonly personnalise = computed(
    () => this.etat().join('|') !== DISPOSITION_PAR_DEFAUT.join('|'));

  constructor() {
    /* La disposition est relue à CHAQUE changement d'identité. Charger une
       seule fois à la construction ne suffirait pas : le service est instancié
       avant que la session ait résolu l'utilisateur, et l'on relirait alors la
       clé « anonyme » — un utilisateur retrouverait la disposition d'un autre,
       ou aucune. */
    effect(() => {
      const id = this.session.user()?.id ?? null;
      untracked(() => this.charger(id));
    });
  }

  estVisible(cle: CleWidget): boolean {
    return this.etat().includes(cle);
  }

  /** Affiche ou masque un encart. Un encart fixe ne bouge pas. */
  basculer(cle: CleWidget): void {
    const def = definition(cle);
    if (!def || def.fixe) return;

    const courant = this.etat();
    if (courant.includes(cle)) {
      this.appliquer(courant.filter(c => c !== cle));
      return;
    }
    /* Un encart réactivé revient à sa place d'origine dans le catalogue plutôt
       qu'en fin de liste : décocher puis recocher ne doit pas réorganiser le
       tableau de bord dans le dos de l'utilisateur. */
    const rang = CATALOGUE.findIndex(d => d.cle === cle);
    const suite = [...courant];
    const apres = suite.findIndex(c => CATALOGUE.findIndex(d => d.cle === c) > rang);
    suite.splice(apres === -1 ? suite.length : apres, 0, cle);
    this.appliquer(suite);
  }

  /** Remonte (`-1`) ou descend (`+1`) un encart d'un cran. */
  deplacer(cle: CleWidget, sens: -1 | 1): void {
    const def = definition(cle);
    if (!def || def.fixe) return;

    const suite = [...this.etat()];
    const de = suite.indexOf(cle);
    const vers = de + sens;
    // Un encart fixe reste ancré : on ne passe pas au-dessus de lui.
    if (de === -1 || vers < 0 || vers >= suite.length) return;
    if (definition(suite[vers])?.fixe) return;

    [suite[de], suite[vers]] = [suite[vers], suite[de]];
    this.appliquer(suite);
  }

  /** Revient à la disposition d'origine. */
  reinitialiser(): void {
    this.appliquer([...DISPOSITION_PAR_DEFAUT]);
  }

  // ------------------------------------------------------------------ interne

  private appliquer(suite: CleWidget[]): void {
    this.etat.set(suite);
    try {
      localStorage.setItem(this.cle(this.session.user()?.id ?? null), JSON.stringify(suite));
    } catch {
      /* Stockage plein ou refusé (navigation privée) : la disposition reste
         valable pour la session en cours. Échouer bruyamment sur un réglage
         d'affichage serait disproportionné. */
    }
  }

  private charger(id: string | null): void {
    const brut = localStorage.getItem(this.cle(id));
    if (!brut) { this.etat.set([...DISPOSITION_PAR_DEFAUT]); return; }
    try {
      const enregistre = JSON.parse(brut) as unknown;
      if (!Array.isArray(enregistre)) throw new Error('format inattendu');

      /* On filtre sur le catalogue COURANT : un encart retiré du produit ne
         doit pas laisser un trou, et un doublon glissé à la main ne doit pas
         afficher deux fois le même bloc. */
      const connues = new Set(CATALOGUE.map(d => d.cle));
      const retenues = [...new Set(enregistre.filter(
        (c): c is CleWidget => typeof c === 'string' && connues.has(c as CleWidget)))];

      // Les encarts obligatoires sont réinjectés à leur rang de catalogue.
      for (const def of CATALOGUE) {
        if (def.fixe && !retenues.includes(def.cle)) retenues.unshift(def.cle);
      }
      this.etat.set(retenues.length ? retenues : [...DISPOSITION_PAR_DEFAUT]);
    } catch {
      localStorage.removeItem(this.cle(id));
      this.etat.set([...DISPOSITION_PAR_DEFAUT]);
    }
  }

  /** Clé de stockage : une disposition par utilisateur, pas par navigateur. */
  private cle(id: string | null): string {
    return `ged-tableau-de-bord-${id ?? 'anonyme'}`;
  }
}
