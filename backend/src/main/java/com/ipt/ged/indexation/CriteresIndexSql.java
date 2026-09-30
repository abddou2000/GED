package com.ipt.ged.indexation;

import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.indexation.dto.RechercheRequest;
import com.ipt.ged.planindexation.metamodele.ValeursMetadonnees;
import com.ipt.ged.recherche.FragmentSql;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Filtres d'index de la recherche multicritère ({@link RechercheRequest.FiltreIndex})
 * traduits en SQL sur {@code document_index_valeur}, pour que la base filtre,
 * trie et pagine au lieu de charger tout le fonds autorisé en mémoire (R32,
 * essais de charge §5.3 : 6,8 s et 744 Mo de tas à 50 000 documents).
 *
 * <p>Même règle que le filtrage historique en Java : un filtre non renseigné
 * est ignoré ; un document sans valeur (ou valeur blanche) pour l'index ne
 * le satisfait pas ; un index inconnu ou supprimé ne retient rien.
 * TEXTE : contient, sans casse — LISTE : égal, sans casse — DATE : bornes
 * incluses sur la forme ISO — NOMBRE : bornes incluses, virgule décimale
 * admise, valeur illisible écartée — BOOLEEN : même lecture que
 * {@link ValeursMetadonnees#booleen}. Le document est sous l'alias {@code d} ;
 * les valeurs passent toutes par des paramètres ({@code index_*}).
 */
@org.springframework.stereotype.Component
public class CriteresIndexSql {

    /** Nombre lisible après remplacement de la virgule : forme acceptée par {@link BigDecimal}. */
    private static final String NOMBRE = "'^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$'";

    private final JdbcTemplate jdbc;

    public CriteresIndexSql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Un fragment par filtre renseigné, à combiner en ET. */
    public List<FragmentSql> fragments(List<RechercheRequest.FiltreIndex> filtres) {
        List<FragmentSql> f = new ArrayList<>();
        if (filtres == null || filtres.isEmpty()) return f;
        Map<UUID, IndexFieldType> types = new HashMap<>();
        jdbc.query("SELECT id, type_champs FROM index_def WHERE NOT supprime",
                rs -> { types.put(rs.getObject(1, UUID.class), IndexFieldType.valueOf(rs.getString(2))); });
        int i = 0;
        for (RechercheRequest.FiltreIndex filtre : filtres) {
            if (filtre == null || !(renseigne(filtre.valeur()) || renseigne(filtre.de()) || renseigne(filtre.a()))) {
                continue;
            }
            IndexFieldType type = filtre.indexFieldId() == null ? null : types.get(filtre.indexFieldId());
            if (type == null) {
                f.add(FragmentSql.FAUX);
                continue;
            }
            f.add(fragment(filtre, type, "index_" + i++));
        }
        return f;
    }

    private static FragmentSql fragment(RechercheRequest.FiltreIndex filtre, IndexFieldType type, String p) {
        Map<String, Object> params = new HashMap<>();
        params.put(p, filtre.indexFieldId());
        String v = p + "_v.valeur";
        StringBuilder sql = new StringBuilder("EXISTS (SELECT 1 FROM document_index_valeur ").append(p)
                .append("_v WHERE ").append(p).append("_v.document_id = d.id AND ").append(p)
                .append("_v.index_def_id = :").append(p).append(" AND btrim(").append(v).append(") <> ''");
        switch (type) {
            case TEXTE -> {
                if (renseigne(filtre.valeur())) {
                    sql.append(" AND strpos(lower(").append(v).append("), :").append(p).append("_x) > 0");
                    params.put(p + "_x", filtre.valeur().trim().toLowerCase(Locale.ROOT));
                }
            }
            case LISTE -> {
                if (renseigne(filtre.valeur())) {
                    sql.append(" AND lower(").append(v).append(") = :").append(p).append("_x");
                    params.put(p + "_x", filtre.valeur().trim().toLowerCase(Locale.ROOT));
                }
            }
            case DATE -> {
                // Forme ISO : l'ordre des octets vaut l'ordre chronologique (collation C).
                if (renseigne(filtre.de())) {
                    sql.append(" AND ").append(v).append(" COLLATE \"C\" >= :").append(p).append("_de");
                    params.put(p + "_de", filtre.de());
                }
                if (renseigne(filtre.a())) {
                    sql.append(" AND ").append(v).append(" COLLATE \"C\" <= :").append(p).append("_a");
                    params.put(p + "_a", filtre.a());
                }
            }
            case NOMBRE -> {
                String lu = "replace(btrim(" + v + "), ',', '.')";
                sql.append(" AND ").append(lu).append(" ~ ").append(NOMBRE);
                BigDecimal min = ValeursMetadonnees.nombre(filtre.de()), max = ValeursMetadonnees.nombre(filtre.a());
                String nombre = "(CASE WHEN " + lu + " ~ " + NOMBRE + " THEN CAST(" + lu + " AS numeric) END)";
                if (min != null) {
                    sql.append(" AND ").append(nombre).append(" >= :").append(p).append("_de");
                    params.put(p + "_de", min);
                }
                if (max != null) {
                    sql.append(" AND ").append(nombre).append(" <= :").append(p).append("_a");
                    params.put(p + "_a", max);
                }
            }
            case BOOLEEN -> {
                if (renseigne(filtre.valeur())) {
                    Boolean b = ValeursMetadonnees.booleen(filtre.valeur());
                    Set<String> vrai = ValeursMetadonnees.formes(true), faux = ValeursMetadonnees.formes(false);
                    String forme = "lower(btrim(" + v + "))";
                    if (b == null) {
                        // Filtre illisible : ne retient que les valeurs illisibles, comme la règle historique.
                        sql.append(" AND ").append(forme).append(" <> ALL (CAST(:").append(p).append("_x AS text[]))");
                        List<String> toutes = new ArrayList<>(vrai);
                        toutes.addAll(faux);
                        params.put(p + "_x", tableau(toutes));
                    } else {
                        sql.append(" AND ").append(forme).append(" = ANY (CAST(:").append(p).append("_x AS text[]))");
                        params.put(p + "_x", tableau(b ? vrai : faux));
                    }
                }
            }
        }
        return new FragmentSql(sql.append(')').toString(), params);
    }

    /**
     * Littéral de tableau PostgreSQL d'un seul paramètre (un tableau Java serait
     * éclaté en autant de paramètres) ; formes fixes, sans virgule ni guillemet.
     */
    private static String tableau(java.util.Collection<String> formes) {
        return "{" + String.join(",", formes) + "}";
    }

    private static boolean renseigne(String s) {
        return s != null && !s.isBlank();
    }
}
