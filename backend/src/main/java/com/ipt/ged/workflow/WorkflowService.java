package com.ipt.ged.workflow;

import com.ipt.ged.common.ActeurCourant;
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
import java.util.UUID;

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

    private final com.ipt.ged.identite.RoleRepository roles;
    private final com.ipt.ged.workspace.WorkSpaceRepository noeuds;

    public WorkflowService(WorkflowRepository workflowRepository, EmployeRepository employeRepository,
                           com.ipt.ged.identite.RoleRepository roles, com.ipt.ged.workspace.WorkSpaceRepository noeuds) {
        this.workflowRepository = workflowRepository;
        this.employeRepository = employeRepository;
        this.roles = roles;
        this.noeuds = noeuds;
    }

    /** Liste active (hors corbeille) paginée + recherche + tri. */
    @Transactional(readOnly = true)
    public PageResponse<WorkflowResponse> list(int page, int size, String search, String sortBy, String sortDir) {
        Page<WorkflowGed> result = workflowRepository
                .findBySupprimeFalseAndNameContainingIgnoreCase(search, Tri.pageable(page, size, sortBy, sortDir, TRIS));
        return PageResponse.of(result, WorkflowResponse::from);
    }

    /** Corbeille (éléments supprimés) paginée + recherche + tri. */
    @Transactional(readOnly = true)
    public PageResponse<WorkflowResponse> trashed(int page, int size, String search, String sortBy, String sortDir) {
        Page<WorkflowGed> result = workflowRepository
                .findBySupprimeTrueAndNameContainingIgnoreCase(search, Tri.pageable(page, size, sortBy, sortDir, TRIS));
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
        workflowRepository.findByIdInAndSupprimeFalse(ids).forEach(w -> w.mettreEnCorbeille(ActeurCourant.employeId()));
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        workflowRepository.findByIdInAndSupprimeTrue(ids).forEach(w -> w.restaurer());
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
            if ((s.employeId() == null) == (s.roleId() == null)) {
                throw new IllegalArgumentException("Chaque validateur est nommé (employé) OU désigné par rôle.");
            }
            if (s.perimetreNoeudId() != null && s.roleId() == null) {
                throw new IllegalArgumentException("Le périmètre ne s'applique qu'à un validateur par rôle.");
            }
            Employe employe = s.employeId() == null ? null : employeRepository.findById(s.employeId())
                    .orElseThrow(() -> new EntityNotFoundException("Employé introuvable : " + s.employeId()));
            WorkflowStep etape = new WorkflowStep(employe, s.label(), i + 1);
            if (s.roleId() != null) {
                etape.setRole(roles.findById(s.roleId())
                        .orElseThrow(() -> new EntityNotFoundException("Rôle introuvable : " + s.roleId())));
                if (s.perimetreNoeudId() != null && !noeuds.existsById(s.perimetreNoeudId())) {
                    throw new EntityNotFoundException("Nœud introuvable : " + s.perimetreNoeudId());
                }
                etape.setPerimetreNoeudId(s.perimetreNoeudId());
            }
            workflow.addStep(etape);
        }
    }
}
