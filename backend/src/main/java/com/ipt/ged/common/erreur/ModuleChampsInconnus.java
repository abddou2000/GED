package com.ipt.ged.common.erreur;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Module Jackson (enregistré par Spring Boot dans l'{@code ObjectMapper} de
 * l'application) : un champ inconnu d'une classe marquée
 * {@link ChampsInconnusSignales} est ignoré, comme partout dans l'API (DAT
 * §5.3.2, P-08), et son chemin est signalé dans l'en-tête
 * {@value ChampsIgnores#ENTETE} de la réponse ({@link ChampsIgnores}).
 */
@Component
public class ModuleChampsInconnus extends SimpleModule {

    public ModuleChampsInconnus() {
        super("ged-champs-inconnus");
    }

    @Override
    public void setupModule(SetupContext contexte) {
        super.setupModule(contexte);
        contexte.addDeserializationProblemHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                                 JsonDeserializer<?> deserializer, Object beanOuClasse,
                                                 String propriete) throws IOException {
                Class<?> classe = beanOuClasse instanceof Class<?> c ? c : beanOuClasse.getClass();
                if (!classe.isAnnotationPresent(ChampsInconnusSignales.class)) {
                    return false;
                }
                ChampsIgnores.signaler(chemin(p, propriete));
                p.skipChildren();
                return true;
            }
        });
    }

    /** Chemin du champ depuis la racine du corps : {@code criteres[0].valeurr}. */
    static String chemin(JsonParser p, String propriete) {
        Deque<String> elements = new ArrayDeque<>();
        JsonStreamContext c = p.getParsingContext();
        // Contexte de l'objet qui porte le champ inconnu ; si la valeur est un objet ou un
        // tableau, le parseur est déjà entré dedans : on remonte d'un cran.
        if (c != null && (p.currentToken() == JsonToken.START_OBJECT || p.currentToken() == JsonToken.START_ARRAY)) {
            c = c.getParent();
        }
        elements.push("." + propriete);
        for (JsonStreamContext parent = c == null ? null : c.getParent(); parent != null && !parent.inRoot();
             parent = parent.getParent()) {
            if (parent.inArray()) {
                elements.push("[" + parent.getCurrentIndex() + "]");
            } else if (parent.getCurrentName() != null) {
                elements.push("." + parent.getCurrentName());
            }
        }
        StringBuilder s = new StringBuilder();
        elements.forEach(s::append);
        String r = s.toString();
        return r.startsWith(".") ? r.substring(1) : r;
    }
}
