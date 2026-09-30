import java.io.OutputStreamWriter;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.util.LiquibaseUtil;

/**
 * Substitut de la CLI Liquibase pour la recette de deployer.sh (T-092) sur un poste
 * sans CLI : même liquibase-core que celui EMBARQUÉ par le backend (classpath Maven
 * hors ligne), mêmes variables d'environnement que la CLI officielle, telles que
 * deployer.sh les pose (LIQUIBASE_COMMAND_URL, _USERNAME, _PASSWORD,
 * _CHANGELOG_FILE, _DEFAULT_SCHEMA_NAME, LIQUIBASE_LIQUIBASE_SCHEMA_NAME,
 * LIQUIBASE_SEARCH_PATH).
 *
 * <p>Buts pris en charge (ceux qu'appelle deployer.sh) : --version, validate,
 * status [--verbose], tag --tag=X, update, rollback --tag=X. Les options globales
 * (--log-level=…) sont ignorées. Code de sortie 1 et message sur stderr en cas d'échec.
 *
 * <p>Garde-fou : refuse toute URL dont la base ne commence pas par ged_qa_v8_.
 */
public class LiquibaseCli {
    public static void main(String[] args) {
        String but = null, tag = null;
        boolean verbeux = false;
        for (String a : args) {
            if (a.equals("--version")) {
                System.out.println("Liquibase Version: " + LiquibaseUtil.getBuildVersion());
                return;
            } else if (a.startsWith("--tag=")) {
                tag = a.substring("--tag=".length());
            } else if (a.equals("--verbose")) {
                verbeux = true;
            } else if (a.startsWith("--")) {
                // option globale ignorée (--log-level=WARNING…)
            } else if (but == null) {
                but = a;
            }
        }
        try {
            executer(but, tag, verbeux);
        } catch (Exception e) {
            Throwable c = e;
            StringBuilder msg = new StringBuilder();
            while (c != null) {
                msg.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
                c = c.getCause();
            }
            System.err.println("ERREUR LIQUIBASE (" + but + ") : " + msg);
            System.exit(1);
        }
    }

    private static void executer(String but, String tag, boolean verbeux) throws Exception {
        if (but == null) throw new IllegalArgumentException("but attendu");
        String url = env("LIQUIBASE_COMMAND_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/ged_qa_v8_[a-z0-9_]+(\\?.*)?")) {
            throw new IllegalStateException("URL refusée par le garde-fou de recette : " + url);
        }
        try (Connection cnx = DriverManager.getConnection(url, env("LIQUIBASE_COMMAND_USERNAME"),
                System.getenv().getOrDefault("LIQUIBASE_COMMAND_PASSWORD", ""))) {
            Database base = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(cnx));
            base.setDefaultSchemaName(env("LIQUIBASE_COMMAND_DEFAULT_SCHEMA_NAME"));
            base.setLiquibaseSchemaName(env("LIQUIBASE_LIQUIBASE_SCHEMA_NAME"));
            try (Liquibase lb = new Liquibase(env("LIQUIBASE_COMMAND_CHANGELOG_FILE"),
                    new DirectoryResourceAccessor(Paths.get(env("LIQUIBASE_SEARCH_PATH"))), base)) {
                switch (but) {
                    case "validate" -> { lb.validate(); System.out.println("No validation errors found."); }
                    case "status" -> lb.reportStatus(verbeux, new Contexts(), new OutputStreamWriter(System.out));
                    case "tag" -> { lb.tag(exiger(tag)); System.out.println("Tag « " + tag + " » posé."); }
                    case "update" -> lb.update(new Contexts(), new LabelExpression());
                    case "rollback" -> lb.rollback(exiger(tag), new Contexts(), new LabelExpression());
                    default -> throw new IllegalArgumentException("but non pris en charge : " + but);
                }
            }
        }
    }

    private static String exiger(String tag) {
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("--tag obligatoire");
        return tag;
    }

    private static String env(String nom) {
        String v = System.getenv(nom);
        if (v == null || v.isBlank()) throw new IllegalStateException("variable d'environnement manquante : " + nom);
        return v;
    }
}
