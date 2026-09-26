package com.ipt.ged.fichier.cles;

import java.util.Set;

/**
 * Accès aux clés maîtresses (KEK) qui enveloppent les clés de données (§6.1.2).
 *
 * <p>L'interface ne rend <b>jamais</b> une KEK : elle enveloppe et désenveloppe.
 * C'est ce qui permet de brancher un KMS ou un HSM (où la clé maîtresse ne sort
 * pas du boîtier) à la place du keystore PKCS#12 livré, sans toucher au reste.
 *
 * <p>Le {@code contexte} est lié cryptographiquement à l'enveloppe (données
 * authentifiées additionnelles) : une DEK enveloppée pour un fichier ne se
 * désenveloppe pas si on la recopie sur la ligne d'un autre fichier.
 */
public interface KeyProvider {

    /** Identifiant de la KEK utilisée pour toute nouvelle enveloppe. */
    String kekActive();

    /** KEK connues, active et désactivées (ces dernières ne servent qu'à désenvelopper). */
    Set<String> kekConnues();

    /** Enveloppe une DEK avec la KEK active. */
    CleEnveloppee envelopper(byte[] dek, byte[] contexte);

    /**
     * Désenveloppe une DEK. L'appelant efface le tableau rendu dès usage.
     *
     * @throws CleIndisponibleException KEK inconnue, ou enveloppe/contexte altérés.
     */
    byte[] desenvelopper(CleEnveloppee enveloppe, byte[] contexte);

    /**
     * Crée une nouvelle KEK et en fait la KEK active ; les précédentes restent
     * disponibles, désactivées. Le réenveloppement des DEK existantes est
     * l'affaire de {@link RotationKek}.
     *
     * @return identifiant de la nouvelle KEK.
     */
    String nouvelleKek();

    /**
     * Retire définitivement une KEK désactivée. À n'appeler que lorsque plus
     * aucune DEK ni aucune sauvegarde ne la référence (§6.1.2) ; {@link
     * RotationKek#retirer} vérifie la première condition.
     */
    void retirer(String kekId);
}
