package com.ipt.ged.indexation.dto;

import java.util.List;

/**
 * Résultats regroupés par un index de groupage (ex. par fournisseur).
 * Quand aucun groupage n'est demandé, un seul groupe « Tous » est renvoyé.
 */
public record GroupeResponse(String libelle, int total, List<ResultatResponse> documents) {}
