package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.support.LdapContextSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Contrôleurs de domaine déclarés et bascule de l'un à l'autre (décision D4,
 * exigence P-02).
 *
 * <p><b>Pourquoi la GED fait la bascule elle-même</b> (ANO-E2-002) : passer la
 * liste des URL à JNDI ne bascule que si la connexion TCP est <i>refusée</i>.
 * Un contrôleur bloqué ou surchargé qui accepte la connexion sans répondre
 * fait expirer le délai de lecture sur une connexion déjà établie : JNDI
 * n'essaie pas le suivant, et avec le pool la connexion muette est même
 * resservie aux demandes suivantes. Chaque contrôleur a donc sa propre source
 * (une seule URL, mêmes réglages : délais de connexion et de lecture, LDAPS,
 * pool), et l'opération est rejouée sur le contrôleur suivant quand le
 * précédent est injoignable ou muet.
 *
 * <p><b>Mise à l'écart</b> : un contrôleur qui vient d'échouer passe en fin de
 * liste pendant {@code ged.identite.annuaire.mise-a-l-ecart} (30 s par
 * défaut) ; les demandes suivantes ne paient pas son délai de lecture. Il
 * redevient prioritaire à l'échéance, ou dès qu'il répond. S'ils sont tous à
 * l'écart, tous sont essayés quand même, dans l'ordre de préférence.
 *
 * <p><b>Seule une panne fait basculer</b> : un refus de l'annuaire (mot de
 * passe faux, compte désactivé) est une réponse, rendue telle quelle. Le
 * rejouer sur un autre contrôleur compterait un échec de plus dans le
 * verrouillage de compte d'Active Directory.
 */
public class ControleursAnnuaire {

    private static final Logger log = LoggerFactory.getLogger(ControleursAnnuaire.class);

    /** Un contrôleur de domaine : son rang dans la liste déclarée, son URL, sa source. */
    public record Controleur(int rang, String url, LdapContextSource source) {
    }

    private final List<Controleur> controleurs;
    private final Duration miseALEcart;
    private final Clock horloge;
    /** Contrôleurs à l'écart : URL → fin de la mise à l'écart. */
    private final Map<String, Instant> ecartes = new ConcurrentHashMap<>();

    public ControleursAnnuaire(List<Controleur> controleurs, Duration miseALEcart, Clock horloge) {
        if (controleurs.isEmpty()) {
            throw new IllegalArgumentException("Au moins un contrôleur de domaine est requis.");
        }
        this.controleurs = List.copyOf(controleurs);
        this.miseALEcart = miseALEcart == null || miseALEcart.isNegative() ? Duration.ZERO : miseALEcart;
        this.horloge = horloge;
    }

    public List<Controleur> controleurs() {
        return controleurs;
    }

    /**
     * Exécute l'opération sur le premier contrôleur qui répond. L'opération
     * signale un contrôleur injoignable ou muet par
     * {@link AnnuaireIndisponibleException} ; toute autre issue (résultat ou
     * refus) est rendue sans essayer les suivants.
     *
     * @throws AnnuaireIndisponibleException si aucun contrôleur ne répond
     */
    public <T> T executer(Function<Controleur, T> operation) {
        AnnuaireIndisponibleException derniere = null;
        for (Controleur c : ordre()) {
            try {
                T resultat = operation.apply(c);
                if (ecartes.remove(c.url()) != null) {
                    log.info("Contrôleur de domaine {} de nouveau joignable.", c.url());
                }
                return resultat;
            } catch (AnnuaireIndisponibleException e) {
                derniere = e;
                ecartes.put(c.url(), horloge.instant().plus(miseALEcart));
                log.warn("Contrôleur de domaine {} injoignable ou muet ({}) : bascule sur le suivant, "
                        + "mis à l'écart {} s.", c.url(), cause(e), miseALEcart.toSeconds());
            }
        }
        throw derniere;
    }

    /** Ordre d'essai : les contrôleurs disponibles dans l'ordre déclaré, puis ceux à l'écart. */
    List<Controleur> ordre() {
        Instant maintenant = horloge.instant();
        List<Controleur> disponibles = new ArrayList<>(controleurs.size());
        List<Controleur> aLEcart = new ArrayList<>();
        for (Controleur c : controleurs) {
            Instant fin = ecartes.get(c.url());
            if (fin != null && maintenant.isBefore(fin)) aLEcart.add(c);
            else disponibles.add(c);
        }
        disponibles.addAll(aLEcart);
        return disponibles;
    }

    private static String cause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage();
    }
}
