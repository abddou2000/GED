package com.ipt.ged.workflow;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workflow.dto.WorkflowRequest;
import com.ipt.ged.workflow.dto.WorkflowResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    private final WorkflowRepository workflowRepository;
    private final EmployeRepository employeRepository;

    public WorkflowService(WorkflowRepository workflowRepository, EmployeRepository employeRepository) {
        this.workflowRepository = workflowRepository;
        this.employeRepository = employeRepository;
    }

    /** Liste active (hors corbeille) paginée + recherche. */
    @Transactional(readOnly = true)
    public PageResponse<WorkflowResponse> list(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<WorkflowGed> result =
                workflowRepository.findByDeletedFalseAndNameContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, WorkflowResponse::from);
    }

    /** Corbeille (éléments supprimés) paginée + recherche. */
    @Transactional(readOnly = true)
    public PageResponse<WorkflowResponse> trashed(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<WorkflowGed> result =
                workflowRepository.findByDeletedTrueAndNameContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, WorkflowResponse::from);
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(Long id) {
        return WorkflowResponse.from(load(id));
    }

    @Transactional
    public WorkflowResponse create(WorkflowRequest request) {
        WorkflowGed workflow = new WorkflowGed(request.name());
        applySteps(workflow, request);
        return WorkflowResponse.from(workflowRepository.save(workflow));
    }

    @Transactional
    public WorkflowResponse update(Long id, WorkflowRequest request) {
        WorkflowGed workflow = load(id);
        workflow.setName(request.name());
        workflow.clearSteps();      // remplacement total (comme l'application d'origine)
        applySteps(workflow, request);
        return WorkflowResponse.from(workflowRepository.save(workflow));
    }

    /** Suppression réversible (mise en corbeille). */
    @Transactional
    public void softDelete(Long id) {
        WorkflowGed workflow = load(id);
        workflow.setDeleted(true);
    }

    /** Restauration depuis la corbeille. */
    @Transactional
    public void restore(Long id) {
        WorkflowGed workflow = load(id);
        workflow.setDeleted(false);
    }

    @Transactional
    public void multipleDelete(List<Long> ids) {
        workflowRepository.findByIdInAndDeletedFalse(ids).forEach(w -> w.setDeleted(true));
    }

    @Transactional
    public void multipleRestore(List<Long> ids) {
        workflowRepository.findByIdInAndDeletedTrue(ids).forEach(w -> w.setDeleted(false));
    }

    private WorkflowGed load(Long id) {
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
