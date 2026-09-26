package com.ipt.ged.common;

import org.hibernate.annotations.IdGeneratorType;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.EnumSet;

/**
 * Clé primaire UUID v7 attribuée par l'application à l'insertion.
 *
 * <p>À poser sur le champ {@code id} de chaque entité, avec {@code @Id}. La clé
 * est tirée côté Java plutôt que par un {@code DEFAULT} en base : l'entité
 * connaît son identifiant dès le {@code persist}, sans relecture, et l'ordre
 * chronologique des clés est garanti par {@link UuidV7}.
 */
@IdGeneratorType(IdentifiantUuid.Generateur.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IdentifiantUuid {

    /** Générateur Hibernate associé à l'annotation. */
    class Generateur implements BeforeExecutionGenerator {

        @Override
        public Object generate(SharedSessionContractImplementor session, Object owner,
                               Object currentValue, EventType eventType) {
            return UuidV7.suivant();
        }

        @Override
        public EnumSet<EventType> getEventTypes() {
            return EventTypeSets.INSERT_ONLY;
        }
    }
}
