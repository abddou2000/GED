package com.ipt.ged.workflow;

import com.ipt.ged.common.ActeurCourant;
import java.util.UUID;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workflow.dto.WorkflowRequest;
import com.ipt.ged.workflow.dto.WorkflowResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Logique métier des circuits de workflow — reproduit l'application d'origine :
 *  - liste paginée + recherche par nom, corbeille (archive/restauration) ;
 *  - création : circuit + étapes, l'ordre recalculé selon la position ;
 *  - édition  : REMPLACEMENT total des étapes.
 */
@Service
public class WorkflowService {

    /** Colonnes triables de cet écran. */
    private static final Set<String> TRIS = Set.of("id", "name");

    private final WorkflowRepository workflowRepository;
    private final EmployeRepository employeRepository;

    public WorkflowService(WorkflowRepository workflowRepository, EmployeRepository employeRepository) {
        this.workflowRepository = workflowRepository;
        this.employeRepository = employeRepository;
    }

    /** Liste active (hors corbeille) paginée + recherche + tri. */
    @Transactional(readOnly = true)
    public PageResponse<WorkflowResponse> list(int page, int size, String search, String sortBy, String sortDir) {
        Page<WorkflowGed> result = workflowRepository
                .findByDeletedFalseAndNameContainingIgnoreCase(search, Tri.pageable(page, size, sortBy, sortDir, TRIS));
        return PageResponse.of(result, WorkflowResponse::from);
    }

    /** Corbeille (éléments supprimés) paginée + recherche + tri. */
    @Transactional(readOnly = true)
    public PageResponse<WorkflowResponse> trashed(int page, int size, String search, String sortBy, String sortDir) {
        Page<WorkflowGed> result = workflowRepository
                .findByDeletedTrueAndNameContainingIgnoreCase(search, Tri.pageable(page, size, sortBy, sortDir, TRIS));
        return PageResponse.of(result, WorkflowResponse::from);
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(UUID id) {
        return WorkflowResponse.from(load(id));
    }

    @Transactional
    public WorkflowResponse create(WorkflowRequest request) {
        WorkflowGed workflow = new WorkflowGed(request.name());
        applySteps(workflow, request);
        return WorkflowResponse.from(workflowRepository.save(workflow));
    }

    @Transactional
    public WorkflowResponse update(UUID id, WorkflowRequest request) {
        WorkflowGed workflow = load(id);
        workflow.setName(request.name());
        workflow.clearSteps();      // remplacement total (comme l'application d'origine)
        applySteps(workflow, request);
        return WorkflowResponse.from(workflowRepository.save(workflow));
    }

    /** Suppression réversible (mise en corbeille). */
    @Transactional
    public void softDelete(UUID id) {
        WorkflowGed workflow = load(id);
        workflow.mettreEnCorbeille(ActeurCourant.employeId());
    }

    /** Restauration depuis la corbeille. */
    @Transactional
    public void restore(UUID id) {
        WorkflowGed workflow = load(id);
        workflow.restaurer();
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        workflowRepository.findByIdInAndDeletedFalse(ids).forEach(w -> w.mettreEnCorbeille(ActeurCourant.employeId()));
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        workflowRepository.findByIdInAndDeletedTrue(ids).forEach(w -> w.restaurer());
    }

    private WorkflowGed load(UUID id) {
        return workflowRepository.findWithStepsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Circuit introuvable : " + id));
    }

    /** Reconstruit les étapes ; l'ordre est réattribué 1..N selon la position dans la liste. */
    private void applySteps(WorkflowGed workflow, WorkflowRequest request) {
        List<WorkflowRequest.StepRequest> steps = request.steps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowRequest.StepRequest s = steps.get(i);
            Employe employe = employeRepository.findById(s.employeId())
                    .orElseThrow(() -> new EntityNotFoundException("Employé introuvable : " + s.employeId()));
            workflow.addStep(new WorkflowStep(employe, s.label(), i + 1));
        }
    }
}
