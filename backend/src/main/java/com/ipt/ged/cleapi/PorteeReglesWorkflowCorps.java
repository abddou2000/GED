package com.ipt.ged.cleapi;

import com.ipt.ged.workflow.dto.WorkflowRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

/**
 * Validateurs désignés par une application (D8, ANO-E8-001) : un validateur
 * par rôle est résolu sur un nœud de périmètre ; ce nœud doit être dans la
 * portée {@code WORKFLOW_PILOTAGE} de la clé, sans quoi une application pourrait
 * désigner les porteurs d'un rôle hors de son domaine. Le corps n'est lisible
 * qu'une fois désérialisé : le contrôle est fait ici, avant le contrôleur,
 * sans modifier le module workflow. Sans effet pour un utilisateur.
 */
@ControllerAdvice
public class PorteeReglesWorkflowCorps extends RequestBodyAdviceAdapter {

    private final GardeReglesWorkflowApplications garde;

    public PorteeReglesWorkflowCorps(GardeReglesWorkflowApplications garde) {
        this.garde = garde;
    }

    @Override
    public boolean supports(MethodParameter parametre, Type type,
                            Class<? extends HttpMessageConverter<?>> convertisseur) {
        return WorkflowRequest.class.equals(parametre.getParameterType());
    }

    @Override
    public Object afterBodyRead(Object corps, HttpInputMessage message, MethodParameter parametre, Type type,
                                Class<? extends HttpMessageConverter<?>> convertisseur) {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof ApplicationAuthentifiee a
                && corps instanceof WorkflowRequest r && r.steps() != null) {
            List<java.util.UUID> perimetres = r.steps().stream().filter(Objects::nonNull)
                    .map(WorkflowRequest.StepRequest::perimetreNoeudId).toList();
            garde.exigerPerimetres(a, perimetres);
        }
        return corps;
    }
}
