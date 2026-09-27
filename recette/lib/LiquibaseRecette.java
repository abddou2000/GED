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

/**
 * Lanceur Liquibase de la recette, exécuté en mode « fichier source » (java X.java).
 *
 * <p>Pourquoi pas le greffon Maven ni la CLI : le greffon tire des dépendances absentes
 * du dépôt local (liquibase-commercial…) et la CLI exige picocli, dépendance optionnelle
 * de liquibase-core. Ce lanceur n'utilise que le classpath d'exécution du backend
 * (liquibase-core et le pilote PostgreSQL que l'application embarque déjà) : la
 * recette migre avec EXACTEMENT la version de Liquibase livrée.
 *
 * <p>Usage : java -cp CLASSPATH LiquibaseRecette.java update|rollbackCount N|status
 * Paramètres par variables d'environnement : LB_URL, LB_USER, LB_PASSWORD, LB_SCHEMA,
 * LB_SCHEMA_REGISTRE, LB_CHANGELOG, LB_SEARCH_PATH (dossier des ressources).
 */
public class LiquibaseRecette {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("but attendu : update | rollbackCount N | status");
        }
        String motDePasse = System.getenv().getOrDefault("LB_PASSWORD", "");
        try (Connection cnx = DriverManager.getConnection(env("LB_URL"), env("LB_USER"), motDePasse)) {
            Database base = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(cnx));
            base.setDefaultSchemaName(env("LB_SCHEMA"));
            base.setLiquibaseSchemaName(env("LB_SCHEMA_REGISTRE"));
            try (Liquibase lb = new Liquibase(env("LB_CHANGELOG"),
                    new DirectoryResourceAccessor(Paths.get(env("LB_SEARCH_PATH"))), base)) {
                switch (args[0]) {
                    // Aucun filtre de contexte : comme Spring Boot au démarrage, tous les
                    // changesets s'appliquent, data-initial compris.
                    case "update" -> lb.update(new Contexts(), new LabelExpression());
                    case "rollbackCount" -> lb.rollback(Integer.parseInt(args[1]), (String) null);
                    case "status" -> lb.reportStatus(true, new Contexts(), new OutputStreamWriter(System.out));
                    default -> throw new IllegalArgumentException("but inconnu : " + args[0]);
                }
            }
        }
    }

    private static String env(String nom) {
        String v = System.getenv(nom);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("variable d'environnement manquante : " + nom);
        }
        return v;
    }
}
