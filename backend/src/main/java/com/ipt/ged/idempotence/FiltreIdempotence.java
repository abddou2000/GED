package com.ipt.ged.idempotence;

import com.ipt.ged.cleapi.ApplicationAuthentifiee;
import com.ipt.ged.common.erreur.ReponsesSecuriteProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Idempotence des créations (DAT §5.3.2) : l'en-tête {@code Idempotency-Key}
 * (UUID) est obligatoire sur les créations déclarées dans
 * {@link ProprietesIdempotence#routes()} ; la réponse est mémorisée 24 heures
 * par appelant (application ou utilisateur) avec l'empreinte de la requête.
 * <ul>
 *   <li>rejeu avec la même clé et le même contenu : la réponse initiale est
 *       renvoyée telle quelle (en-tête {@code Idempotency-Replayed: true}),
 *       sans nouvelle exécution, donc sans doublon ;</li>
 *   <li>même clé, contenu différent : 422 {@code IDEMPOTENCE_CONFLIT} ;</li>
 *   <li>même clé pendant que la première requête s'exécute : 409
 *       {@code IDEMPOTENCE_EN_COURS} (la clé est réservée avant l'exécution,
 *       deux requêtes simultanées ne peuvent pas créer deux objets) ;</li>
 *   <li>seule une réponse de succès (2xx) est mémorisée : après un refus,
 *       l'appelant peut corriger et rejouer avec la même clé.</li>
 * </ul>
 *
 * <p>Filtre générique : un nouveau point d'entrée de création s'y soumet en
 * ajoutant une ligne à la configuration, sans code. Placé après la chaîne de
 * sécurité : l'appelant est connu, et une requête non authentifiée n'a rien
 * réservé.
 *
 * <p>Empreinte de la requête : méthode, chemin, paramètres triés et contenu.
 * Pour un envoi multipart, chaque partie compte par son nom, son nom de fichier
 * et l'empreinte de son contenu, et non par les octets bruts : la frontière
 * multipart change d'un envoi à l'autre pour un même contenu.
 */
public class FiltreIdempotence extends OncePerRequestFilter {

    public static final String ENTETE = "Idempotency-Key";
    public static final String ENTETE_REJEU = "Idempotency-Replayed";

    private static final Logger journal = LoggerFactory.getLogger(FiltreIdempotence.class);
    private static final HexFormat HEX = HexFormat.of();

    private final DepotIdempotence depot;
    private final ProprietesIdempotence proprietes;
    private final ReponsesSecuriteProblem reponses;
    private final Clock horloge;
    private final List<Route> routes;

    private record Route(String methode, PathPattern motif) {}

    public FiltreIdempotence(DepotIdempotence depot, ProprietesIdempotence proprietes,
                             ReponsesSecuriteProblem reponses, Clock horloge) {
        this.depot = depot;
        this.proprietes = proprietes;
        this.reponses = reponses;
        this.horloge = horloge;
        PathPatternParser analyseur = new PathPatternParser();
        this.routes = proprietes.routes().stream().map(r -> {
            String[] morceaux = r.trim().split("\\s+", 2);
            return new Route(morceaux[0].toUpperCase(), analyseur.parse(morceaux[1]));
        }).toList();
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest requete) {
        org.springframework.http.server.PathContainer chemin =
                org.springframework.http.server.PathContainer.parsePath(requete.getRequestURI());
        return routes.stream().noneMatch(r -> r.methode().equals(requete.getMethod()) && r.motif().matches(chemin));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest requete, @NonNull HttpServletResponse reponse,
                                    @NonNull FilterChain suite) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            // Requête non authentifiée : la sécurité la refuse, rien n'est réservé.
            suite.doFilter(requete, reponse);
            return;
        }
        String brute = requete.getHeader(ENTETE);
        if (brute == null || brute.isBlank()) {
            reponses.ecrire(requete, reponse, HttpStatus.BAD_REQUEST, CodesErreurIdempotence.IDEMPOTENCE_CLE_ABSENTE,
                    "En-tête Idempotency-Key (UUID) obligatoire sur une création.");
            return;
        }
        UUID cle;
        try {
            cle = UUID.fromString(brute.trim());
        } catch (IllegalArgumentException e) {
            reponses.ecrire(requete, reponse, HttpStatus.BAD_REQUEST, CodesErreurIdempotence.IDEMPOTENCE_CLE_INVALIDE,
                    "Idempotency-Key doit être un UUID.");
            return;
        }

        UUID applicationId = auth instanceof ApplicationAuthentifiee a ? a.applicationId() : null;
        String appelant = applicationId != null ? "application:" + applicationId : "utilisateur:" + auth.getName();

        // Le corps n'est lu qu'une fois : on le garde pour l'empreinte et pour l'aval.
        HttpServletRequest lue = memoriserCorpsSiBesoin(requete);
        String empreinte = empreinte(lue);
        Instant maintenant = horloge.instant();

        Optional<UUID> reservation = depot.reserver(applicationId, appelant, cle, requete.getMethod(),
                requete.getRequestURI(), empreinte, maintenant, maintenant.plus(proprietes.duree()));
        if (reservation.isEmpty()) {
            Optional<DepotIdempotence.Entree> existante = depot.lire(appelant, cle);
            if (existante.isPresent() && !existante.get().expireLe().isAfter(maintenant)) {
                // Clé expirée non encore purgée : elle ne vaut plus rien, on la réutilise.
                depot.liberer(existante.get().id());
                reservation = depot.reserver(applicationId, appelant, cle, requete.getMethod(),
                        requete.getRequestURI(), empreinte, maintenant, maintenant.plus(proprietes.duree()));
                existante = reservation.isPresent() ? Optional.empty() : depot.lire(appelant, cle);
            }
            if (reservation.isEmpty()) {
                if (existante.isEmpty()) {
                    // Libérée entre-temps par une requête concurrente en échec : l'appelant rejoue.
                    reponses.ecrire(requete, reponse, HttpStatus.CONFLICT, CodesErreurIdempotence.IDEMPOTENCE_EN_COURS,
                            "Une requête avec cette clé vient d'être traitée ; réessayez.");
                    return;
                }
                repondreSurCleConnue(requete, reponse, existante.get(), empreinte);
                return;
            }
        }

        ContentCachingResponseWrapper capture = new ContentCachingResponseWrapper(reponse);
        boolean memorisee = false;
        try {
            suite.doFilter(lue, capture);
            int statut = capture.getStatus();
            if (statut >= 200 && statut < 300) {
                byte[] corps = capture.getContentAsByteArray();
                depot.terminer(reservation.get(), statut, capture.getContentType(),
                        capture.getHeader(HttpHeaders.LOCATION), corps.length <= proprietes.corpsMax() ? corps : null);
                memorisee = true;
            }
        } finally {
            if (!memorisee) {
                try {
                    depot.liberer(reservation.get());
                } catch (RuntimeException e) {
                    journal.warn("Réservation d'idempotence {} non libérée", reservation.get(), e);
                }
            }
            capture.copyBodyToResponse();
        }
    }

    private void repondreSurCleConnue(HttpServletRequest requete, HttpServletResponse reponse,
                                      DepotIdempotence.Entree entree, String empreinte) throws IOException {
        if (!entree.empreinteRequete().equals(empreinte)) {
            reponses.ecrire(requete, reponse, HttpStatus.UNPROCESSABLE_ENTITY, CodesErreurIdempotence.IDEMPOTENCE_CONFLIT,
                    "Cette Idempotency-Key a déjà servi pour une requête au contenu différent.");
            return;
        }
        if (!entree.terminee()) {
            reponses.ecrire(requete, reponse, HttpStatus.CONFLICT, CodesErreurIdempotence.IDEMPOTENCE_EN_COURS,
                    "Une requête avec cette Idempotency-Key est en cours de traitement.");
            return;
        }
        reponse.setStatus(entree.statut());
        reponse.setHeader(ENTETE_REJEU, "true");
        if (entree.location() != null) reponse.setHeader(HttpHeaders.LOCATION, entree.location());
        if (entree.typeContenu() != null) reponse.setContentType(entree.typeContenu());
        if (entree.corps() != null) {
            reponse.setContentLength(entree.corps().length);
            reponse.getOutputStream().write(entree.corps());
        }
    }

    /* ------------------------------------------------------------- empreinte */

    private static HttpServletRequest memoriserCorpsSiBesoin(HttpServletRequest requete) throws IOException {
        if (estMultipart(requete) || estFormulaire(requete)) {
            return requete;
        }
        return new CorpsMemorise(requete, requete.getInputStream().readAllBytes());
    }

    static String empreinte(HttpServletRequest requete) throws IOException, ServletException {
        MessageDigest sha = sha256();
        champ(sha, "v1");
        champ(sha, requete.getMethod());
        champ(sha, requete.getRequestURI());
        // Requête déjà analysée par Spring (et enveloppée par la sécurité) : ses
        // fichiers ne sont pas des Part, on lit la vue MultipartFile.
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(requete, MultipartHttpServletRequest.class);
        if (multipart != null) {
            parametres(sha, multipart.getParameterMap());
            TreeMap<String, String> fichiers = new TreeMap<>();
            for (Map.Entry<String, List<MultipartFile>> e : multipart.getMultiFileMap().entrySet()) {
                int i = 0;
                for (MultipartFile f : e.getValue()) {
                    fichiers.put(e.getKey() + "\u0000" + i++, f.getOriginalFilename() + "\u0000" + hacher(f.getInputStream()));
                }
            }
            fichiers.forEach((k, v) -> { champ(sha, k); champ(sha, v); });
        } else if (estMultipart(requete)) {
            parametres(sha, requete.getParameterMap());
            List<String> parties = new ArrayList<>();
            for (Part p : requete.getParts()) {
                parties.add(p.getName() + "\u0000" + p.getSubmittedFileName() + "\u0000" + hacher(p.getInputStream()));
            }
            parties.stream().sorted().forEach(p -> champ(sha, p));
        } else if (estFormulaire(requete)) {
            parametres(sha, requete.getParameterMap());
        } else {
            parametres(sha, requete.getParameterMap());
            champ(sha, requete instanceof CorpsMemorise c ? HEX.formatHex(sha256().digest(c.corps)) : "");
        }
        return HEX.formatHex(sha.digest());
    }

    private static void parametres(MessageDigest sha, Map<String, String[]> params) {
        new TreeMap<>(params).forEach((nom, valeurs) -> {
            champ(sha, nom);
            for (String v : valeurs) champ(sha, v);
        });
    }

    private static boolean estMultipart(HttpServletRequest r) {
        return r.getContentType() != null && r.getContentType().toLowerCase().startsWith("multipart/");
    }

    private static boolean estFormulaire(HttpServletRequest r) {
        return r.getContentType() != null
                && r.getContentType().toLowerCase().startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    }

    private static String hacher(InputStream in) throws IOException {
        MessageDigest sha = sha256();
        try (in) {
            byte[] tampon = new byte[64 * 1024];
            int n;
            while ((n = in.read(tampon)) > 0) sha.update(tampon, 0, n);
        }
        return HEX.formatHex(sha.digest());
    }

    /* Chaque valeur précédée de sa longueur : aucun glissement possible entre champs. */
    private static void champ(MessageDigest sha, String valeur) {
        if (valeur == null) {
            sha.update(ByteBuffer.allocate(4).putInt(-1).array());
            return;
        }
        byte[] b = valeur.getBytes(StandardCharsets.UTF_8);
        sha.update(ByteBuffer.allocate(4).putInt(b.length).array());
        sha.update(b);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Requête dont le corps, déjà lu pour l'empreinte, est resservi à l'aval. */
    static final class CorpsMemorise extends HttpServletRequestWrapper {
        final byte[] corps;

        CorpsMemorise(HttpServletRequest requete, byte[] corps) {
            super(requete);
            this.corps = corps;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream flux = new ByteArrayInputStream(corps);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return flux.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
                @Override public int read() { return flux.read(); }
                @Override public int read(byte[] b, int off, int len) { return flux.read(b, off, len); }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            String enc = getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8";
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    java.nio.charset.Charset.forName(enc)));
        }

        @Override
        public int getContentLength() {
            return corps.length;
        }

        @Override
        public long getContentLengthLong() {
            return corps.length;
        }
    }
}
