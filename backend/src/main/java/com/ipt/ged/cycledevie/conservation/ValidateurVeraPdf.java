package com.ipt.ged.cycledevie.conservation;

import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validation PDF/A-2B par veraPDF (modèle « greenfield », en bibliothèque, sans
 * processus externe ni accès réseau).
 *
 * <p>Licence : veraPDF est distribué sous double licence GPL-3.0+ ou MPL-2.0+ ;
 * la MPL-2.0 est retenue (copyleft faible au niveau du fichier, bibliothèque
 * utilisée telle quelle), compatible avec la cession du code à MMED (DAT 11.2).
 */
public class ValidateurVeraPdf implements ValidateurPdfA {

    /** Nombre de règles enfreintes reprises dans le détail. */
    private static final int REGLES_CITEES = 5;

    static {
        VeraGreenfieldFoundryProvider.initialise();
    }

    @Override
    public Validation valider(Path pdf) {
        try (InputStream in = Files.newInputStream(pdf);
             PDFAParser parser = Foundries.defaultInstance().createParser(in, PDFAFlavour.PDFA_2_B);
             PDFAValidator validateur = Foundries.defaultInstance().createValidator(PDFAFlavour.PDFA_2_B, false)) {
            ValidationResult r = validateur.validate(parser);
            if (r.isCompliant()) return new Validation(true, "");
            Set<String> regles = new LinkedHashSet<>();
            for (TestAssertion a : r.getTestAssertions()) {
                if (a.getStatus() != TestAssertion.Status.FAILED) continue;
                regles.add(a.getRuleId().getClause() + "-" + a.getRuleId().getTestNumber()
                        + (a.getMessage() != null ? " " + a.getMessage().trim() : ""));
                if (regles.size() >= REGLES_CITEES) break;
            }
            return new Validation(false, "PDF/A-2B non conforme (veraPDF) : "
                    + regles.stream().collect(Collectors.joining(" ; ")));
        } catch (Exception e) {
            return new Validation(false, "Validation veraPDF impossible : " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""));
        }
    }
}
