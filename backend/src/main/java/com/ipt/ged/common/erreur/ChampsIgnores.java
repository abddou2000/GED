package com.ipt.ged.common.erreur;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Signalement des paramètres et champs inconnus <b>ignorés</b> par le serveur
 * (DAT §5.3.2, P-08) : leurs noms sont rendus dans l'en-tête de réponse
 * {@value #ENTETE}, séparés par des virgules ({@code criteres[0].valeurr} pour
 * un champ imbriqué). La réponse est celle de la requête sans ces champs.
 *
 * <p>Les noms sont encodés en pourcentage (UTF-8) hors ASCII imprimable, de la
 * virgule et du signe {@code %} ; au plus {@value #NOMS_MAX} noms de
 * {@value #LONGUEUR_MAX} caractères, au-delà de quoi un dernier élément
 * {@code ...} indique la troncature.
 */
public final class ChampsIgnores {

    /** En-tête de réponse : paramètres ou champs inconnus ignorés. */
    public static final String ENTETE = "GED-Champs-Ignores";

    static final int NOMS_MAX = 20;
    static final int LONGUEUR_MAX = 100;
    private static final String ATTRIBUT = ChampsIgnores.class.getName();

    private ChampsIgnores() {}

    /** Ajoute {@code nom} à l'en-tête de la requête en cours ; sans requête en cours, rien. */
    public static void signaler(String nom) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                && a.getResponse() != null) {
            signaler(a.getRequest(), a.getResponse(), nom);
        }
    }

    /** Ajoute {@code nom} à l'en-tête {@value #ENTETE} de {@code reponse}. */
    public static void signaler(HttpServletRequest requete, HttpServletResponse reponse, String nom) {
        @SuppressWarnings("unchecked")
        Set<String> noms = (Set<String>) requete.getAttribute(ATTRIBUT);
        if (noms == null) {
            noms = new LinkedHashSet<>();
            requete.setAttribute(ATTRIBUT, noms);
        }
        if (noms.size() > NOMS_MAX) return;
        noms.add(noms.size() == NOMS_MAX ? "..." : encoder(nom));
        if (!reponse.isCommitted()) {
            reponse.setHeader(ENTETE, String.join(", ", noms));
        }
    }

    static String encoder(String nom) {
        String n = nom.length() > LONGUEUR_MAX ? nom.substring(0, LONGUEUR_MAX) : nom;
        StringBuilder s = new StringBuilder(n.length());
        for (byte b : n.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c > 0x20 && c < 0x7F && c != ',' && c != '%') {
                s.append((char) c);
            } else {
                s.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return s.toString();
    }
}
