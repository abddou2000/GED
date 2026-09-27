package com.ipt.ged.fichier;

import com.ipt.ged.fichier.controle.AntivirusDesactive;
import com.ipt.ged.fichier.controle.ClientClamd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Garde-fous de configuration du stockage sécurisé. */
class ConfigurationFichiersTest {

    private final ConfigurationFichiers config = new ConfigurationFichiers();

    @TempDir Path dossier;

    @Test
    @DisplayName("L'antivirus ne peut être désactivé qu'en dev et en test (prod, uat, profil inconnu : refus)")
    void antivirusObligatoireEnProd() {
        ProprietesFichiers p = new ProprietesFichiers();
        p.getAntivirus().setActif(false);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> config.analyseurAntivirus(p, prod));
        for (String[] profils : new String[][]{{"uat"}, {"uat", "dev"}, {"recette"}, {}}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profils);
            assertThrows(IllegalStateException.class, () -> config.analyseurAntivirus(p, env),
                    "antivirus désactivable sous " + String.join(",", profils));
        }
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertInstanceOf(AntivirusDesactive.class, config.analyseurAntivirus(p, dev));
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        assertInstanceOf(AntivirusDesactive.class, config.analyseurAntivirus(p, test));
        p.getAntivirus().setActif(true);
        assertInstanceOf(ClientClamd.class, config.analyseurAntivirus(p, prod));
    }

    @Test
    @DisplayName("Keystore obligatoire, et jamais sous la racine du stockage de fichiers")
    void keystoreHorsStockage() {
        ProprietesFichiers p = new ProprietesFichiers();
        p.setRacine(dossier.resolve("coffre").toString());
        p.getCles().setMotDePasse("mdp");
        p.getCles().setCreerSiAbsent(true);
        assertThrows(IllegalStateException.class, () -> config.keyProvider(p), "chemin absent");
        p.getCles().setKeystore(dossier.resolve("coffre/cles/kek.p12").toString());
        assertThrows(IllegalStateException.class, () -> config.keyProvider(p), "keystore sous la racine");
        p.getCles().setKeystore(dossier.resolve("secrets/kek.p12").toString());
        assertEquals("kek-00001", config.keyProvider(p).kekActive());
    }

    @Test
    @DisplayName("ANO-E5-001 : le keystore du profil dev ne dépend pas du répertoire de lancement")
    void keystoreDevHorsDepot() {
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean yaml =
                new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application-dev.yml"));
        String chemin = yaml.getObject().getProperty("ged.fichiers.cles.keystore");
        assertNotNull(chemin);
        assertTrue(chemin.contains("${user.home}"), chemin);
        assertFalse(chemin.contains(":./") || chemin.contains(":data/"), "chemin relatif : " + chemin);
    }

    @Test
    @DisplayName("Valeurs par défaut conformes au §6.1.5")
    void defauts() {
        ProprietesFichiers p = new ProprietesFichiers();
        assertEquals(100, p.getTailleMaxDefautMo());
        assertEquals(200, p.getPlafondPlateformeMo());
        assertEquals(12, p.getFormatsParDefaut().size());
        assertTrue(p.getAntivirus().isActif());
        assertFalse(p.getCles().isCreerSiAbsent());
    }
}
