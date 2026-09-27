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
        assertEquals(EnumSet.of(CONSULTER, DEPOSER, MODIFIER, DEPLACER, ARCHIVER, SUPPRIMER, VOIR_PRIVE),
                composition(Role.AGENT_ARCHIVE));
        Set<CodePermission> dg = composition(Role.DIRECTION_GENERALE);
        // Lecture et écriture sur tout nœud, pas de purge, pas d'administration technique.
        assertTrue(dg.containsAll(EnumSet.of(CONSULTER, DEPOSER, MODIFIER, VALIDER, DIFFUSER, DEPLACER, ARCHIVER,
                SUPPRIMER)));
        assertFalse(dg.contains(PURGER));
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
