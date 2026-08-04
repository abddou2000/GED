package com.ipt.ged.stats;

import com.ipt.ged.accessgroup.AccessGroupRepository;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.signature.SignatureStatus;
import com.ipt.ged.signature.WorkflowSignatureRepository;
import com.ipt.ged.workspace.WorkSpaceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Indicateurs synthétiques pour les tuiles d'en-tête (tableau de bord).
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final WorkSpaceRepository workspaces;
    private final UploadDocumentRepository documents;
    private final AccessGroupRepository accessGroups;
    private final WorkflowSignatureRepository signatures;

    public StatsController(WorkSpaceRepository workspaces, UploadDocumentRepository documents,
                           AccessGroupRepository accessGroups, WorkflowSignatureRepository signatures) {
        this.workspaces = workspaces;
        this.documents = documents;
        this.accessGroups = accessGroups;
        this.signatures = signatures;
    }

    public record Overview(long workspaces, long documents, long pendingSignatures, long accessGroups) {}

    @GetMapping("/overview")
    public Overview overview() {
        return new Overview(
                workspaces.countByDeletedFalse(),
                documents.countByDeletedFalse(),
                signatures.countByStatus(SignatureStatus.PENDING),
                accessGroups.countByDeletedFalse());
    }
}
