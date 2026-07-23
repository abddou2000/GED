package com.ipt.ged.workflow;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.workflow.dto.WorkflowRequest;
import com.ipt.ged.workflow.dto.WorkflowResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API REST des circuits de workflow (« Règles de Workflow »).
 * Base : /api/v1/workflowgeds
 */
@RestController
@RequestMapping("/api/v1/workflowgeds")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    /** Liste active, paginée + recherche par nom. */
    @GetMapping
    public PageResponse<WorkflowResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search) {
        return service.list(page, size, search);
    }

    /** Corbeille (éléments archivés). */
    @GetMapping("/trashed")
    public PageResponse<WorkflowResponse> trashed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search) {
        return service.trashed(page, size, search);
    }

    @GetMapping("/{id}")
    public WorkflowResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody WorkflowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public WorkflowResponse update(@PathVariable Long id, @Valid @RequestBody WorkflowRequest request) {
        return service.update(id, request);
    }

    /** Suppression réversible (corbeille). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    /** Restauration depuis la corbeille. */
    @PatchMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }

    /** Suppression multiple (corbeille). */
    @DeleteMapping("/multiple-delete")
    public ResponseEntity<Void> multipleDelete(@RequestBody Map<String, List<Long>> body) {
        service.multipleDelete(body.getOrDefault("ids", List.of()));
        return ResponseEntity.noContent().build();
    }

    /** Restauration multiple. */
    @PatchMapping("/multiple-restore")
    public ResponseEntity<Void> multipleRestore(@RequestBody Map<String, List<Long>> body) {
        service.multipleRestore(body.getOrDefault("ids", List.of()));
        return ResponseEntity.noContent().build();
    }
}
