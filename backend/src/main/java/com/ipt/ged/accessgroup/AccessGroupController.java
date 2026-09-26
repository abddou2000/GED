package com.ipt.ged.accessgroup;

import java.util.UUID;
import com.ipt.ged.accessgroup.dto.AccessGroupRequest;
import com.ipt.ged.accessgroup.dto.AccessGroupResponse;
import com.ipt.ged.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API REST des groupes d'accès. Base : /api/v1/access-groups
 */
@RestController
@RequestMapping("/api/v1/access-groups")
public class AccessGroupController {

    private final AccessGroupService service;

    public AccessGroupController(AccessGroupService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AccessGroupResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return service.list(page, size, search, sortBy, sortDir);
    }

    @GetMapping("/trashed")
    public PageResponse<AccessGroupResponse> trashed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return service.trashed(page, size, search, sortBy, sortDir);
    }

    @GetMapping("/for-select")
    public List<Map<String, Object>> forSelect() {
        return service.forSelect();
    }

    @GetMapping("/{id}")
    public AccessGroupResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<AccessGroupResponse> create(@Valid @RequestBody AccessGroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public AccessGroupResponse update(@PathVariable UUID id, @Valid @RequestBody AccessGroupRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/multiple-delete")
    public ResponseEntity<Void> multipleDelete(@RequestBody Map<String, List<UUID>> body) {
        service.multipleDelete(body.getOrDefault("ids", List.of()));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/multiple-restore")
    public ResponseEntity<Void> multipleRestore(@RequestBody Map<String, List<UUID>> body) {
        service.multipleRestore(body.getOrDefault("ids", List.of()));
        return ResponseEntity.noContent().build();
    }
}
