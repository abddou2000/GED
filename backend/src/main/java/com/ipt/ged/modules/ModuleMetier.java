package com.ipt.ged.modules;

import java.util.List;
import java.util.Map;

/**
 * Modules métier activables séparément (DAT §9.3 : « le déploiement doit être
 * par processus métier […] pour détecter les bugs rapidement » ; T-088).
 *
 * <p>Un module regroupe un processus du dossier fonctionnel : ses points
 * d'entrée (routes, fermées en 404 {@code MODULE_INACTIF} quand il est
 * désactivé) et ses traitements de fond (propriétés techniques forcées à
 * l'arrêt, voir {@link ModulesEnvironnement}). Le socle — identité et
 * habilitations, arborescence, dépôt et consultation, typologie et indexation,
 * journal d'audit — n'est pas un module : sans lui, aucun processus n'a de sens,
 * il est toujours actif.
 *
 * <p>Les routes sont fixées ici parce qu'elles sont du code ; l'état
 * (actif ou non) est de la configuration : {@code ged.modules.<code>.actif},
 * ou la variable {@code GED_MODULES_<CODE>_ACTIF} (fichier /etc/ged/modules.env).
 */
public enum ModuleMetier {

    OCR("ocr", "OCRisation et recherche plein texte", "DF §4.2, §4.4 ; DAT §4.4",
            List.of("/api/v1/ocr/**", "/api/v1/recherche/**", "/api/v1/admin/ocr/**",
                    "/api/v1/admin/recherche/**"),
            Map.of("ged.ocr.chaine.actif", "false")),

    WORKFLOW("workflow", "Circuits de validation et diffusion", "DF §4.5 ; DAT §12.8, D8",
            List.of("/api/v1/workflow/**", "/api/v1/workflowgeds/**", "/api/v1/workflowgeds"),
            Map.of()),

    CYCLEDEVIE("cycledevie", "Cycle de vie : archivage, conservation, purge, re-typologisation",
            "DF §4.6, §4.7 ; DAT §12.6, §12.9",
            List.of("/api/v1/documents/*/archivage", "/api/v1/documents/*/conservation",
                    "/api/v1/documents/*/purge", "/api/v1/documents/purge", "/api/v1/archivage/**",
                    "/api/v1/type-documents/retypages/**", "/api/v1/type-documents/retypages"),
            Map.of("ged.conservation.alertes.actif", "false")),

    EXPORT("export", "Export de dossiers", "DF §4.4 ; DAT §12.10",
            List.of("/api/v1/exports/**", "/api/v1/exports"),
            Map.of()),

    NOTIFICATIONS("notifications", "Notifications (application et e-mail)", "DF §4.5.3, §4.6.6 ; DAT §12.9",
            List.of("/api/v1/notifications/**", "/api/v1/notifications"),
            Map.of("ged.notification.active", "false", "ged.notification.expedition-auto", "false")),

    INTEGRATION("integration", "Intégration par API : clés d'API, bureau d'ordre, délégation",
            "DF §4.10 ; DAT §5",
            List.of("/api/v1/applications/**", "/api/v1/applications", "/api/v1/cles-api/**"),
            Map.of());

    private final String code;
    private final String libelle;
    private final String reference;
    private final List<String> routes;
    private final Map<String, String> proprietesArret;

    ModuleMetier(String code, String libelle, String reference, List<String> routes,
                 Map<String, String> proprietesArret) {
        this.code = code;
        this.libelle = libelle;
        this.reference = reference;
        this.routes = routes;
        this.proprietesArret = proprietesArret;
    }

    /** Code de configuration : {@code ged.modules.<code>.actif}. */
    public String code() {
        return code;
    }

    public String libelle() {
        return libelle;
    }

    /** Sections du dossier fonctionnel (DF) et du dossier technique (DAT) couvertes. */
    public String reference() {
        return reference;
    }

    /** Motifs de chemins (AntPathMatcher) fermés quand le module est inactif. */
    public List<String> routes() {
        return routes;
    }

    /** Propriétés techniques imposées quand le module est inactif (traitements de fond arrêtés). */
    public Map<String, String> proprietesArret() {
        return proprietesArret;
    }

    public static ModuleMetier parCode(String code) {
        for (ModuleMetier m : values()) if (m.code.equals(code)) return m;
        return null;
    }
}
