package com.ipt.ged.autorisation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.ipt.ged.autorisation.CodePermission.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Règle de résolution (dossier technique §12.2.2), sans base : chaque cas de la
 * règle — plus spécifique, rupture, cumul, document isolé, accès global,
 * portée des permissions d'administration et de confidentialité — et
 * l'explication des origines (P-22).
 *
 * <pre>
 *   E (espace)          F (espace)
 *   ├── D1              └── G
 *   │   └── D11
 *   └── D2
 * </pre>
 */
class ResolveurDroitsTest {

    private static final Set<CodePermission> STANDARD = EnumSet.of(CONSULTER, DEPOSER, MODIFIER, VALIDER, DIFFUSER);
    private static final Set<CodePermission> AGENT = EnumSet.of(CONSULTER, DEPOSER, MODIFIER, DEPLACER, ARCHIVER,
            SUPPRIMER, VOIR_PRIVE);
    private static final Set<CodePermission> ADMIN = EnumSet.allOf(CodePermission.class);
    private static final Set<CodePermission> DG = EnumSet.of(CONSULTER, DEPOSER, MODIFIER, VALIDER, DIFFUSER,
            DEPLACER, ARCHIVER, SUPPRIMER, VOIR_PRIVE, VOIR_CONFIDENTIEL, CONSULTER_AUDIT);

    private final UUID e = UUID.randomUUID(), d1 = UUID.randomUUID(), d11 = UUID.randomUUID(),
            d2 = UUID.randomUUID(), f = UUID.randomUUID(), g = UUID.randomUUID();
    private final UUID utilisateur = UUID.randomUUID(), groupe = UUID.randomUUID();
    private final Sujet sujet = new Sujet(TypeSujet.UTILISATEUR, utilisateur, UUID.randomUUID(), "Testeur");

    private ArbreNoeuds arbre() {
        String ce = "/" + e + "/", cd1 = ce + d1 + "/", cf = "/" + f + "/";
        return new ArbreNoeuds(List.of(
                new ArbreNoeuds.Noeud(e, null, ce, "E", false, "ACTIF"),
                new ArbreNoeuds.Noeud(d1, e, cd1, "D1", false, "ACTIF"),
                new ArbreNoeuds.Noeud(d11, d1, cd1 + d11 + "/", "D11", false, "ACTIF"),
                new ArbreNoeuds.Noeud(d2, e, ce + d2 + "/", "D2", false, "ACTIF"),
                new ArbreNoeuds.Noeud(f, null, cf, "F", false, "ACTIF"),
                new ArbreNoeuds.Noeud(g, f, cf + g + "/", "G", false, "ACTIF")));
    }

    private Attribution role(String code, Set<CodePermission> perms, UUID noeud, UUID document) {
        return new Attribution(UUID.randomUUID(), TypeSujet.UTILISATEUR, utilisateur, "Testeur", UUID.randomUUID(),
                code, "DIRECTION_GENERALE".equals(code), perms, noeud, document, false);
    }

    private Attribution parGroupe(String code, Set<CodePermission> perms, UUID noeud) {
        return new Attribution(UUID.randomUUID(), TypeSujet.GROUPE, groupe, "Groupe", UUID.randomUUID(), code,
                false, perms, noeud, null, false);
    }

    private Attribution rupture(UUID noeud) {
        return new Attribution(UUID.randomUUID(), TypeSujet.UTILISATEUR, utilisateur, "Testeur", null, null,
                false, Set.of(), noeud, null, true);
    }

    private DroitsResolus resoudre(Attribution... a) {
        return ResolveurDroits.resoudre(sujet, List.of(a), arbre(), 1L);
    }

    @Test
    @DisplayName("Héritage : une attribution sur un espace vaut pour toute sa sous-arborescence, pas ailleurs")
    void heritage() {
        DroitsResolus d = resoudre(role("UTILISATEUR_STANDARD", STANDARD, e, null));
        assertEquals(STANDARD, d.surNoeud(e));
        assertEquals(STANDARD, d.surNoeud(d11));
        assertEquals(STANDARD, d.surNoeud(d2));
        assertTrue(d.surNoeud(f).isEmpty());
        assertTrue(d.surNoeud(g).isEmpty());
        assertEquals(Set.of(e, d1, d11, d2), d.noeuds(CONSULTER));
        assertFalse(d.partout(CONSULTER));
    }

    @Test
    @DisplayName("Le plus spécifique prévaut : l'attribution d'un dossier REMPLACE l'héritage, qu'elle étende ou restreigne")
    void plusSpecifique() {
        DroitsResolus d = resoudre(role("AGENT_ARCHIVE", AGENT, e, null),
                role("UTILISATEUR_STANDARD", STANDARD, d1, null));
        assertEquals(AGENT.stream().filter(p -> p.categorie() == Categorie.ELEMENTAIRE).collect(
                java.util.stream.Collectors.toSet()), d.surNoeud(e));
        // Restriction : sur D1 et en dessous, plus de SUPPRIMER hérité d'E.
        assertEquals(STANDARD, d.surNoeud(d1));
        assertEquals(STANDARD, d.surNoeud(d11));
        assertFalse(d.peutSurNoeud(SUPPRIMER, d11));
        // D2 hérite toujours d'E.
        assertTrue(d.peutSurNoeud(SUPPRIMER, d2));
    }

    @Test
    @DisplayName("Rupture d'héritage sans rôle : aucun droit sur le nœud ni en dessous, sauf attribution plus basse")
    void rupture() {
        DroitsResolus d = resoudre(role("UTILISATEUR_STANDARD", STANDARD, e, null), rupture(d1));
        assertTrue(d.surNoeud(d1).isEmpty());
        assertTrue(d.surNoeud(d11).isEmpty());
        assertEquals(STANDARD, d.surNoeud(d2));

        DroitsResolus d2Bis = resoudre(role("UTILISATEUR_STANDARD", STANDARD, e, null), rupture(d1),
                role("UTILISATEUR_STANDARD", STANDARD, d11, null));
        assertTrue(d2Bis.surNoeud(d1).isEmpty());
        assertEquals(STANDARD, d2Bis.surNoeud(d11));
    }

    @Test
    @DisplayName("Rupture : la portée globale n'atteint plus le nœud rompu")
    void ruptureSousPorteeGlobale() {
        DroitsResolus d = resoudre(role("UTILISATEUR_STANDARD", STANDARD, null, null), rupture(f));
        assertEquals(STANDARD, d.surNoeud(e));
        assertTrue(d.surNoeud(f).isEmpty());
        assertTrue(d.surNoeud(g).isEmpty());
    }

    @Test
    @DisplayName("Cumul : au même niveau, rôles de l'utilisateur et de ses groupes s'additionnent")
    void cumul() {
        DroitsResolus d = resoudre(role("UTILISATEUR_STANDARD", STANDARD, e, null),
                parGroupe("AGENT_ARCHIVE", AGENT, e));
        assertTrue(d.peutSurNoeud(VALIDER, e));
        assertTrue(d.peutSurNoeud(SUPPRIMER, e));
        assertTrue(d.peutSurNoeud(ARCHIVER, d11));
        assertEquals(Set.of("UTILISATEUR_STANDARD", "AGENT_ARCHIVE"), d.roles());
        assertTrue(d.rolesGlobaux().isEmpty());
    }

    @Test
    @DisplayName("Document isolé : l'habilitation sur un document s'ajoute pour lui seul")
    void documentIsole() {
        UUID doc = UUID.randomUUID();
        DroitsResolus d = resoudre(role("UTILISATEUR_STANDARD", STANDARD, null, doc));
        assertEquals(STANDARD, d.surDocumentIsole(doc));
        assertTrue(d.surDocumentIsole(UUID.randomUUID()).isEmpty());
        assertEquals(Set.of(doc), d.documents(CONSULTER));
        assertTrue(d.noeuds(CONSULTER).isEmpty());
    }

    @Test
    @DisplayName("Direction Générale : accès global sur tout nœud, ruptures sans effet, aucune administration technique")
    void accesGlobal() {
        DroitsResolus d = resoudre(role("DIRECTION_GENERALE", DG, null, null), rupture(d1));
        assertTrue(d.accesGlobal());
        assertTrue(d.partout(CONSULTER));
        assertTrue(d.peutSurNoeud(MODIFIER, d11));
        assertFalse(d.peutSurNoeud(PURGER, e));
        assertTrue(d.voirPrive());
        assertTrue(d.voirConfidentiel());
        assertEquals(Set.of(CONSULTER_AUDIT), d.administration());
        assertFalse(d.administre(GERER_ESPACES));
        assertFalse(d.administre(GERER_ROLES_HABILITATIONS));
    }

    @Test
    @DisplayName("Administration : les permissions d'administration ne s'exercent qu'en portée globale")
    void administrationGlobaleSeulement() {
        DroitsResolus surNoeud = resoudre(role("ADMINISTRATEUR", ADMIN, e, null));
        assertTrue(surNoeud.administration().isEmpty());
        assertFalse(surNoeud.surNoeud(e).contains(GERER_ESPACES));
        assertTrue(surNoeud.peutSurNoeud(PURGER, e));
        assertTrue(surNoeud.rolesGlobaux().isEmpty());

        DroitsResolus global = resoudre(role("ADMINISTRATEUR", ADMIN, null, null));
        assertTrue(global.administre(GERER_ROLES_HABILITATIONS));
        assertTrue(global.partout(PURGER));
        assertEquals(Set.of("ADMINISTRATEUR"), global.rolesGlobaux());
    }

    @Test
    @DisplayName("Confidentialité : VOIR_PRIVE et VOIR_CONFIDENTIEL portées par le sujet dès qu'un rôle les contient")
    void confidentialiteDuSujet() {
        DroitsResolus agentSurDossier = resoudre(role("AGENT_ARCHIVE", AGENT, d2, null));
        assertTrue(agentSurDossier.voirPrive());
        assertFalse(agentSurDossier.voirConfidentiel());
        assertFalse(resoudre(role("UTILISATEUR_STANDARD", STANDARD, e, null)).voirPrive());
    }

    @Test
    @DisplayName("Aucune attribution : aucun droit, aucune permission exercée")
    void aucunDroit() {
        DroitsResolus d = resoudre();
        assertTrue(d.noeuds(CONSULTER).isEmpty());
        assertTrue(d.permissionsExercees().isEmpty());
        assertFalse(d.partout(CONSULTER));
    }

    @Test
    @DisplayName("Origines : attribution directe, héritage, portée globale, rupture et accès global sont nommés")
    void origines() {
        Attribution surE = role("UTILISATEUR_STANDARD", STANDARD, e, null);
        Attribution global = role("AGENT_ARCHIVE", AGENT, null, null);
        Attribution dg = role("DIRECTION_GENERALE", DG, null, null);
        List<Attribution> l = List.of(surE, global, rupture(d2), dg);
        ArbreNoeuds a = arbre();

        List<ResolveurDroits.Origine> surD11 = ResolveurDroits.expliquerNoeud(l, a, d11);
        assertTrue(surD11.stream().anyMatch(o -> o.nature().equals("HERITAGE") && e.equals(o.noeudAttribution())));
        assertTrue(surD11.stream().anyMatch(o -> o.nature().equals("ACCES_GLOBAL")));
        assertTrue(ResolveurDroits.expliquerNoeud(l, a, e).stream()
                .anyMatch(o -> o.nature().equals("ATTRIBUTION_DIRECTE")));
        assertTrue(ResolveurDroits.expliquerNoeud(l, a, d2).stream().anyMatch(o -> o.nature().equals("RUPTURE")));
        assertTrue(ResolveurDroits.expliquerNoeud(l, a, g).stream()
                .anyMatch(o -> o.nature().equals("PORTEE_GLOBALE") && "AGENT_ARCHIVE".equals(o.roleCode())));
    }
}
