package com.ipt.ged.common.erreur;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Module Jackson (enregistré par Spring Boot dans l'{@code ObjectMapper} de
 * l'application) : un champ inconnu d'une classe marquée
 * {@link ChampsInconnusRefuses} lève une {@link UnrecognizedPropertyException},
 * rendue en 400 {@link CodesErreur#PARAMETRE_INCONNU} par le gestionnaire
 * d'erreurs. Les autres corps gardent le comportement de l'application (champ
 * inconnu ignoré), pour ne casser aucun client existant.
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
                if (!classe.isAnnotationPresent(ChampsInconnusRefuses.class)) {
                    return false;
                }
                throw UnrecognizedPropertyException.from(p, beanOuClasse, propriete,
                        deserializer == null ? null : deserializer.getKnownPropertyNames());
            }
        });
    }
}
