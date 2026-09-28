package com.ipt.ged.charge;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.document.DocumentService;
import com.ipt.ged.document.dto.DocumentResponse;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.IdentitesDeTest;
import com.ipt.ged.support.JeuDroits;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

/**
 * Socle des essais de charge (§6.6). Exécutés à la demande, jamais par
 * {@code mvn test} (suffixe {@code IT}), dans une BASE DÉDIÉE jetable : le jeu
 * synthétique s'efface en supprimant la base entière, sans aucune suppression
 * de lignes. Pas de {@code drop-first} : le fonds chargé (10 000 documents et
 * plus) sert d'une exécution à l'autre ; coffre et keystore sous
 * {@code target/charge} pour la même raison (un {@code mvn test} ordinaire
 * recrée le keystore de test, les fichiers chiffrés deviendraient illisibles).
 *
 * <pre>
 * psql -U postgres -d postgres -v base=ged_dev3_charge -v tests=oui -f scripts/db/preparer-base.sql
 * DB_NAME_TEST=ged_dev3_charge SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=10 \
 *   GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT=33393 GED_IDENTITE_ANNUAIRE_URLS=ldap://localhost:33393 \
 *   mvn test -Dtest=EssaiVolumetrieChargeIT -Dsurefire.failIfNoSpecifiedTests=false
 * psql -U postgres -d postgres -c "DROP DATABASE ged_dev3_charge"   # fin des essais
 * </pre>
 */
@SpringBootTest(properties = {
        "spring.liquibase.drop-first=false",
        "ged.fichiers.racine=./target/charge/coffre",
        "ged.fichiers.racine-cache-apercu=./target/charge/cache-apercu",
        "ged.fichiers.cles.keystore=./target/charge/cles/ged-kek.p12",
        "ged.cycledevie.archivage.repertoire-travail=./target/charge/conservation-travail"
})
@ActiveProfiles("test")
abstract class BaseCharge {

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JeuDroits jeu;
    @Autowired protected IdentitesDeTest identites;
    @Autowired protected TypeDocumentRepository types;
    @Autowired protected DocumentService documents;

    protected Authentication commeAdmin() {
        return comme(Comptes.ADMIN);
    }

    protected Authentication comme(String identifiant) {
        UserDetails u = identites.loadUserByUsername(identifiant);
        Authentication a = new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    /** Type de document PDF rangé dans le nœud, taille maximale donnée. */
    protected UUID typePdf(UUID noeud, int tailleMaxMo) {
        UUID t = jeu.type(noeud, Confidentialite.PUBLIC);
        TypeDocument type = types.findById(t).orElseThrow();
        type.setTailleMaxMo(tailleMaxMo);
        types.saveAndFlush(type);
        return t;
    }

    protected DocumentResponse deposer(UUID type, String nom, byte[] pdf) {
        return documents.upload(new MockMultipartFile("file", nom + ".pdf", "application/pdf", pdf), nom, type,
                null, null, List.of());
    }
}
