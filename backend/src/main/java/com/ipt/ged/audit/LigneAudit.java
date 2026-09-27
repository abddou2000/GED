package com.ipt.ged.audit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Enregistrement du journal tel que le consulte l'Administrateur (DAT §7.4.1). */
public record LigneAudit(long id, Instant horodatage, UUID acteurUtilisateurId, UUID acteurApplicationId,
                         String acteurNom, String adresseIp, String action, String objetType, UUID objetId,
                         JsonNode avant, JsonNode apres, String resultat, String motif, UUID traceId) {
}
