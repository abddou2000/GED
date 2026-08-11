package com.ipt.ged.workspace;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.workspace.dto.TreeNode;
import com.ipt.ged.workspace.dto.WorkSpaceRequest;
import com.ipt.ged.workspace.dto.WorkSpaceResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API REST des espaces de travail (dossiers). Base : /api/v1/workspaces
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkSpaceController {

    private final WorkSpaceService service;

    public WorkSpaceController(WorkSpaceService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<WorkSpaceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return service.list(page, size, search, sortBy, sortDir);
    }

    @GetMapping("/trashed")
    public PageResponse<WorkSpaceResponse> trashed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return service.trashed(page, size, search, sortBy, sortDir);
    }

    @GetMapping("/tree")
    public List<TreeNode> tree() {
        return service.tree();
    }

    @GetMapping("/for-select")
    public List<Map<String, Object>> forSelect() {
        return service.forSelect();
    }

    @GetMapping("/{id}")
    public WorkSpaceResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<WorkSpaceResponse> create(@Valid @RequestBody WorkSpaceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public WorkSpaceResponse update(@PathVariable Long id, @Valid @RequestBody WorkSpaceRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }

    /** Déplacement : body { "parentId": <id ou null> }. */
    @PatchMapping("/{id}/parent")
    public WorkSpaceResponse move(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        return service.move(id, body.get("parentId"));
    }

    @PatchMapping("/{id}/archive")
    public WorkSpaceResponse archive(@PathVariable Long id) {
        return service.archiveToggle(id);
    }

    @DeleteMapping("/multiple-delete")
    public ResponseEntity<Void> multipleDelete(@RequestBody Map<String, List<Long>> body) {
        service.multipleDelete(body.getOrDefault("ids", List.of()));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/multiple-restore")
    public ResponseEntity<Void> multipleRestore(@RequestBody Map<String, List<Long>> body) {
        service.multipleRestore(body.getOrDefault("ids", List.of()));
        return ResponseEntity.noContent().build();
    }
}
