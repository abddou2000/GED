package com.ipt.ged.conventionsapi;

import com.ipt.ged.common.erreur.CodesErreur;
import com.ipt.ged.common.erreur.ReponsesSecuriteProblem;
import com.ipt.ged.fichier.CodesErreurFichier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conventions communes de l'API (DAT §5.3.2), appliquées à tout {@code /api/**} :
 * <ul>
 *   <li><b>Pagination</b> : paramètres {@code page} et {@code taille} (défaut 50,
 *       maximum 200). {@code taille} est l'alias contractuel de {@code size},
 *       nom historique des contrôleurs ; sans l'un ni l'autre, une lecture reçoit
 *       50 ; le plafond de 200 est appliqué par {@link com.ipt.ged.common.Tri}.</li>
 *   <li><b>Métadonnées</b> limitées à 64 Ko : corps JSON, ou champs non fichiers
 *       d'un envoi multipart ; au-delà, 413 {@code METADONNEES_TROP_VOLUMINEUSES}.
 *       La taille des fichiers relève des contrôles de dépôt (§6.1.5).</li>
 *   <li><b>Versionnage</b> : pour un préfixe annoncé obsolète, en-têtes
 *       {@code Deprecation} (RFC 9745), {@code Sunset} (RFC 8594) et lien
 *       {@code successor-version}.</li>
 * </ul>
 * Placé après la chaîne de sécurité : un envoi multipart n'est analysé que pour
 * un appelant authentifié.
 */
public class FiltreConventionsApi extends OncePerRequestFilter {

    public static final String METADONNEES_TROP_VOLUMINEUSES = "METADONNEES_TROP_VOLUMINEUSES";

    private static final Logger JOURNAL = LoggerFactory.getLogger(FiltreConventionsApi.class);

    /** Taille de page par défaut (DAT §5.3.2) ; le maximum, 200, est appliqué par {@code Tri}. */
    public static final int TAILLE_DEFAUT = 50;

    private final ProprietesConventionsApi proprietes;
    private final ReponsesSecuriteProblem reponses;

    public FiltreConventionsApi(ProprietesConventionsApi proprietes, ReponsesSecuriteProblem reponses) {
        this.proprietes = proprietes;
        this.reponses = reponses;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest requete) {
        return !requete.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest requete, @NonNull HttpServletResponse reponse,
                                    @NonNull FilterChain suite) throws ServletException, IOException {
        annoncerDepreciation(requete, reponse);

        long metadonnees;
        try {
            metadonnees = tailleMetadonnees(requete);
        } catch (IllegalStateException | IOException | ServletException e) {
            if (!plafondDepasse(e)) throw e;
            // Le conteneur refuse l'envoi au-delà de spring.servlet.multipart.* :
            // même réponse que le gestionnaire commun (413, §6.1.5), et non une
            // 500 hors de Spring MVC (ANO-E5-004).
            JOURNAL.warn("Refus 413 {} : {}", CodesErreurFichier.FICHIER_TROP_VOLUMINEUX, e.getMessage());
            reponses.ecrire(requete, reponse, HttpStatus.PAYLOAD_TOO_LARGE, CodesErreurFichier.FICHIER_TROP_VOLUMINEUX,
                    "Fichier trop volumineux : plafond de la plateforme dépassé.");
            return;
        }
        if (metadonnees > proprietes.metadonneesMax()) {
            reponses.ecrire(requete, reponse, HttpStatus.PAYLOAD_TOO_LARGE, METADONNEES_TROP_VOLUMINEUSES,
                    "Métadonnées trop volumineuses : " + metadonnees + " octets pour un maximum de "
                            + proprietes.metadonneesMax() + ".");
            return;
        }

        HttpServletRequest suivante = requete;
        if ("GET".equals(requete.getMethod()) && requete.getParameter("size") == null) {
            suivante = new AliasTaille(requete);
        }
        suite.doFilter(suivante, reponse);
    }

    private void annoncerDepreciation(HttpServletRequest requete, HttpServletResponse reponse) {
        for (ProprietesConventionsApi.Depreciation d : proprietes.depreciations()) {
            if (requete.getRequestURI().startsWith(d.prefixe())) {
                reponse.setHeader("Deprecation", "@" + d.annonce().atStartOfDay().toEpochSecond(ZoneOffset.UTC));
                reponse.setHeader("Sunset", DateTimeFormatter.RFC_1123_DATE_TIME
                        .format(d.retrait().atStartOfDay().atOffset(ZoneOffset.UTC)));
                if (d.successeur() != null && !d.successeur().isBlank()) {
                    reponse.addHeader("Link", "<" + d.successeur() + ">; rel=\"successor-version\"");
                }
                return;
            }
        }
    }

    /** Taille des métadonnées : corps JSON, ou champs non fichiers d'un multipart. */
    private static long tailleMetadonnees(HttpServletRequest requete) throws IOException, ServletException {
        String type = requete.getContentType() == null ? "" : requete.getContentType().toLowerCase();
        if (type.startsWith("application/json") || type.contains("+json")) {
            return Math.max(requete.getContentLengthLong(), 0);
        }
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(requete, MultipartHttpServletRequest.class);
        if (multipart != null) {
            return tailleParametres(multipart.getParameterMap());
        }
        if (type.startsWith("multipart/")) {
            long total = 0;
            for (Part p : requete.getParts()) {
                if (p.getSubmittedFileName() == null) total += p.getSize();
            }
            return total;
        }
        return 0;
    }

    /**
     * Vrai si l'échec d'analyse multipart vient d'un plafond de taille (fichier ou
     * requête). Tomcat enveloppe sa {@code FileSizeLimitExceededException} ou sa
     * {@code SizeLimitExceededException} dans une {@link IllegalStateException} ;
     * le critère est celui de Spring ({@code StandardMultipartHttpServletRequest}),
     * indépendant du conteneur.
     */
    static boolean plafondDepasse(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String nom = t.getClass().getSimpleName();
            if (nom.contains("SizeLimitExceeded") || nom.contains("SizeException")) return true;
            String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (msg.contains("exceed") && (msg.contains("size") || msg.contains("length"))) return true;
        }
        return false;
    }

    private static long tailleParametres(Map<String, String[]> params) {
        long total = 0;
        for (Map.Entry<String, String[]> e : params.entrySet()) {
            for (String v : e.getValue()) total += v.getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    /** Présente {@code taille} sous le nom {@code size} attendu par les contrôleurs. */
    static final class AliasTaille extends HttpServletRequestWrapper {
        private final Map<String, String[]> parametres;

        AliasTaille(HttpServletRequest requete) {
            super(requete);
            Map<String, String[]> p = new LinkedHashMap<>(requete.getParameterMap());
            String[] taille = requete.getParameterValues("taille");
            // Sans taille demandée : 50, le défaut contractuel (§5.3.2), et non le
            // défaut historique de chaque contrôleur.
            p.put("size", taille != null ? taille : new String[]{Integer.toString(TAILLE_DEFAUT)});
            this.parametres = Collections.unmodifiableMap(p);
        }

        @Override
        public String getParameter(String nom) {
            String[] v = parametres.get(nom);
            return v == null || v.length == 0 ? null : v[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parametres;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parametres.keySet());
        }

        @Override
        public String[] getParameterValues(String nom) {
            return parametres.get(nom);
        }
    }
}
