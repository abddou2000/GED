package com.ipt.ged.socle;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le schéma est entièrement produit par Liquibase, et chaque changeset sait se
 * défaire (dossier technique §4.2.1, §4.2.2 ; critère de sortie de l'étape E1).
 *
 * <p>Le test travaille dans un schéma <b>jetable</b>, créé pour l'occasion avec
 * le compte propriétaire {@code ged_owner} : la base de test partagée par les
 * autres classes n'est pas touchée, et la démonstration part d'un schéma
 * réellement vide — pas d'un schéma « déjà migré » dont on ne saurait pas ce
 * qu'il contenait avant.
 *
 * <p>Prérequis : base de test préparée avec {@code preparer-base.sql -v tests=oui}
 * (droit CREATE de ged_owner sur la base).
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaLiquibaseTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

    /** Tables du modèle à l'issue du lot E1 (jalon socle-e1). */
    private static final Set<String> TABLES_E1 = Set.of(
            "employe", "compte_utilisateur", "workflow_ged", "workflow_ged_etape", "workspace",
            "access_group", "access_group_workspace", "access_group_employe", "etiquette",
            "index_def", "plan_indexation", "plan_index", "type_document", "document",
            "version_document", "document_etiquette", "document_index_valeur",
            "workflow_ged_signature");

    /** Tables à l'issue du lot E2 (jalon identite-e2) : E1 moins les comptes à mot de passe, plus l'identité. */
    private static final Set<String> TABLES_E2;
    static {
        Set<String> t = new TreeSet<>(TABLES_E1);
        t.remove("compte_utilisateur");
        t.addAll(Set.of("role", "utilisateur", "utilisateur_role", "cache_annuaire", "session"));
        TABLES_E2 = Set.copyOf(t);
    }

    /**
     * Tables du modèle courant (lot E3, §12.1) : les espaces deviennent des
     * nœuds, les groupes d'accès des groupes GED ; les rôles globaux et les
     * rattachements groupe / espace sont repris en habilitations.
     */
    private static final Set<String> TABLES_E3;
    static {
        Set<String> t = new TreeSet<>(TABLES_E2);
        t.removeAll(Set.of("workspace", "access_group", "access_group_workspace", "access_group_employe",
                "utilisateur_role"));
        t.addAll(Set.of("noeud", "groupe_ged", "groupe_membre", "permission", "role_permission", "habilitation",
                "document_rattachement", "document_confidentiel_designe", "version_habilitations"));
        TABLES_E3 = Set.copyOf(t);
    }

    /**
     * Tables du journal d'audit (lot E4). Leur clé {@code id} est un bigint
     * SÉQUENTIEL, comme le prescrit le §7.4.1 : c'est l'ordre du scellement
     * chaîné. Les partitions mensuelles de journal_audit (journal_audit_AAAAMM)
     * dépendent de la date de migration et sont écartées des comparaisons.
     */
    private static final Set<String> TABLES_AUDIT = Set.of("journal_audit", "journal_audit_scellement");

    /** Tables de l'API d'intégration (lot E9) : clés UUID, conventions de nommage. */
    private static final Set<String> TABLES_API = Set.of("application", "cle_api", "cle_api_portee", "idempotence_cle");

    /** Notifications (DAT §12.9) : boîte d'envoi et préférence (clé : l'utilisateur). */
    private static final Set<String> TABLES_NOTIFICATION = Set.of("notification", "preference_notification");

    /** Tables ajoutées par les lots E5 (stockage chiffré) et E6 (OCR, recherche plein texte). */
    private static final Set<String> TABLES_E5_E6 = Set.of("cle_fichier", "ocr_job", "document_texte");

    /** Lot E7, cycle de vie (dev3) : copies de conservation, jobs d'archivage et d'export. */
    private static final Set<String> TABLES_E7_CYCLE_DE_VIE = Set.of(
            "copie_conservation", "job_archivage", "job_archivage_element", "job_export", "job_export_element");

    /**
     * Tables au jalon modele-e7 : E3, les lots de dev3 (E5 à E7 cycle de vie)
     * et de dev2 (audit, API, notifications), posés avant, plus le
     * méta-modèle de dev1 (plans versionnés, re-typologisation).
     */
    private static final Set<String> TABLES_E7;
    static {
        Set<String> t = new TreeSet<>(TABLES_E3);
        t.addAll(TABLES_E5_E6);
        t.addAll(TABLES_E7_CYCLE_DE_VIE);
        t.addAll(TABLES_AUDIT);
        t.addAll(TABLES_API);
        t.addAll(TABLES_NOTIFICATION);
        t.addAll(Set.of("plan_indexation_version", "job_retypage"));
        TABLES_E7 = Set.copyOf(t);
    }

    /**
     * Tables du modèle courant : E7, le rapport de reprise des liens groupe /
     * espace, et le workflow parallèle du lot E8 (règles et validateurs repris
     * de workflow_ged, circuits, décisions ; signatures séquentielles reprises).
     */
    private static final Set<String> TABLES_ATTENDUES;
    /** Tables au jalon workflow-e8 (avant l'alerte d'échéance de conservation). */
    private static final Set<String> TABLES_E8;
    static {
        Set<String> t = new TreeSet<>(TABLES_E7);
        t.add("reprise_lien_groupe_espace");
        t.removeAll(Set.of("workflow_ged", "workflow_ged_etape", "workflow_ged_signature"));
        t.addAll(Set.of("regle_workflow", "regle_validateur", "circuit", "circuit_validateur", "decision"));
        TABLES_E8 = Set.copyOf(t);
        // Alerte d'échéance (T-112) : verrou des tâches planifiées.
        t.add("verrou_tache");
        // T-025 : rapport des droits hérités des groupes (colonnes droit_* retirées).
        t.add("reprise_droits_groupe");
        // T-025, écart 2 : appartenances de personnes sans identité GED, en attente de leur première connexion.
        t.add("groupe_membre_attente");
        TABLES_ATTENDUES = Set.copyOf(t);
    }


    /**
     * Seule exception à la clé {@code id} UUID (§12.1) : {@code journal_audit}, dont
     * l'identifiant est séquentiel par exigence du §7.4.1 (« id, horodatage :
     * identifiant séquentiel »). Le scellement et la préférence de notification
     * ont une clé UUID depuis ANO-E1-005.
     */
    private static final Set<String> CLES_PARTICULIERES = Set.of("journal_audit");

    /**
     * Tables sans colonne {@code id} : aucune depuis ANO-E1-001 (§4.2.2), les
     * tables d'association ayant reçu une clé {@code id} et une contrainte uk_.
     */
    private static final Set<String> ASSOCIATIONS = Set.of();

    /** Tables à corbeille : portent l'auteur et la date de suppression. */
    private static final Set<String> A_CORBEILLE = Set.of(
            "regle_workflow", "noeud", "groupe_ged", "etiquette", "index_def",
            "plan_indexation", "type_document", "document");

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.liquibase.user}")
    private String proprietaire;

    @Value("${spring.liquibase.password:}")
    private String motDePasseProprietaire;

    @Test
    @DisplayName("Base vierge : Liquibase crée tout le schéma, conventions respectées, puis tout se défait")
    void schemaCreeParLiquibasePuisRetourArriere() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                assertTrue(tables(c, schema).isEmpty(), "le schéma jetable doit partir vide");

                // 1. Montée complète depuis un schéma vide.
                liquibase.update(new Contexts(), new LabelExpression());
                Set<String> tables = tables(c, schema);
                tables.removeAll(Set.of("databasechangelog", "databasechangeloglock"));
                assertEquals(new TreeSet<>(TABLES_ATTENDUES), tables);

                verifierClesUuid(c, schema);
                verifierConventionsDeNommage(c, schema);
                verifierSuppressionDouce(c, schema);
                verifierMetadonnees(c, schema);
                verifierAucunMotDePasse(c, schema);
                int changesets = compter(c, "SELECT count(*) FROM " + schema + ".databasechangelog");
                assertTrue(changesets >= 21, "tous les changesets doivent être enregistrés : " + changesets);

                // 2. Retour arrière de TOUS les changesets, dans l'ordre inverse :
                //    chacun porte une clause rollback qui doit s'exécuter sans erreur.
                liquibase.rollback(changesets, (String) null);
                Set<String> restantes = tables(c, schema);
                restantes.removeAll(Set.of("databasechangelog", "databasechangeloglock"));
                assertEquals(Set.of(), restantes, "après retour arrière complet, plus aucune table");
                assertEquals(0, compter(c, "SELECT count(*) FROM " + schema + ".databasechangelog"));

                // 3. Remontée : le retour arrière a laissé un schéma réutilisable.
                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(changesets, compter(c, "SELECT count(*) FROM " + schema + ".databasechangelog"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("Retour arrière par jalon : workflow-e8, modele-e7, autorisation-e3, identite-e2 puis socle-e1")
    void retourArriereAuxJalons() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(new TreeSet<>(TABLES_ATTENDUES), tablesMetier(c, schema));
                assertEquals(4, compter(c, "SELECT count(*) FROM " + schema + ".role WHERE systeme"),
                        "les quatre rôles système sont amorcés (data-initial)");
                assertEquals(18, compter(c, "SELECT count(*) FROM " + schema + ".permission"),
                        "neuf permissions élémentaires, sept d'administration, deux de confidentialité");
                assertEquals(1, compter(c, "SELECT count(*) FROM " + schema + ".version_habilitations"));

                // Retour au jalon E8 : l'alerte d'échéance (verrou de tâche, marquage) se défait.
                liquibase.rollback("workflow-e8", (String) null);
                assertEquals(new TreeSet<>(TABLES_E8), tablesMetier(c, schema));
                assertEquals(0, compter(c, "SELECT count(*) FROM information_schema.columns WHERE table_schema = '"
                        + schema + "' AND table_name = 'document' AND column_name = 'echeance_signalee_le'"));

                // Retour au jalon E7 (modèle) : rapport de reprise et workflow parallèle se défont.
                liquibase.rollback("modele-e7", (String) null);
                assertEquals(new TreeSet<>(TABLES_E7), tablesMetier(c, schema));

                // Retour au jalon E3 : plans versionnés et re-typologisation se défont.
                liquibase.rollback("autorisation-e3", (String) null);
                assertEquals(new TreeSet<>(TABLES_E3), tablesMetier(c, schema));

                // Retour au jalon E2 : nœuds et groupes reprennent leurs noms,
                // les tables d'association d'origine sont recréées.
                liquibase.rollback("identite-e2", (String) null);
                assertEquals(new TreeSet<>(TABLES_E2), tablesMetier(c, schema));

                // Retour au jalon E1 : tout le lot E2 se défait, y compris la
                // suppression de compte_utilisateur (structure recréée, vide).
                liquibase.rollback("socle-e1", (String) null);
                assertEquals(new TreeSet<>(TABLES_E1), tablesMetier(c, schema));

                // Rejouer la montée repose les cinq jalons.
                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(new TreeSet<>(TABLES_ATTENDUES), tablesMetier(c, schema));
                assertEquals(5, compter(c, "SELECT count(*) FROM " + schema
                        + ".databasechangelog WHERE tag IN ('socle-e1', 'identite-e2', 'autorisation-e3', 'modele-e7',"
                        + " 'workflow-e8')"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("Point 9 (E7) : les liens groupe / espace d'avant E3 finissent au rapport de reprise, jamais en habilitations")
    void liensGroupeEspaceAuRapport() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                liquibase.update("identite-e2", new Contexts(), new LabelExpression());
                // État E2 : un Administrateur de portée globale, membre d'un groupe qui « couvre » un espace.
                String s = schema + ".";
                executer(c, "INSERT INTO " + s + "employe (id, first_name, last_name, has_user) VALUES"
                        + " ('01920000-0000-7000-8000-00000000e001', 'Sara', 'Bennani', true)");
                executer(c, "INSERT INTO " + s + "workflow_ged (id, name) VALUES ('01920000-0000-7000-8000-00000000f001', 'WF')");
                executer(c, "INSERT INTO " + s + "workspace (id, name, code, status, employe_id, workflow_ged_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000a001', 'Compta', 'WS-C', 'ACTIF',"
                        + " '01920000-0000-7000-8000-00000000e001', '01920000-0000-7000-8000-00000000f001')");
                executer(c, "INSERT INTO " + s + "access_group (id, code, name) VALUES"
                        + " ('01920000-0000-7000-8000-00000000b001', 'AG-ADMIN', 'Administrateurs')");
                executer(c, "INSERT INTO " + s + "access_group_employe (access_group_id, employe_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000b001', '01920000-0000-7000-8000-00000000e001')");
                executer(c, "INSERT INTO " + s + "access_group_workspace (access_group_id, workspace_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000b001', '01920000-0000-7000-8000-00000000a001')");
                executer(c, "INSERT INTO " + s + "utilisateur (id, object_guid, identifiant, employe_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000c001', '01920000-0000-7000-8000-00000000c0aa', 'sbennani',"
                        + " '01920000-0000-7000-8000-00000000e001')");
                executer(c, "INSERT INTO " + s + "utilisateur_role (utilisateur_id, role_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000c001', '0192a000-0000-7000-8000-000000000001')");

                liquibase.update(new Contexts(), new LabelExpression());

                assertEquals(0, compter(c, "SELECT count(*) FROM " + s + "habilitation WHERE sujet_type = 'GROUPE'"),
                        "aucune habilitation de groupe issue de la reprise");
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "reprise_lien_groupe_espace"
                        + " WHERE groupe_ged_id = '01920000-0000-7000-8000-00000000b001'"
                        + " AND noeud_id = '01920000-0000-7000-8000-00000000a001'"));
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "habilitation WHERE sujet_type = 'UTILISATEUR'"
                        + " AND noeud_id IS NULL AND role_id = '0192a000-0000-7000-8000-000000000001'"),
                        "le rôle global de l'Administrateur est intact");
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "groupe_membre"), "groupes et membres conservés");

                // Retour au jalon modele-e7 : les habilitations reviennent, le rapport disparaît.
                liquibase.rollback("modele-e7", (String) null);
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "habilitation WHERE sujet_type = 'GROUPE'"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("ANO-E1-006 : montée de version sur une base peuplée qui contient un document archivé")
    void monteeAvecDocumentArchive() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                // État d'une base où l'archivage de dev3 a servi, avant la numérotation des versions (lot modèle).
                List<liquibase.changelog.ChangeSet> aJouer = liquibase.listUnrunChangeSets(new Contexts(),
                        new LabelExpression());
                int avant = 0;
                while (!aJouer.get(avant).getId().equals("202609301049-1")) avant++;
                liquibase.update(avant, new Contexts(), new LabelExpression());
                String s = schema + ".";
                executer(c, "INSERT INTO " + s + "employe (id, first_name, last_name) VALUES"
                        + " ('01920000-0000-7000-8000-00000000e001', 'Karim', 'El Fassi')");
                executer(c, "INSERT INTO " + s + "workflow_ged (id, name) VALUES ('01920000-0000-7000-8000-00000000f001', 'WF')");
                executer(c, "INSERT INTO " + s + "noeud (id, name, code, status, employe_id, workflow_ged_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000a001', 'Compta', 'WS-C', 'ACTIF',"
                        + " '01920000-0000-7000-8000-00000000e001', '01920000-0000-7000-8000-00000000f001')");
                executer(c, "INSERT INTO " + s + "type_document (id, code, type_de_document, description, noeud_id,"
                        + " type_autorise, taille_max_mo) VALUES ('01920000-0000-7000-8000-00000000d001', 'TD', 'Facture',"
                        + " 'd', '01920000-0000-7000-8000-00000000a001', 'pdf', 5)");
                executer(c, "INSERT INTO " + s + "document (id, name, noeud_principal_id, type_document_id)"
                        + " VALUES ('01920000-0000-7000-8000-00000000b001', 'Facture archivée',"
                        + " '01920000-0000-7000-8000-00000000a001', '01920000-0000-7000-8000-00000000d001')");
                executer(c, "INSERT INTO " + s + "version_document (id, document_id, file_name, file_path, is_default)"
                        + " VALUES ('01920000-0000-7000-8000-00000000b101', '01920000-0000-7000-8000-00000000b001',"
                        + " 'v1.pdf', 'x/v1.pdf', false),"
                        + " ('01920000-0000-7000-8000-00000000b102', '01920000-0000-7000-8000-00000000b001',"
                        + " 'v2.pdf', 'x/v2.pdf', true)");
                executer(c, "UPDATE " + s + "document SET statut_conservation = 'ARCHIVE', archive_le = now()"
                        + " WHERE id = '01920000-0000-7000-8000-00000000b001'");
                if (!c.getAutoCommit()) c.commit();

                // Toute la suite de la montée passe (avant la correction : refus du gel sur 202609301050-1).
                liquibase.update(new Contexts(), new LabelExpression());

                assertEquals(2, compter(c, "SELECT count(*) FROM " + s + "version_document"
                        + " WHERE document_id = '01920000-0000-7000-8000-00000000b001' AND numero IN (1, 2)"));
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "version_document"
                        + " WHERE id = '01920000-0000-7000-8000-00000000b102' AND courante"));
                // Le gel est rétabli : une version d'un document archivé reste intouchable.
                assertEquals("O", texte(c, "SELECT tgenabled::text FROM pg_trigger WHERE tgname = 'trg_version_document_archive'"
                        + " AND tgrelid = '" + s + "version_document'::regclass"));
                SQLException refus = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> executer(c,
                        "INSERT INTO " + s + "version_document (id, document_id, file_name, numero) VALUES"
                                + " ('01920000-0000-7000-8000-00000000b103', '01920000-0000-7000-8000-00000000b001', 'v3.pdf', 3)"));
                assertTrue(refus.getMessage().contains("archivé"), refus.getMessage());
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("ANO-E8-003 : retour arrière de la reprise des signatures refusé s'il perd des données, sauf décision explicite")
    void retourArriereRepriseAvecPerte() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                liquibase.update(new Contexts(), new LabelExpression());
                String s = schema + ".";
                executer(c, "INSERT INTO " + s + "employe (id, first_name, last_name) VALUES"
                        + " ('01920000-0000-7000-8000-00000000e001', 'Karim', 'El Fassi')");
                executer(c, "INSERT INTO " + s + "regle_workflow (id, nom) VALUES ('01920000-0000-7000-8000-00000000f001', 'WF')");
                executer(c, "INSERT INTO " + s + "noeud (id, nom, code, status, employe_id, regle_workflow_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000a001', 'Compta', 'WS-C', 'ACTIF',"
                        + " '01920000-0000-7000-8000-00000000e001', '01920000-0000-7000-8000-00000000f001')");
                executer(c, "INSERT INTO " + s + "type_document (id, code, type_de_document, description, noeud_id,"
                        + " type_autorise, taille_max_mo) VALUES ('01920000-0000-7000-8000-00000000d001', 'TD', 'Facture',"
                        + " 'd', '01920000-0000-7000-8000-00000000a001', 'pdf', 5)");
                executer(c, "INSERT INTO " + s + "document (id, name, noeud_principal_id, type_document_id)"
                        + " VALUES ('01920000-0000-7000-8000-00000000b001', 'Facture',"
                        + " '01920000-0000-7000-8000-00000000a001', '01920000-0000-7000-8000-00000000d001')");
                // Un circuit annulé : l'ancien modèle ne sait pas le représenter.
                executer(c, "INSERT INTO " + s + "circuit (id, document_id, statut, annule_le, motif_annulation) VALUES"
                        + " ('01920000-0000-7000-8000-00000000c001', '01920000-0000-7000-8000-00000000b001', 'ANNULE',"
                        + " now(), 'Document remplacé')");
                // Liquibase laisse la connexion hors auto-validation : les données du test sont validées
                // avant le retour arrière, comme celles d'une base en service.
                if (!c.getAutoCommit()) c.commit();

                Exception refus = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                        () -> liquibase.rollback("modele-e7", (String) null));
                assertTrue(causes(refus).contains("1 circuit(s) annulé(s)"), causes(refus));
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "circuit"), "rien n'est perdu");

                // Décision explicite de l'exploitant : le retour arrière passe, avec la perte annoncée.
                // Paramètre de session (en exploitation : options=-c … dans l'URL de la CLI) ; un SET
                // est transactionnel, il est validé avant que Liquibase n'ouvre ses transactions.
                executer(c, "SET ged.retour_arriere_avec_perte = 'oui'");
                if (!c.getAutoCommit()) c.commit();
                liquibase.rollback("modele-e7", (String) null);
                assertEquals(new TreeSet<>(TABLES_E7), tablesMetier(c, schema));
            } finally {
                executer(c, "RESET ged.retour_arriere_avec_perte");
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("ANO-E8-004 : au-delà de workflow-e8, diffusions et signalements d'échéance ne se perdent pas sans décision")
    void retourArriereApresJalonWorkflowSansPerte() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                liquibase.update(new Contexts(), new LabelExpression());
                String s = schema + ".";
                executer(c, "INSERT INTO " + s + "employe (id, first_name, last_name) VALUES"
                        + " ('01920000-0000-7000-8000-00000000e001', 'Karim', 'El Fassi')");
                executer(c, "INSERT INTO " + s + "utilisateur (id, object_guid, identifiant, employe_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000c001', '01920000-0000-7000-8000-00000000c0aa', 'kelfassi',"
                        + " '01920000-0000-7000-8000-00000000e001')");
                executer(c, "INSERT INTO " + s + "noeud (id, nom, code, status, employe_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000a001', 'Compta', 'WS-C', 'ACTIF',"
                        + " '01920000-0000-7000-8000-00000000e001')");
                executer(c, "INSERT INTO " + s + "type_document (id, code, type_de_document, description, noeud_id,"
                        + " type_autorise, taille_max_mo) VALUES ('01920000-0000-7000-8000-00000000d001', 'TD', 'Facture',"
                        + " 'd', '01920000-0000-7000-8000-00000000a001', 'pdf', 5)");
                executer(c, "INSERT INTO " + s + "document (id, name, noeud_principal_id, type_document_id)"
                        + " VALUES ('01920000-0000-7000-8000-00000000b001', 'Facture',"
                        + " '01920000-0000-7000-8000-00000000a001', '01920000-0000-7000-8000-00000000d001')");
                String sansRegle = "SELECT count(*) FROM " + s + "noeud WHERE regle_workflow_id IS NULL";
                // Une diffusion (habilitation du rôle Lecteur sur le document) et un signalement d'échéance.
                executer(c, "INSERT INTO " + s + "habilitation (id, sujet_type, utilisateur_id, role_id, document_id)"
                        + " VALUES ('01920000-0000-7000-8000-00000000f101', 'UTILISATEUR',"
                        + " '01920000-0000-7000-8000-00000000c001', '0192a000-0000-7000-8000-000000000005',"
                        + " '01920000-0000-7000-8000-00000000b001')");
                executer(c, "UPDATE " + s + "document SET echeance_signalee_le = now()"
                        + " WHERE id = '01920000-0000-7000-8000-00000000b001'");
                if (!c.getAutoCommit()) c.commit();
                String diffusions = "SELECT count(*) FROM " + s + "habilitation WHERE role_id = '0192a000-0000-7000-8000-000000000005'";
                String signales = "SELECT count(*) FROM " + s + "document WHERE echeance_signalee_le IS NOT NULL";

                // 1. Refus au premier changeset destructeur défait (202610031000-1), avant toute perte.
                Exception refus = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                        () -> liquibase.rollback("modele-e7", (String) null));
                assertTrue(causes(refus).contains("1 marque(s) de signalement"), causes(refus));
                assertEquals(1, compter(c, signales));
                assertEquals(1, compter(c, diffusions));
                if (!c.getAutoCommit()) c.rollback();

                // 2. Sans signalement : refus au rôle Lecteur (202610021130), la diffusion reste.
                executer(c, "UPDATE " + s + "document SET echeance_signalee_le = NULL");
                if (!c.getAutoCommit()) c.commit();
                refus = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                        () -> liquibase.rollback("modele-e7", (String) null));
                assertTrue(causes(refus).contains("1 habilitation(s) du rôle"), causes(refus));
                assertEquals(1, compter(c, diffusions), "la diffusion n'est pas supprimée");
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "role WHERE code = 'LECTEUR'"));
                if (!c.getAutoCommit()) c.rollback();

                // 3. Sans diffusion : refus aux règles (202610021100-2), le nœud sans règle n'en reçoit
                //    pas une au hasard ; sans aucune règle, impossible même sur décision explicite.
                executer(c, "DELETE FROM " + s + "habilitation WHERE role_id = '0192a000-0000-7000-8000-000000000005'");
                if (!c.getAutoCommit()) c.commit();
                refus = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                        () -> liquibase.rollback("modele-e7", (String) null));
                assertTrue(causes(refus).contains("règle arbitraire à 1 nœud(s)"), causes(refus));
                assertEquals(1, compter(c, sansRegle));
                if (!c.getAutoCommit()) c.rollback();

                // 4. Remontée possible depuis cet état intermédiaire (rien de perdu), puis décision explicite.
                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(new TreeSet<>(TABLES_ATTENDUES), tablesMetier(c, schema));
                executer(c, "SET ged.retour_arriere_avec_perte = 'oui'");
                if (!c.getAutoCommit()) c.commit();
                refus = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                        () -> liquibase.rollback("modele-e7", (String) null));
                assertTrue(causes(refus).contains("aucune règle à leur attribuer"), causes(refus));
                if (!c.getAutoCommit()) c.rollback();
                // Les changesets T-025 (colonnes « nom ») sont déjà défaits : la colonne s'appelle de nouveau name.
                executer(c, "INSERT INTO " + s + "regle_workflow (id, name) VALUES ('01920000-0000-7000-8000-00000000f001', 'WF')");
                if (!c.getAutoCommit()) c.commit();
                liquibase.rollback("modele-e7", (String) null);
                assertEquals(new TreeSet<>(TABLES_E7), tablesMetier(c, schema));
            } finally {
                executer(c, "RESET ged.retour_arriere_avec_perte");
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("ANO-F-001 : l'Agent d'archive reçoit Valider, Diffuser, Purger ; le retour arrière épargne un ajout fait à l'écran")
    void compositionAgentArchive() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                List<liquibase.changelog.ChangeSet> aJouer = liquibase.listUnrunChangeSets(new Contexts(),
                        new LabelExpression());
                int avant = 0;
                while (!aJouer.get(avant).getId().equals("202610041000-1")) avant++;
                liquibase.update(avant, new Contexts(), new LabelExpression());
                String s = schema + ".";
                String agent = "'0192a000-0000-7000-8000-000000000002'";
                String composition = "SELECT count(*) FROM " + s + "role_permission rp JOIN " + s
                        + "permission p ON p.id = rp.permission_id WHERE rp.role_id = " + agent;
                assertEquals(7, compter(c, composition), "composition livrée avant le correctif");
                // Un Administrateur a déjà ajouté Valider depuis l'écran des rôles.
                executer(c, "INSERT INTO " + s + "role_permission (role_id, permission_id) VALUES (" + agent
                        + ", '0192b000-0000-7000-8000-000000000004')");
                if (!c.getAutoCommit()) c.commit();

                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(10, compter(c, composition), "neuf permissions élémentaires et Voir privé");
                assertEquals(3, compter(c, composition + " AND p.code IN ('VALIDER', 'DIFFUSER', 'PURGER')"));

                // Retour arrière jusqu'à ce changeset inclus : Diffuser et Purger partent, Valider
                // (posé à l'écran) reste.
                int apres = compter(c, "SELECT count(*) FROM " + s + "databasechangelog WHERE orderexecuted >="
                        + " (SELECT orderexecuted FROM " + s + "databasechangelog WHERE id = '202610041000-1')");
                liquibase.rollback(apres, (String) null);
                assertEquals(8, compter(c, composition));
                assertEquals(1, compter(c, composition + " AND p.code = 'VALIDER'"));
                assertEquals(0, compter(c, composition + " AND p.code IN ('DIFFUSER', 'PURGER')"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("ANO-F-002 : Direction Générale sans Déplacer, Archiver ni Supprimer ; retour arrière qui rétablit la composition livrée")
    void compositionDirectionGenerale() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                List<liquibase.changelog.ChangeSet> aJouer = liquibase.listUnrunChangeSets(new Contexts(),
                        new LabelExpression());
                int avant = 0;
                while (!aJouer.get(avant).getId().equals("202610071000-1")) avant++;
                liquibase.update(avant, new Contexts(), new LabelExpression());
                String s = schema + ".";
                String dg = "'0192a000-0000-7000-8000-000000000003'";
                String composition = "SELECT count(*) FROM " + s + "role_permission rp JOIN " + s
                        + "permission p ON p.id = rp.permission_id WHERE rp.role_id = " + dg;
                String structuration = composition + " AND p.code IN ('DEPLACER', 'ARCHIVER', 'SUPPRIMER')";
                assertEquals(11, compter(c, composition), "composition livrée avant le correctif");
                assertEquals(3, compter(c, structuration));
                int version = compter(c, "SELECT valeur::int FROM " + s + "version_habilitations");
                if (!c.getAutoCommit()) c.commit();

                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(0, compter(c, structuration), "plus aucune permission de structuration");
                assertEquals(8, compter(c, composition));
                assertEquals(8, compter(c, composition + " AND p.code IN ('CONSULTER', 'DEPOSER', 'MODIFIER',"
                        + " 'VALIDER', 'DIFFUSER', 'VOIR_PRIVE', 'VOIR_CONFIDENTIEL', 'CONSULTER_AUDIT')"));
                assertTrue(compter(c, "SELECT valeur::int FROM " + s + "version_habilitations") > version,
                        "les droits en cache sont invalidés");
                // Les autres rôles système sont intacts.
                assertEquals(10, compter(c, "SELECT count(*) FROM " + s + "role_permission WHERE role_id ="
                        + " '0192a000-0000-7000-8000-000000000002'"));

                // Retour arrière jusqu'à ce changeset inclus : la composition livrée revient.
                int apres = compter(c, "SELECT count(*) FROM " + s + "databasechangelog WHERE orderexecuted >="
                        + " (SELECT orderexecuted FROM " + s + "databasechangelog WHERE id = '202610071000-1')");
                liquibase.rollback(apres, (String) null);
                assertEquals(11, compter(c, composition));
                assertEquals(3, compter(c, structuration));

                // Nouvelle montée : de nouveau sans structuration.
                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(0, compter(c, structuration));
                assertEquals(8, compter(c, composition));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("T-025 : droits hérités des groupes au rapport de reprise, colonnes « nom » ; retour arrière sans perte")
    void modeleDeReferenceGroupesEtNoms() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                List<liquibase.changelog.ChangeSet> aJouer = liquibase.listUnrunChangeSets(new Contexts(),
                        new LabelExpression());
                int avant = 0;
                while (!aJouer.get(avant).getId().equals("202610041010-1")) avant++;
                liquibase.update(avant, new Contexts(), new LabelExpression());
                String s = schema + ".";
                // Un groupe repris avec des droits hérités, un autre sans (valeurs par défaut).
                executer(c, "INSERT INTO " + s + "groupe_ged (id, code, name, droit_lecture, droit_deplacer) VALUES"
                        + " ('01920000-0000-7000-8000-0000000fa001', 'AG-A', 'Archivistes', true, true)");
                executer(c, "INSERT INTO " + s + "groupe_ged (id, code, name) VALUES"
                        + " ('01920000-0000-7000-8000-0000000fa002', 'AG-B', 'Lecteurs')");
                executer(c, "INSERT INTO " + s + "regle_workflow (id, name) VALUES ('01920000-0000-7000-8000-0000000fa003', 'Visa')");
                if (!c.getAutoCommit()) c.commit();

                liquibase.update(new Contexts(), new LabelExpression());
                String colonnes = "SELECT string_agg(table_name || '.' || column_name, ',' ORDER BY table_name, column_name)"
                        + " FROM information_schema.columns WHERE table_schema = '" + schema + "'"
                        + " AND table_name IN ('groupe_ged', 'noeud', 'regle_workflow')"
                        + " AND (column_name IN ('name', 'nom') OR column_name LIKE 'droit\\_%')";
                assertEquals("groupe_ged.nom,noeud.nom,regle_workflow.nom", texte(c, colonnes));
                assertEquals("{droit_lecture,droit_deplacer}", texte(c, "SELECT droits::text FROM " + s
                        + "reprise_droits_groupe WHERE groupe_ged_id = '01920000-0000-7000-8000-0000000fa001'"));
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "reprise_droits_groupe"));
                assertEquals("Archivistes", texte(c, "SELECT nom FROM " + s + "groupe_ged WHERE code = 'AG-A'"));
                assertEquals(1, compter(c, "SELECT count(*) FROM pg_constraint WHERE conname = 'uk_groupe_ged_nom'"
                        + " AND connamespace = '" + schema + "'::regnamespace"));
                verifierConventionsDeNommage(c, schema);

                // Retour arrière des deux changesets (et de ceux qui les suivent dans le changelog
                // maître) : colonnes et valeurs d'origine, rapport retiré.
                int apres = compter(c, "SELECT count(*) FROM " + s + "databasechangelog WHERE orderexecuted >="
                        + " (SELECT orderexecuted FROM " + s + "databasechangelog WHERE id = '202610041010-1')");
                liquibase.rollback(apres, (String) null);
                assertEquals("groupe_ged.droit_access,groupe_ged.droit_ajouter_version,groupe_ged.droit_deplacer,"
                        + "groupe_ged.droit_lecture,groupe_ged.droit_modifier,groupe_ged.droit_supprimer,"
                        + "groupe_ged.droit_uploader,groupe_ged.droit_verrouiller_deverrouiller,groupe_ged.name,"
                        + "noeud.name,regle_workflow.name", texte(c, colonnes));
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "groupe_ged WHERE code = 'AG-A' AND droit_lecture"
                        + " AND droit_deplacer AND NOT (droit_access OR droit_modifier OR droit_uploader OR droit_supprimer"
                        + " OR droit_ajouter_version OR droit_verrouiller_deverrouiller)"));
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "groupe_ged WHERE code = 'AG-B' AND NOT"
                        + " (droit_access OR droit_lecture OR droit_modifier OR droit_uploader OR droit_supprimer"
                        + " OR droit_deplacer OR droit_ajouter_version OR droit_verrouiller_deverrouiller)"));
                assertEquals("Visa", texte(c, "SELECT name FROM " + s + "regle_workflow"));
                assertEquals(0, compter(c, "SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                        + schema + "' AND table_name = 'reprise_droits_groupe'"));
                assertEquals(1, compter(c, "SELECT count(*) FROM pg_constraint WHERE conname = 'uk_groupe_ged_name'"
                        + " AND connamespace = '" + schema + "'::regnamespace"));

                // Remontée : même état qu'avant le retour arrière.
                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals("groupe_ged.nom,noeud.nom,regle_workflow.nom", texte(c, colonnes));
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "reprise_droits_groupe"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("T-025 écart 2 : groupe_membre désigne l'identité GED ; sans identité, en attente ; retour arrière et remontée sans perte")
    void groupeMembreParIdentite() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                List<liquibase.changelog.ChangeSet> aJouer = liquibase.listUnrunChangeSets(new Contexts(),
                        new LabelExpression());
                int avant = 0;
                while (!aJouer.get(avant).getId().equals("202610061000-1")) avant++;
                liquibase.update(avant, new Contexts(), new LabelExpression());
                String s = schema + ".";
                // Sara s'est connectée (identité) ; Karim jamais (fiche reprise seule).
                executer(c, "INSERT INTO " + s + "employe (id, first_name, last_name) VALUES"
                        + " ('01920000-0000-7000-8000-0000000fe001', 'Sara', 'Bennani'),"
                        + " ('01920000-0000-7000-8000-0000000fe002', 'Karim', 'El Fassi')");
                executer(c, "INSERT INTO " + s + "utilisateur (id, object_guid, identifiant, employe_id) VALUES"
                        + " ('01920000-0000-7000-8000-0000000fc001', '01920000-0000-7000-8000-0000000fc0aa', 'sbennani',"
                        + " '01920000-0000-7000-8000-0000000fe001')");
                executer(c, "INSERT INTO " + s + "groupe_ged (id, code, nom) VALUES"
                        + " ('01920000-0000-7000-8000-0000000fa001', 'AG-A', 'Archivistes'),"
                        + " ('01920000-0000-7000-8000-0000000fa002', 'AG-B', 'Lecteurs')");
                executer(c, "INSERT INTO " + s + "groupe_membre (id, groupe_ged_id, employe_id) VALUES"
                        + " ('01920000-0000-7000-8000-0000000fb001', '01920000-0000-7000-8000-0000000fa001', '01920000-0000-7000-8000-0000000fe001'),"
                        + " ('01920000-0000-7000-8000-0000000fb002', '01920000-0000-7000-8000-0000000fa001', '01920000-0000-7000-8000-0000000fe002'),"
                        + " ('01920000-0000-7000-8000-0000000fb003', '01920000-0000-7000-8000-0000000fa002', '01920000-0000-7000-8000-0000000fe002')");
                if (!c.getAutoCommit()) c.commit();
                String origine = "SELECT string_agg(id || ':' || groupe_ged_id || ':' || employe_id, ',' ORDER BY id) FROM "
                        + s + "groupe_membre";
                String avantMontee = texte(c, origine);

                // 1. Montée des deux changesets seuls (les évolutions suivantes ne sont pas l'objet du test) :
                // l'identité de Sara est membre, les appartenances de Karim attendent.
                liquibase.update(2, new Contexts(), new LabelExpression());
                String colonnes = "SELECT string_agg(column_name || ':' || is_nullable, ',' ORDER BY column_name)"
                        + " FROM information_schema.columns WHERE table_schema = '" + schema + "' AND table_name = ?";
                assertEquals("groupe_ged_id:NO,id:NO,utilisateur_id:NO", texte(c, colonnes, "groupe_membre"));
                assertEquals("employe_id:NO,groupe_ged_id:NO,id:NO", texte(c, colonnes, "groupe_membre_attente"));
                String membres = "SELECT string_agg(id || ':' || groupe_ged_id || ':' || utilisateur_id, ',' ORDER BY id) FROM "
                        + s + "groupe_membre";
                String attente = "SELECT string_agg(id || ':' || groupe_ged_id || ':' || employe_id, ',' ORDER BY id) FROM "
                        + s + "groupe_membre_attente";
                assertEquals("01920000-0000-7000-8000-0000000fb001:01920000-0000-7000-8000-0000000fa001:"
                        + "01920000-0000-7000-8000-0000000fc001", texte(c, membres));
                assertEquals("01920000-0000-7000-8000-0000000fb002:01920000-0000-7000-8000-0000000fa001:"
                        + "01920000-0000-7000-8000-0000000fe002,01920000-0000-7000-8000-0000000fb003:"
                        + "01920000-0000-7000-8000-0000000fa002:01920000-0000-7000-8000-0000000fe002", texte(c, attente));
                for (String contrainte : List.of("uk_groupe_membre_groupe_ged_id_utilisateur_id", "fk_groupe_membre_utilisateur",
                        "uk_groupe_membre_attente_groupe_ged_id_employe_id", "fk_groupe_membre_attente_employe",
                        "fk_groupe_membre_attente_groupe_ged")) {
                    assertEquals(1, compter(c, "SELECT count(*) FROM pg_constraint WHERE conname = '" + contrainte
                            + "' AND connamespace = '" + schema + "'::regnamespace"), contrainte);
                }
                verifierConventionsDeNommage(c, schema);
                // Une personne sans identité ne peut pas être membre directement.
                Exception refus = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> executer(c,
                        "INSERT INTO " + s + "groupe_membre (groupe_ged_id) VALUES ('01920000-0000-7000-8000-0000000fa002')"));
                assertTrue(causes(refus).contains("utilisateur_id"), causes(refus));
                if (!c.getAutoCommit()) c.rollback();
                // Activité après la montée : Sara rejoint AG-B, une appartenance est préparée pour Karim.
                executer(c, "INSERT INTO " + s + "groupe_ged (id, code, nom) VALUES"
                        + " ('01920000-0000-7000-8000-0000000fa003', 'AG-C', 'Courrier')");
                executer(c, "INSERT INTO " + s + "groupe_membre (id, groupe_ged_id, utilisateur_id) VALUES"
                        + " ('01920000-0000-7000-8000-0000000fb004', '01920000-0000-7000-8000-0000000fa002', '01920000-0000-7000-8000-0000000fc001')");
                executer(c, "INSERT INTO " + s + "groupe_membre_attente (id, groupe_ged_id, employe_id) VALUES"
                        + " ('01920000-0000-7000-8000-0000000fb005', '01920000-0000-7000-8000-0000000fa003', '01920000-0000-7000-8000-0000000fe002')");
                if (!c.getAutoCommit()) c.commit();
                String membresApres = texte(c, membres);
                String attenteApres = texte(c, attente);

                // 2. Retour arrière des deux changesets : employe_id reconstitué, attente réintégrée, rien de perdu.
                int apres = compter(c, "SELECT count(*) FROM " + s + "databasechangelog WHERE orderexecuted >="
                        + " (SELECT orderexecuted FROM " + s + "databasechangelog WHERE id = '202610061000-1')");
                assertEquals(2, apres);
                liquibase.rollback(apres, (String) null);
                assertEquals("employe_id:NO,groupe_ged_id:NO,id:NO", texte(c, colonnes, "groupe_membre"));
                assertEquals(0, compter(c, "SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                        + schema + "' AND table_name = 'groupe_membre_attente'"));
                assertEquals(avantMontee + ",01920000-0000-7000-8000-0000000fb004:01920000-0000-7000-8000-0000000fa002:"
                        + "01920000-0000-7000-8000-0000000fe001,01920000-0000-7000-8000-0000000fb005:"
                        + "01920000-0000-7000-8000-0000000fa003:01920000-0000-7000-8000-0000000fe002", texte(c, origine));
                for (String contrainte : List.of("uk_groupe_membre_groupe_ged_id_employe_id", "fk_groupe_membre_employe")) {
                    assertEquals(1, compter(c, "SELECT count(*) FROM pg_constraint WHERE conname = '" + contrainte
                            + "' AND connamespace = '" + schema + "'::regnamespace"), contrainte);
                }
                assertEquals(1, compter(c, "SELECT count(*) FROM pg_indexes WHERE schemaname = '" + schema
                        + "' AND indexname = 'idx_groupe_membre_employe_id'"));
                verifierConventionsDeNommage(c, schema);

                // 3. Remontée sur la base peuplée : exactement le même état qu'avant le retour arrière.
                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(membresApres, texte(c, membres));
                assertEquals(attenteApres, texte(c, attente));
                assertEquals("groupe_ged_id:NO,id:NO,utilisateur_id:NO", texte(c, colonnes, "groupe_membre"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("ANO-E7-007 : meta_date sans bloc EXCEPTION ; retour arrière vers les corps d'origine, index d'expression conservé")
    void metaDateSansSousTransaction() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                liquibase.update(new Contexts(), new LabelExpression());
                String s = schema + ".";
                String corps = "SELECT string_agg(proname || '=' || (prosrc ~* '\\mexception\\s+when\\M')::text, ' '"
                        + " ORDER BY proname) FROM pg_proc WHERE pronamespace = '" + schema + "'::regnamespace"
                        + " AND proname IN ('meta_date', 'meta_nombre')";
                assertEquals("meta_date=false meta_nombre=false", texte(c, corps));
                // Un index d'expression posé par l'exploitation (DEPLOIEMENT.md §8) dépend de la fonction.
                executer(c, "CREATE INDEX idx_verif_meta_date ON " + s + "document (" + s
                        + "meta_date(metadonnees, 'DATE_FACTURE'))");
                if (!c.getAutoCommit()) c.commit();

                int apres = compter(c, "SELECT count(*) FROM " + s + "databasechangelog WHERE orderexecuted >="
                        + " (SELECT orderexecuted FROM " + s + "databasechangelog WHERE id = '202610051000-1')");
                liquibase.rollback(apres, (String) null);
                assertEquals("meta_date=true meta_nombre=false", texte(c, corps), "corps d'origine rétablis");
                assertEquals(1, compter(c, "SELECT count(*) FROM pg_indexes WHERE schemaname = '" + schema
                        + "' AND indexname = 'idx_verif_meta_date'"), "l'index d'expression survit au retour arrière");

                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals("meta_date=false meta_nombre=false", texte(c, corps));
                assertEquals("2024-02-29 null", texte(c, "SELECT " + s + "meta_date('{\"d\":\"2024-02-29\"}', 'd') || ' '"
                        + " || coalesce(" + s + "meta_date('{\"d\":\"2023-02-29\"}', 'd')::text, 'null')"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    private static String causes(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause()) b.append(x.getMessage()).append(" | ");
        return b.toString();
    }

    @Test
    @DisplayName("E8 : les signatures séquentielles deviennent un circuit, ses validateurs et ses décisions ; retour arrière fidèle")
    void signaturesRepriseEnCircuits() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                liquibase.update("modele-e7", new Contexts(), new LabelExpression());
                // État E7 : un document, sa version courante, deux étapes (l'une signée, l'autre en attente).
                String s = schema + ".";
                executer(c, "INSERT INTO " + s + "employe (id, first_name, last_name, has_user) VALUES"
                        + " ('01920000-0000-7000-8000-00000000e001', 'Karim', 'El Fassi', true),"
                        + " ('01920000-0000-7000-8000-00000000e002', 'Yasmine', 'Alaoui', true)");
                executer(c, "INSERT INTO " + s + "workflow_ged (id, name) VALUES ('01920000-0000-7000-8000-00000000f001', 'WF')");
                executer(c, "INSERT INTO " + s + "noeud (id, name, code, status, employe_id, workflow_ged_id) VALUES"
                        + " ('01920000-0000-7000-8000-00000000a001', 'Compta', 'WS-C', 'ACTIF',"
                        + " '01920000-0000-7000-8000-00000000e001', '01920000-0000-7000-8000-00000000f001')");
                executer(c, "INSERT INTO " + s + "type_document (id, code, type_de_document, description, noeud_id,"
                        + " type_autorise, taille_max_mo) VALUES ('01920000-0000-7000-8000-00000000d001', 'TD', 'Facture',"
                        + " 'd', '01920000-0000-7000-8000-00000000a001', 'pdf', 5)");
                executer(c, "INSERT INTO " + s + "document (id, name, noeud_principal_id, type_document_id, active)"
                        + " VALUES ('01920000-0000-7000-8000-00000000b001', 'Facture 1',"
                        + " '01920000-0000-7000-8000-00000000a001', '01920000-0000-7000-8000-00000000d001', false)");
                executer(c, "INSERT INTO " + s + "version_document (id, document_id, file_name, file_path, courante, numero)"
                        + " VALUES ('01920000-0000-7000-8000-00000000b101', '01920000-0000-7000-8000-00000000b001',"
                        + " 'f.pdf', 'x/f.pdf', true, 1)");
                executer(c, "INSERT INTO " + s + "workflow_ged_signature (id, document_id, employe_id, step_label,"
                        + " step_order, status, signed_at, motif) VALUES"
                        + " ('01920000-0000-7000-8000-00000000c001', '01920000-0000-7000-8000-00000000b001',"
                        + " '01920000-0000-7000-8000-00000000e001', 'Comptable', 1, 'SIGNED', now(), 'ok'),"
                        + " ('01920000-0000-7000-8000-00000000c002', '01920000-0000-7000-8000-00000000b001',"
                        + " '01920000-0000-7000-8000-00000000e002', 'Directeur', 2, 'PENDING', NULL, NULL)");

                liquibase.update(new Contexts(), new LabelExpression());

                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "circuit WHERE statut = 'EN_COURS'"
                        + " AND document_id = '01920000-0000-7000-8000-00000000b001'"
                        + " AND regle_workflow_id = '01920000-0000-7000-8000-00000000f001'"));
                assertEquals(2, compter(c, "SELECT count(*) FROM " + s + "circuit_validateur WHERE id IN"
                        + " ('01920000-0000-7000-8000-00000000c001', '01920000-0000-7000-8000-00000000c002')"),
                        "un validateur par signature, même identifiant");
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "decision WHERE decision = 'VALIDE'"
                        + " AND circuit_validateur_id = '01920000-0000-7000-8000-00000000c001'"
                        + " AND version_id = '01920000-0000-7000-8000-00000000b101' AND motif = 'ok'"),
                        "la signature devient une décision sur la version courante");
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "decision"), "l'étape en attente n'a pas de décision");

                liquibase.rollback("modele-e7", (String) null);
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "workflow_ged_signature WHERE status = 'SIGNED'"
                        + " AND id = '01920000-0000-7000-8000-00000000c001'"));
                assertEquals(1, compter(c, "SELECT count(*) FROM " + s + "workflow_ged_signature WHERE status = 'PENDING'"
                        + " AND id = '01920000-0000-7000-8000-00000000c002'"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    private static Set<String> tablesMetier(Connection c, String schema) throws SQLException {
        Set<String> t = tables(c, schema);
        t.removeAll(Set.of("databasechangelog", "databasechangeloglock"));
        return t;
    }

    /* ---------------------------------------------------------------- vérifications */

    /** Clé primaire {@code id} de type uuid sur toute table qui n'est pas une association. */
    private void verifierClesUuid(Connection c, String schema) throws SQLException {
        for (String table : TABLES_ATTENDUES) {
            if (ASSOCIATIONS.contains(table) || CLES_PARTICULIERES.contains(table)) continue;
            String type = texte(c, "SELECT data_type FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = ? AND column_name = 'id'", schema, table);
            assertEquals("uuid", type, "clé primaire de " + table);
            String pk = texte(c, "SELECT constraint_name FROM information_schema.table_constraints"
                    + " WHERE table_schema = ? AND table_name = ? AND constraint_type = 'PRIMARY KEY'", schema, table);
            assertEquals("pk_" + table, pk);
        }
        // Clés générées par la base (tables d'association) : UUID version 7.
        assertEquals("7", texte(c, "SELECT substr(" + schema + ".uuid_v7()::text, 15, 1)"));
        // Toute colonne se terminant par _id (clé étrangère) est elle aussi un uuid.
        List<String> nonUuid = lignes(c, "SELECT table_name || '.' || column_name FROM information_schema.columns"
                + " WHERE table_schema = ? AND column_name LIKE '%\\_id' AND data_type <> 'uuid'"
                + " AND table_name NOT LIKE 'databasechangelog%'", schema);
        assertEquals(List.of(), nonUuid, "clés étrangères non UUID");
    }

    /** snake_case minuscule, préfixes pk_, uk_, fk_, ck_ et idx_ (§4.2.2). */
    private void verifierConventionsDeNommage(Connection c, String schema) throws SQLException {
        List<String> mauvaisNoms = lignes(c, """
                SELECT conname FROM pg_constraint k JOIN pg_namespace n ON n.oid = k.connamespace
                 WHERE n.nspname = ? AND conname !~ '^(pk|uk|fk|ck)_[a-z0-9_]+$'
                   AND contype <> 'n' AND conrelid::regclass::text NOT LIKE '%databasechangelog%'""", schema);
        assertEquals(List.of(), mauvaisNoms, "contraintes hors convention");

        List<String> mauvaisIndex = lignes(c, """
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = ? AND tablename NOT LIKE 'databasechangelog%'
                   AND indexname !~ '^(pk|uk|idx)_[a-z0-9_]+$'""", schema);
        assertEquals(List.of(), mauvaisIndex, "index hors convention");

        List<String> mauvaisesColonnes = lignes(c, """
                SELECT table_name || '.' || column_name FROM information_schema.columns
                 WHERE table_schema = ? AND table_name NOT LIKE 'databasechangelog%'
                   AND (column_name !~ '^[a-z][a-z0-9_]*$' OR table_name !~ '^[a-z][a-z0-9_]*$')""", schema);
        assertEquals(List.of(), mauvaisesColonnes, "noms hors snake_case");

        // Une clé étrangère sur <table>_id doit viser <table> (le rôle éventuel
        // précède : created_by_employe_id, parent_id pour l'auto-référence).
        List<String> incoherentes = lignes(c, """
                SELECT tc.table_name || '.' || kcu.column_name || ' -> ' || ccu.table_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
                  JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema
                 WHERE tc.table_schema = ? AND tc.constraint_type = 'FOREIGN KEY'
                   -- version_id : nom imposé par le dossier (document_texte, ocr_job, §4.4),
                   -- vise version_document.
                   AND kcu.column_name NOT IN ('parent_id', 'supprime_par', 'attribue_par', 'cree_par',
                                               'noeud_principal_id', 'archive_par', 'verrou_par', 'auteur_id',
                                               'version_id', 'initiateur_id', 'annule_par', 'reaffecte_par')
                   AND kcu.column_name NOT LIKE '%' || ccu.table_name || '_id'""", schema);
        assertEquals(List.of(), incoherentes, "clés étrangères dont le nom ne désigne pas la table visée");
    }

    /** supprime_par (uuid) et supprime_le (timestamptz) partout où existe `supprime`. */
    private void verifierSuppressionDouce(Connection c, String schema) throws SQLException {
        List<String> avecSupprime = lignes(c, "SELECT table_name FROM information_schema.columns"
                + " WHERE table_schema = ? AND column_name = 'supprime' ORDER BY 1", schema);
        assertEquals(new TreeSet<>(A_CORBEILLE), new TreeSet<>(avecSupprime));
        for (String table : A_CORBEILLE) {
            assertEquals("uuid", texte(c, "SELECT data_type FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = ? AND column_name = 'supprime_par'", schema, table));
            assertEquals("timestamp with time zone", texte(c, "SELECT data_type FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = ? AND column_name = 'supprime_le'", schema, table));
        }
    }

    /**
     * Aucun référentiel local de mots de passe (dossier technique §3.2, A02) :
     * aucune colonne de mot de passe ni d'empreinte de mot de passe dans le
     * schéma. La seule empreinte stockée est celle des jetons de session.
     */
    private void verifierAucunMotDePasse(Connection c, String schema) throws SQLException {
        List<String> suspects = lignes(c, """
                SELECT table_name || '.' || column_name FROM information_schema.columns
                 WHERE table_schema = ? AND table_name NOT LIKE 'databasechangelog%'
                   AND (column_name ~* '(mot_de_passe|password|passwd|pwd|secret|bcrypt)')""", schema);
        assertEquals(List.of(), suspects, "colonnes de mot de passe");
    }

    /** document.metadonnees : jsonb, NOT NULL, objet vide par défaut, index GIN. */
    private void verifierMetadonnees(Connection c, String schema) throws SQLException {
        assertEquals("jsonb", texte(c, "SELECT data_type FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'document' AND column_name = 'metadonnees'", schema));
        assertEquals("NO", texte(c, "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'document' AND column_name = 'metadonnees'", schema));
        String index = texte(c, "SELECT indexdef FROM pg_indexes WHERE schemaname = ?"
                + " AND indexname = 'idx_document_metadonnees'", schema);
        assertTrue(index != null && index.contains("USING gin (metadonnees)"), "index GIN attendu : " + index);
    }

    /* ---------------------------------------------------------------- outillage */

    /**
     * Supprime le schéma jetable et vérifie qu'il a bien disparu.
     *
     * <p>Liquibase coupe l'autocommit de la connexion qu'on lui prête : sans le
     * rétablir, le {@code DROP SCHEMA} restait dans une transaction jamais
     * validée et un schéma {@code ged_verif_*} s'accumulait dans la base de test
     * à chaque exécution.
     */
    private static void supprimerSchema(Connection c, String schema) throws SQLException {
        c.setAutoCommit(true);
        executer(c, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        assertEquals(0, compter(c, "SELECT count(*) FROM information_schema.schemata WHERE schema_name = '"
                + schema + "'"), "schéma jetable non supprimé : " + schema);
    }

    /** Aucun schéma jetable ne doit survivre à la classe, ni aux exécutions précédentes. */
    @org.junit.jupiter.api.AfterAll
    static void aucunSchemaJetableRestant(@org.springframework.beans.factory.annotation.Autowired
                                          org.springframework.core.env.Environment env) throws SQLException {
        try (Connection c = DriverManager.getConnection(env.getProperty("spring.datasource.url"),
                env.getProperty("spring.liquibase.user"), env.getProperty("spring.liquibase.password", ""))) {
            String jetables = " WHERE schema_name ~ '^ged_verif_[0-9a-f]+$'";
            for (String reste : lignes(c, "SELECT schema_name FROM information_schema.schemata" + jetables)) {
                executer(c, "DROP SCHEMA " + reste + " CASCADE");
            }
            assertEquals(0, compter(c, "SELECT count(*) FROM information_schema.schemata" + jetables),
                    "schémas ged_verif_* restants");
        }
    }

    private Liquibase liquibase(Connection c, String schema) throws Exception {
        executer(c, "SET search_path TO " + schema);
        Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
        db.setDefaultSchemaName(schema);
        db.setLiquibaseSchemaName(schema);
        return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db);
    }

    private static Set<String> tables(Connection c, String schema) throws SQLException {
        return new TreeSet<>(lignes(c, "SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = ? AND table_type = 'BASE TABLE'"
                + " AND table_name !~ '^journal_audit_[0-9]{6}$'", schema));
    }

    private static void executer(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static int compter(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        }
    }

    private static String texte(Connection c, String sql, String... params) throws SQLException {
        List<String> l = lignes(c, sql, params);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<String> lignes(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet r = ps.executeQuery()) {
                List<String> l = new ArrayList<>();
                while (r.next()) l.add(r.getString(1));
                return l;
            }
        }
    }
}
