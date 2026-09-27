package com.ipt.ged.support;

import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.util.ssl.cert.ManageCertificates;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Magasins PKCS#12 jetables pour les tests : clé RSA et certificat autosigné
 * pour {@code localhost}, produits par l'outil {@code manage-certificates}
 * d'UnboundID (la JDK n'a pas d'API publique pour signer un certificat).
 *
 * <p>Sert au simulateur d'annuaire en LDAPS et aux magasins de clé JWT.
 */
public final class CertificatsDeTest {

    private CertificatsDeTest() {}

    public static void autosigne(Path magasin, String motDePasse, String alias, int bits) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        ResultCode r = ManageCertificates.main(new java.io.ByteArrayInputStream(new byte[0]), sortie, sortie,
                "generate-self-signed-certificate",
                "--keystore", magasin.toString(),
                "--keystore-password", motDePasse,
                "--keystore-type", "PKCS12",
                "--alias", alias,
                "--subject-dn", "CN=localhost,O=GED tests",
                "--subject-alternative-name-dns", "localhost",
                "--key-algorithm", "RSA",
                "--key-size-bits", String.valueOf(bits),
                "--signature-algorithm", "SHA256withRSA",
                "--days-valid", "2");
        if (r != ResultCode.SUCCESS) {
            throw new IllegalStateException("Génération du certificat de test impossible : "
                    + sortie.toString(StandardCharsets.UTF_8));
        }
    }
}
