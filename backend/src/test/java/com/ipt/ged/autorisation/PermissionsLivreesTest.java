package com.ipt.ged.autorisation;

import com.ipt.ged.identite.Role;
import com.ipt.ged.identite.RoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ipt.ged.autorisation.CodePermission.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Données initiales du modèle de droits (§12.2.1, §12.3, décision D14) :
 * permissions livrées identiques à celles que le code applique, composition
 * des quatre rôles système.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PermissionsLivreesTest {

    @Autowired private PermissionRepository permissions;
    @Autowired private RoleRepository roles;

    private Set<CodePermission> composition(String code) {
        return roles.findByCode(code).orElseThrow().codesPermissions();
    }

    @Test
    @DisplayName("Table permission = énumération CodePermission (9 élémentaires, 7 d'administration, 2 de confidentialité)")
    void permissionsLivrees() {
        Set<String> enBase = permissions.findAll().stream().map(Permission::getCode).collect(Collectors.toSet());
        Set<String> enCode = Arrays.stream(CodePermission.values()).map(Enum::name).collect(Collectors.toSet());
        assertEquals(enCode, enBase);
        assertEquals(9, CodePermission.de(Categorie.ELEMENTAIRE).size());
        assertEquals(7, CodePermission.de(Categorie.ADMINISTRATION).size());
        assertEquals(2, CodePermission.de(Categorie.CONFIDENTIALITE).size());
        permissions.findAll().forEach(p -> assertEquals(p.codePermission().categorie().name(), p.getCategorie()));
    }

    @Test
    @DisplayName("Rôles système : composition et règle de confidentialité du Maître d'Ouvrage")
    void compositionDesRolesSysteme() {
        assertEquals(EnumSet.allOf(CodePermission.class), composition(Role.ADMINISTRATEUR));
        assertEquals(EnumSet.of(CONSULTER, DEPOSER, MODIFIER, VALIDER, DIFFUSER), composition(Role.UTILISATEUR_STANDARD));
        // ANO-F-001 (dossier fonctionnel §3.2) : les neuf permissions élémentaires, plus Voir privé.
        EnumSet<CodePermission> agent = EnumSet.copyOf(CodePermission.de(Categorie.ELEMENTAIRE));
        agent.add(VOIR_PRIVE);
        assertEquals(agent, composition(Role.AGENT_ARCHIVE));
        assertTrue(composition(Role.AGENT_ARCHIVE).containsAll(EnumSet.of(VALIDER, DIFFUSER, PURGER)));
        Set<CodePermission> dg = composition(Role.DIRECTION_GENERALE);
        // ANO-F-002 (§3.2 p. 7, décision du client du 03/10) : consulter et déposer sur tout nœud, sans les
        // permissions de structuration (Déplacer, Archiver, Supprimer), sans purge ni administration technique.
        assertEquals(EnumSet.of(CONSULTER, DEPOSER, MODIFIER, VALIDER, DIFFUSER, VOIR_PRIVE, VOIR_CONFIDENTIEL,
                CONSULTER_AUDIT), dg);
        for (CodePermission p : EnumSet.of(DEPLACER, ARCHIVER, SUPPRIMER, PURGER, GERER_ESPACES)) {
            assertFalse(dg.contains(p), p.name());
        }
        assertEquals(EnumSet.of(CONSULTER_AUDIT), dg.stream().filter(p -> p.categorie() == Categorie.ADMINISTRATION)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(CodePermission.class))));
        assertTrue(roles.findByCode(Role.DIRECTION_GENERALE).orElseThrow().isAccesGlobal());
        // VOIR_PRIVE : Agent d'archive, Administrateur, Direction Générale ; VOIR_CONFIDENTIEL : Administrateur, DG.
        for (String r : new String[]{Role.AGENT_ARCHIVE, Role.ADMINISTRATEUR, Role.DIRECTION_GENERALE}) {
            assertTrue(composition(r).contains(VOIR_PRIVE), r);
        }
        assertFalse(composition(Role.UTILISATEUR_STANDARD).contains(VOIR_PRIVE));
        assertTrue(composition(Role.ADMINISTRATEUR).contains(VOIR_CONFIDENTIEL));
        assertTrue(dg.contains(VOIR_CONFIDENTIEL));
        assertFalse(composition(Role.AGENT_ARCHIVE).contains(VOIR_CONFIDENTIEL));
    }
}
