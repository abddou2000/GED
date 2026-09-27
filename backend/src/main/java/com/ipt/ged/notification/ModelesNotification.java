package com.ipt.ged.notification;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modèles français des notifications ({@code notification/modeles.properties}).
 *
 * <p>Rendu volontairement minimal — substitution de {@code {variable}} et
 * segments facultatifs {@code [[ … ]]} — plutôt qu'un moteur de gabarits : le
 * texte est du texte brut (e-mail {@code text/plain}, affichage échappé côté
 * Angular), donc aucune donnée métier ne peut injecter de balisage.
 */
public class ModelesNotification {

    static final String ABSENT = "—";
    private static final Pattern VARIABLE = Pattern.compile("\\{([a-zA-Z]+)}");
    private static final Pattern FACULTATIF = Pattern.compile("\\[\\[(.*?)]]");
    private static final int TITRE_MAX = 300;
    private static final int MESSAGE_MAX = 4000;

    private final Properties modeles = new Properties();

    public ModelesNotification() {
        this("notification/modeles.properties");
    }

    ModelesNotification(String ressource) {
        try (Reader r = new InputStreamReader(new ClassPathResource(ressource).getInputStream(), StandardCharsets.UTF_8)) {
            modeles.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException("Modèles de notification illisibles : " + ressource, e);
        }
        for (TypeNotification t : TypeNotification.values()) {
            if (modeles.getProperty(t.name() + ".titre") == null || modeles.getProperty(t.name() + ".message") == null) {
                throw new IllegalStateException("Modèle de notification manquant pour " + t);
            }
        }
    }

    public record Texte(String titre, String message) {}

    public Texte rendre(TypeNotification type, Map<String, ?> variables) {
        Map<String, ?> v = libellerDecision(variables);
        return new Texte(borner(remplir(modeles.getProperty(type.name() + ".titre"), v), TITRE_MAX),
                borner(remplir(modeles.getProperty(type.name() + ".message"), v), MESSAGE_MAX));
    }

    /** Corps de l'e-mail : message, lien vers l'application, pied de page. */
    public String corpsCourriel(String message, String url) {
        StringBuilder b = new StringBuilder(message);
        if (url != null) {
            b.append("\n\n").append(remplir(modeles.getProperty("courriel.lien"), Map.of("url", url)));
        }
        return b.append("\n\n-- \n").append(modeles.getProperty("courriel.pied")).toString();
    }

    private Map<String, ?> libellerDecision(Map<String, ?> variables) {
        Object d = variables.get("decision");
        if (d == null) return variables;
        String libelle = modeles.getProperty("decision." + d);
        if (libelle == null) return variables;
        java.util.Map<String, Object> copie = new java.util.HashMap<>(variables);
        copie.put("decision", libelle);
        return copie;
    }

    static String remplir(String modele, Map<String, ?> variables) {
        Matcher f = FACULTATIF.matcher(modele);
        StringBuilder sansFacultatifs = new StringBuilder();
        while (f.find()) {
            String segment = f.group(1);
            f.appendReplacement(sansFacultatifs, Matcher.quoteReplacement(toutesPresentes(segment, variables) ? segment : ""));
        }
        f.appendTail(sansFacultatifs);
        Matcher m = VARIABLE.matcher(sansFacultatifs);
        StringBuilder rendu = new StringBuilder();
        while (m.find()) {
            Object valeur = variables.get(m.group(1));
            m.appendReplacement(rendu, Matcher.quoteReplacement(present(valeur) ? valeur.toString().strip() : ABSENT));
        }
        m.appendTail(rendu);
        return rendu.toString();
    }

    private static boolean toutesPresentes(String segment, Map<String, ?> variables) {
        Matcher m = VARIABLE.matcher(segment);
        while (m.find()) {
            if (!present(variables.get(m.group(1)))) return false;
        }
        return true;
    }

    private static boolean present(Object valeur) {
        return valeur != null && !valeur.toString().isBlank();
    }

    private static String borner(String texte, int max) {
        return texte.length() <= max ? texte : texte.substring(0, max - 1) + "…";
    }
}
