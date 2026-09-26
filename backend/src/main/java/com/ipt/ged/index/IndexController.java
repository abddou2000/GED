package com.ipt.ged.index;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.index.dto.IndexRequest;
import com.ipt.ged.index.dto.IndexResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API REST des index (champs de métadonnées). Base : /api/v1/indices
 */
@RestController
@RequestMapping("/api/v1/indices")
public class IndexController {

    private final IndexService service;

    public IndexController(IndexService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<IndexResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return service.list(page, size, search, sortBy, sortDir);
    }

    @GetMapping("/trashed")
    public PageResponse<IndexResponse> trashed(
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
    public IndexResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<IndexResponse> create(@Valid @RequestBody IndexRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public IndexResponse update(@PathVariable UUID id, @Valid @RequestBody IndexRequest req) {
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
