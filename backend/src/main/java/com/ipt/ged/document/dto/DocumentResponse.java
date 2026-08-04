package com.ipt.ged.document.dto;

import com.ipt.ged.document.UploadDocument;

import java.time.Instant;

/**
 * Données renvoyées au frontend pour un document déposé.
 */
public record DocumentResponse(
        Long id,
        String name,
        Ref workspace,
        Ref typeDocument,
        String fileName,
        String extension,
        long sizeKo,
        String sizeLabel,
        String expirationDate,
        boolean active,
        Instant createdAt
) {
    public record Ref(Long id, String label) {}

    public static DocumentResponse from(UploadDocument d) {
        return new DocumentResponse(
                d.getId(), d.getName(),
                d.getWorkspace() != null ? new Ref(d.getWorkspace().getId(), d.getWorkspace().getName()) : null,
                d.getTypeDocument() != null ? new Ref(d.getTypeDocument().getId(), d.getTypeDocument().getTypeDeDocument()) : null,
                d.getFileName(), d.getExtension(), d.getSizeKo(), humanSize(d.getSizeKo()),
                d.getExpirationDate() != null ? d.getExpirationDate().toString() : null,
                d.isActive(), d.getCreatedAt());
    }

    private static String humanSize(long ko) {
        if (ko < 1024) return ko + " Ko";
        return String.format("%.1f Mo", ko / 1024.0);
    }
}
