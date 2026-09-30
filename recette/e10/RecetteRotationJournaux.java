import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedFileNamingAndTriggeringPolicyBase;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Recette E10 — T-077 (DAT 7.1, 7.3.1) : journalisation technique, SANS démarrer l'application.
 *
 * <p>Charge le {@code logback-spring.xml} LIVRÉ (chemin en argument) par le vrai mécanisme de
 * Spring Boot ({@link LoggingApplicationListener} : liaison de {@code logging.*} et
 * {@code ged.journalisation.*}, {@code <springProfile>}, {@code <springProperty>}), profil
 * {@code prod}, avec une taille maximale RÉDUITE à 8 KB par la propriété livrée
 * {@code ged.journalisation.taille-max-fichier} (variable GED_LOG_TAILLE_MAX), puis vérifie :
 * <ol>
 *   <li>INFO par défaut, DEBUG de {@code com.ipt.ged} activé par la seule variable
 *       d'environnement {@code LOGGING_LEVEL_COM_IPT_GED=DEBUG} (même artefact, nouvelle initialisation) ;</li>
 *   <li>pattern de l'Article 50 et champs MDC (username, ip, traceId, spanId, thread) ;</li>
 *   <li>rotation par taille : archives {@code ged.<jour>.<i>.log.gz} compressées (gzip valide) ;</li>
 *   <li>rotation quotidienne : horloge du déclencheur avancée d'un jour ;</li>
 *   <li>rétention 90 jours : une archive de J-100 est purgée au démarrage, une de J-80 conservée.</li>
 * </ol>
 * Usage : {@code lancer-java.sh RecetteRotationJournaux.java RecetteRotationJournaux <logback-spring.xml> <répertoire>}
 */
public class RecetteRotationJournaux {

    private static int ok, echec;
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    static void resultat(String id, boolean vrai, String libelle, String detail) {
        if (vrai) ok++; else echec++;
        System.out.println("RESULTAT|" + id + "|" + (vrai ? "OK" : "ECHEC") + "|" + libelle + "|" + detail);
    }

    /** Initialisation de la journalisation comme au démarrage de Spring Boot (événements réels). */
    static void initialiser(Path config, Path dossier, Map<String, Object> envVars) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        Map<String, Object> systeme = new HashMap<>(System.getenv());
        systeme.putAll(envVars);
        // Même nom que la source d'environnement réelle : liaison souple LOGGING_LEVEL_X -> logging.level.x.
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systeme));
        Map<String, Object> proprietes = new HashMap<>();
        proprietes.put("logging.config", config.toUri().toString());
        proprietes.put("logging.file.path", dossier.toString());
        // Valeurs de exploitation.yml (livré) : seule la taille est réduite pour l'essai.
        proprietes.put("ged.journalisation.taille-max-fichier", "8KB");
        proprietes.put("ged.journalisation.retention-jours", "90");
        env.getPropertySources().addLast(new MapPropertySource("exploitation-recette", proprietes));
        SpringApplication app = new SpringApplication(RecetteRotationJournaux.class);
        DefaultBootstrapContext boot = new DefaultBootstrapContext();
        LoggingApplicationListener l = new LoggingApplicationListener();
        l.onApplicationEvent(new ApplicationStartingEvent(boot, app, new String[0]));
        l.onApplicationEvent(new ApplicationEnvironmentPreparedEvent(boot, app, new String[0], env));
    }

    static void gz(Path p, String contenu) throws IOException {
        try (GZIPOutputStream o = new GZIPOutputStream(Files.newOutputStream(p))) {
            o.write(contenu.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String lireGz(Path p) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            in.transferTo(b);
            return b.toString(StandardCharsets.UTF_8);
        }
    }

    static List<Path> archives(Path dossier) throws IOException {
        try (Stream<Path> s = Files.list(dossier)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".log.gz")).sorted().toList();
        }
    }

    static List<Path> attendreArchives(Path dossier, String prefixe, int nb) throws Exception {
        List<Path> a = List.of();
        for (int i = 0; i < 100; i++) {  // compression asynchrone : 10 s au plus
            a = archives(dossier).stream().filter(p -> p.getFileName().toString().startsWith(prefixe)).toList();
            try (Stream<Path> s = Files.list(dossier)) {
                boolean enCours = s.anyMatch(p -> p.getFileName().toString().contains(".tmp"));
                if (a.size() >= nb && !enCours) return a;
            }
            Thread.sleep(100);
        }
        return a;
    }

    public static void main(String[] args) throws Exception {
        Path config = Path.of(args[0]).toAbsolutePath();
        Path dossier = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(dossier);
        try (Stream<Path> s = Files.list(dossier)) {
            for (Path p : s.toList()) Files.delete(p);
        }
        LocalDate aujourdhui = LocalDate.now(ZoneId.systemDefault());
        Path tresVieille = dossier.resolve("ged." + JOUR.format(aujourdhui.minusDays(100)) + ".0.log.gz");
        Path recente = dossier.resolve("ged." + JOUR.format(aujourdhui.minusDays(80)) + ".0.log.gz");
        gz(tresVieille, "archive de J-100\n");
        gz(recente, "archive de J-80\n");

        // ---------- 1. Initialisation profil prod, configuration livrée ----------
        initialiser(config, dossier, Map.of());
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger racine = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<?> a = racine.getAppender("FICHIER");
        resultat("T-077.appender", a instanceof RollingFileAppender, "profil prod : appender FICHIER (RollingFileAppender) sur la racine",
                a == null ? "absent" : a.getClass().getSimpleName() + " console=" + (racine.getAppender("CONSOLE") != null));
        RollingFileAppender<?> fichier = (RollingFileAppender<?>) a;
        SizeAndTimeBasedRollingPolicy<?> pol = (SizeAndTimeBasedRollingPolicy<?>) fichier.getRollingPolicy();
        resultat("T-077.politique", pol.getFileNamePattern().endsWith("ged.%d{yyyy-MM-dd}.%i.log.gz")
                        && pol.getMaxHistory() == 90 && "GZ".equals(pol.getCompressionMode().name()),
                "SizeAndTimeBasedRollingPolicy : jour + index, gzip, maxHistory 90",
                "motif=" + Path.of(pol.getFileNamePattern()).getFileName() + " maxHistory=" + pol.getMaxHistory()
                        + " compression=" + pol.getCompressionMode() + " fichier=" + fichier.getFile());
        resultat("T-077.niveau-info", !ctx.getLogger("com.ipt.ged.recette").isDebugEnabled()
                        && ctx.getLogger("com.ipt.ged.recette").isInfoEnabled(),
                "INFO par défaut en production (DEBUG inactif pour com.ipt.ged)", "racine=" + racine.getLevel());
        resultat("T-077.retention-purge", !Files.exists(tresVieille), "archive de J-100 purgée au démarrage (cleanHistoryOnStart, 90 jours)",
                tresVieille.getFileName().toString());
        resultat("T-077.retention-garde", Files.exists(recente), "archive de J-80 conservée", recente.getFileName().toString());

        // ---------- 2. Écriture avec MDC, rotation par taille ----------
        org.slf4j.Logger log = LoggerFactory.getLogger("com.ipt.ged.recette.RotationJournaux");
        MDC.put("username", "rec-qa");
        MDC.put("ip", "10.1.2.3");
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c");
        MDC.put("spanId", "b7ad6b7169203331");
        String charge = "x".repeat(120);
        for (int i = 0; i < 300; i++) {
            log.info("ligne {} {}", i, charge);
        }
        log.debug("ne doit pas apparaître en INFO");
        String prefixeJour = "ged." + JOUR.format(aujourdhui) + ".";
        List<Path> parTaille = attendreArchives(dossier, prefixeJour, 3);
        resultat("T-077.rotation-taille", parTaille.size() >= 3, "rotation à la taille maximale (8 KB pour l'essai, 100 MB livré)",
                parTaille.size() + " archives : " + parTaille.stream().map(p -> p.getFileName() + "=" + p.toFile().length() + "o").toList());
        boolean gzValides = true;
        String premiere = "";
        for (Path p : parTaille) {
            try {
                String t = lireGz(p);
                if (premiere.isEmpty()) premiere = t.lines().findFirst().orElse("");
            } catch (IOException e) {
                gzValides = false;
            }
        }
        resultat("T-077.compression", gzValides && !parTaille.isEmpty(), "archives gzip valides (décompressées)", "");
        Pattern article50 = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} - \\[rec-qa\\] \\[10\\.1\\.2\\.3\\] \\[main\\] - "
                + "0af7651916cd43dd8448eb211c80319c/b7ad6b7169203331 INFO  - c\\.ipt\\.ged\\.recette\\.RotationJournaux - ligne 0 x+$");
        resultat("T-077.pattern", article50.matcher(premiere).matches(), "pattern de l'Article 50 avec MDC (username, ip, thread, traceId/spanId)",
                premiere.length() > 110 ? premiere.substring(0, 110) + "…" : premiere);
        String actif = Files.readString(dossier.resolve("ged.log"));
        resultat("T-077.debug-absent", !actif.contains("ne doit pas apparaître"), "message DEBUG non écrit en INFO", "");

        // ---------- 3. Rotation quotidienne : horloge du déclencheur avancée d'un jour ----------
        int avant = archives(dossier).size();
        TimeBasedFileNamingAndTriggeringPolicyBase<?> declencheur =
                (TimeBasedFileNamingAndTriggeringPolicyBase<?>) pol.getTimeBasedFileNamingAndTriggeringPolicy();
        long demain = aujourdhui.plusDays(1).atTime(0, 0, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        declencheur.setCurrentTime(demain);
        log.info("première ligne du lendemain");
        List<Path> apres = attendreArchives(dossier, prefixeJour, parTaille.size() + 1);
        String actifLendemain = Files.readString(dossier.resolve("ged.log"));
        resultat("T-077.rotation-jour", apres.size() == parTaille.size() + 1 && actifLendemain.lines().count() == 1
                        && actifLendemain.contains("première ligne du lendemain"),
                "rotation au changement de jour : fichier du jour archivé, ged.log repart vide",
                "archives du jour " + parTaille.size() + " -> " + apres.size() + " ; ged.log=" + actifLendemain.lines().count() + " ligne(s)");
        MDC.clear();
        LoggingSystem.get(RecetteRotationJournaux.class.getClassLoader()).cleanUp();
        ctx.stop();

        // ---------- 4. DEBUG par la configuration externalisée, sans reconstruire ----------
        Path dossier2 = dossier.resolveSibling(dossier.getFileName() + "-debug");
        Files.createDirectories(dossier2);
        try (Stream<Path> s = Files.list(dossier2)) {
            for (Path p : s.toList()) Files.delete(p);
        }
        initialiser(config, dossier2, Map.of("LOGGING_LEVEL_COM_IPT_GED", "DEBUG"));
        LoggerContext ctx2 = (LoggerContext) LoggerFactory.getILoggerFactory();
        String niveau = String.valueOf(ctx2.getLogger("com.ipt.ged").getLevel());
        org.slf4j.Logger log2 = LoggerFactory.getLogger("com.ipt.ged.recette.RotationJournaux");
        log2.debug("diagnostic ponctuel");
        LoggerFactory.getLogger("org.springframework.recette").debug("hors com.ipt.ged");
        ctx2.stop();
        String t2 = Files.readString(dossier2.resolve("ged.log"));
        resultat("T-077.debug-externe", t2.contains("DEBUG - c.ipt.ged.recette.RotationJournaux - diagnostic ponctuel")
                        && !t2.contains("hors com.ipt.ged"),
                "LOGGING_LEVEL_COM_IPT_GED=DEBUG (ged.env) : DEBUG de com.ipt.ged seul, même artefact, au redémarrage",
                "niveau com.ipt.ged=" + niveau);

        System.out.println("BILAN|T-077 journaux|ok=" + ok + "|echec=" + echec);
        System.exit(echec == 0 ? 0 : 1);
    }
}
