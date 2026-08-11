import { Injectable } from '@angular/core';

/** Une colonne proposée au masquage. */
export interface ColonneDef {
  /** Clé technique, celle utilisée dans `matColumnDef`. */
  cle: string;
  /** Intitulé montré dans le sélecteur. */
  libelle: string;
  /** Colonne structurelle (case à cocher, actions) : jamais masquable. */
  toujours?: boolean;
  /**
   * Colonne disponible mais repliée tant que l'utilisateur ne l'a pas demandée.
   * Reprend le {@code shown: false} de l'application d'origine : la donnée
   * existe, elle n'encombre simplement pas le premier coup d'œil.
   */
  masqueeParDefaut?: boolean;
}

/**
 * Mémorisation des colonnes masquées, par écran.
 *
 * <p>Même clé de stockage que l'application d'origine (`<écran>-column-visibility`)
 * afin qu'un utilisateur passant d'une GED à l'autre retrouve ses réglages.
 */
@Injectable({ providedIn: 'root' })
export class ColumnPrefs {

  private cle(titre: string): string {
    return `${titre}-column-visibility`;
  }

  /**
   * État de visibilité de chaque colonne. Une colonne absente du stockage est
   * considérée visible : ajouter une colonne à un écran ne doit pas la faire
   * disparaître chez ceux qui ont déjà des préférences enregistrées.
   */
  charger(titre: string, colonnes: ColonneDef[]): Record<string, boolean> {
    const etat = Object.fromEntries(
      colonnes.map(c => [c.cle, !(c.masqueeParDefaut && !c.toujours)]));
    const brut = localStorage.getItem(this.cle(titre));
    if (!brut) return etat;
    try {
      const enregistre = JSON.parse(brut) as Record<string, boolean>;
      /* On applique la valeur enregistrée telle quelle, dans les DEUX sens.
         En ne recopiant que les `false`, une colonne repliée par défaut ne
         pouvait jamais être affichée durablement : l'utilisateur la cochait,
         le `true` partait bien dans le stockage, et le chargement suivant le
         ramenait à `false`. La case restait cochée, la colonne absente. */
      for (const c of colonnes) {
        if (c.toujours) continue;
        const valeur = enregistre[c.cle];
        if (typeof valeur === 'boolean') etat[c.cle] = valeur;
      }
    } catch {
      // Stockage illisible (édité à la main, format ancien) : on repart des
      // colonnes complètes plutôt que de laisser l'écran dans un état bancal.
      localStorage.removeItem(this.cle(titre));
    }
    return etat;
  }

  enregistrer(titre: string, etat: Record<string, boolean>): void {
    localStorage.setItem(this.cle(titre), JSON.stringify(etat));
  }
}
