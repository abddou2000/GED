package com.ipt.ged.identite;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Paramètres du lot identité et sessions : sous-arbre {@code ged.identite} de la
 * configuration (dossier technique §3.3, §3.4 ; décisions client D1 à D4).
 *
 * <p>Les valeurs par défaut sont celles de la PRODUCTION et ferment tout ce qui
 * peut l'être : LDAPS exigé, clé de signature fournie exigée, cookie
 * {@code Secure}. Les profils dev et test relâchent explicitement ce qui doit
 * l'être ; un profil oublié (uat, ci…) hérite donc du réglage sûr.
 */
@ConfigurationProperties(prefix = "ged.identite")
public class ProprietesIdentite {

    private final Annuaire annuaire = new Annuaire();
    private final Jeton jeton = new Jeton();
    private final Session session = new Session();
    private final Limitation limitation = new Limitation();
    private final Cache cache = new Cache();
    private final Amorcage amorcage = new Amorcage();

    /**
     * Domaine des adresses dérivées « prenom.nom@domaine ». Sert uniquement à
     * rattacher une identité provisionnée à une fiche employé existante (reprise
     * de l'ancienne base), jamais à authentifier.
     */
    private String domaineCourriel = "marchica.ma";

    public Annuaire getAnnuaire() { return annuaire; }
    public Jeton getJeton() { return jeton; }
    public Session getSession() { return session; }
    public Limitation getLimitation() { return limitation; }
    public Cache getCache() { return cache; }
    public Amorcage getAmorcage() { return amorcage; }
    public String getDomaineCourriel() { return domaineCourriel; }
    public void setDomaineCourriel(String domaineCourriel) { this.domaineCourriel = domaineCourriel; }

    /** Liaison à l'annuaire (§3.3, tableau des paramètres ; D2, D3, D4). */
    public static class Annuaire {
        /**
         * Contrôleurs de domaine, dans l'ordre de préférence. La bascule se fait
         * sur le suivant quand un contrôleur est injoignable ou muet (délai de
         * lecture expiré) ; UN seul suffit (D4).
         */
        private List<String> urls = new ArrayList<>();
        /**
         * Durée pendant laquelle un contrôleur qui vient d'échouer passe après
         * les autres (ANO-E2-002) : les connexions suivantes ne paient pas son
         * délai de lecture.
         */
        private Duration miseALEcart = Duration.ofSeconds(30);
        /**
         * Échéance du secret du compte de service (AAAA-MM-JJ), quand l'annuaire
         * ne la porte pas (mot de passe sans expiration, rotation imposée par
         * MMED). Vide :
         * lue sur le compte de service ({@link com.ipt.ged.identite.annuaire.EcheanceSecretAnnuaire}).
         */
        private String echeanceSecret = "";
        /** Base de recherche, par exemple {@code DC=marchicamed,DC=ma}. */
        private String base = "";
        /** DN du compte de service en lecture seule. */
        private String compteService = "";
        /** Secret du compte de service (variable d'environnement, jamais le dépôt). */
        private String motDePasseService = "";
        /**
         * Fichier contenant le secret du compte de service. Prioritaire sur
         * {@link #motDePasseService} ; relu à chaud quand il change (rotation
         * sans redémarrage).
         */
        private String motDePasseServiceFichier = "";
        private Duration delaiConnexion = Duration.ofSeconds(3);
        private Duration delaiLecture = Duration.ofSeconds(5);
        /** Pool de connexions du compte de service. */
        private boolean pool = true;
        /** Refuse toute URL autre que {@code ldaps://} (LDAP en clair interdit). */
        private boolean exigerLdaps = true;
        /** Magasin de confiance contenant la chaîne de certificats de MMED. */
        private String truststore = "";
        private String truststoreMotDePasse = "";
        private String truststoreType = "PKCS12";
        /**
         * Attributs demandés à l'annuaire : le strict minimum (D3). Tout attribut
         * d'appartenance (memberOf, groupes, unité) est exclu, quelle que soit
         * cette liste (principe P2) ; {@code userAccountControl} aussi (D1).
         */
        private List<String> attributs = new ArrayList<>(List.of(
                "sAMAccountName", "objectGUID", "givenName", "sn", "displayName", "mail", "department"));

        public List<String> getUrls() { return urls; }
        public void setUrls(List<String> urls) { this.urls = urls; }
        public Duration getMiseALEcart() { return miseALEcart; }
        public void setMiseALEcart(Duration miseALEcart) { this.miseALEcart = miseALEcart; }
        public String getEcheanceSecret() { return echeanceSecret; }
        public void setEcheanceSecret(String echeanceSecret) { this.echeanceSecret = echeanceSecret; }
        public String getBase() { return base; }
        public void setBase(String base) { this.base = base; }
        public String getCompteService() { return compteService; }
        public void setCompteService(String compteService) { this.compteService = compteService; }
        public String getMotDePasseService() { return motDePasseService; }
        public void setMotDePasseService(String motDePasseService) { this.motDePasseService = motDePasseService; }
        public String getMotDePasseServiceFichier() { return motDePasseServiceFichier; }
        public void setMotDePasseServiceFichier(String f) { this.motDePasseServiceFichier = f; }
        public Duration getDelaiConnexion() { return delaiConnexion; }
        public void setDelaiConnexion(Duration delaiConnexion) { this.delaiConnexion = delaiConnexion; }
        public Duration getDelaiLecture() { return delaiLecture; }
        public void setDelaiLecture(Duration delaiLecture) { this.delaiLecture = delaiLecture; }
        public boolean isPool() { return pool; }
        public void setPool(boolean pool) { this.pool = pool; }
        public boolean isExigerLdaps() { return exigerLdaps; }
        public void setExigerLdaps(boolean exigerLdaps) { this.exigerLdaps = exigerLdaps; }
        public String getTruststore() { return truststore; }
        public void setTruststore(String truststore) { this.truststore = truststore; }
        public String getTruststoreMotDePasse() { return truststoreMotDePasse; }
        public void setTruststoreMotDePasse(String p) { this.truststoreMotDePasse = p; }
        public String getTruststoreType() { return truststoreType; }
        public void setTruststoreType(String truststoreType) { this.truststoreType = truststoreType; }
        public List<String> getAttributs() { return attributs; }
        public void setAttributs(List<String> attributs) { this.attributs = attributs; }
    }

    /** Jeton d'accès JWT RS256 (§3.4.1). */
    public static class Jeton {
        private Duration validite = Duration.ofMinutes(15);
        private String emetteur = "ged-api";
        private Duration toleranceHorloge = Duration.ofSeconds(30);
        /** Magasin PKCS#12 contenant la clé privée RSA de signature (hors dépôt). */
        private String keystore = "";
        private String keystoreMotDePasse = "";
        private String alias = "ged-jwt";
        /**
         * Autorise, faute de magasin, une paire RSA tirée au démarrage. Faux par
         * défaut : en production l'absence de clé empêche de démarrer.
         */
        private boolean cleEphemereAutorisee = false;

        public Duration getValidite() { return validite; }
        public void setValidite(Duration validite) { this.validite = validite; }
        public String getEmetteur() { return emetteur; }
        public void setEmetteur(String emetteur) { this.emetteur = emetteur; }
        public Duration getToleranceHorloge() { return toleranceHorloge; }
        public void setToleranceHorloge(Duration toleranceHorloge) { this.toleranceHorloge = toleranceHorloge; }
        public String getKeystore() { return keystore; }
        public void setKeystore(String keystore) { this.keystore = keystore; }
        public String getKeystoreMotDePasse() { return keystoreMotDePasse; }
        public void setKeystoreMotDePasse(String p) { this.keystoreMotDePasse = p; }
        public String getAlias() { return alias; }
        public void setAlias(String alias) { this.alias = alias; }
        public boolean isCleEphemereAutorisee() { return cleEphemereAutorisee; }
        public void setCleEphemereAutorisee(boolean b) { this.cleEphemereAutorisee = b; }
    }

    /** Jeton de renouvellement et session (§3.4.1, risque R26). */
    public static class Session {
        /**
         * Durée de vie absolue d'une session, renouvellements compris. 8 h au
         * dossier V3 ; 4 h recommandées (risque R26 : sans relecture de l'état AD,
         * un compte désactivé garde sa session jusqu'à cette borne).
         */
        private Duration dureeAbsolue = Duration.ofHours(8);
        /** Inactivité maximale entre deux renouvellements. */
        private Duration inactivite = Duration.ofMinutes(30);
        private String nomCookie = "ged_renouvellement";
        private String cheminCookie = "/api/v1/auth";
        /** Attribut {@code Secure} du cookie ; ne se désactive qu'en test (MockMvc en http). */
        private boolean cookieSecurise = true;
        /** En-tête personnalisé exigé sur les points d'entrée fondés sur le cookie (CSRF). */
        private String enteteCsrf = "X-GED-Renouvellement";

        public Duration getDureeAbsolue() { return dureeAbsolue; }
        public void setDureeAbsolue(Duration dureeAbsolue) { this.dureeAbsolue = dureeAbsolue; }
        public Duration getInactivite() { return inactivite; }
        public void setInactivite(Duration inactivite) { this.inactivite = inactivite; }
        public String getNomCookie() { return nomCookie; }
        public void setNomCookie(String nomCookie) { this.nomCookie = nomCookie; }
        public String getCheminCookie() { return cheminCookie; }
        public void setCheminCookie(String cheminCookie) { this.cheminCookie = cheminCookie; }
        public boolean isCookieSecurise() { return cookieSecurise; }
        public void setCookieSecurise(boolean cookieSecurise) { this.cookieSecurise = cookieSecurise; }
        public String getEnteteCsrf() { return enteteCsrf; }
        public void setEnteteCsrf(String enteteCsrf) { this.enteteCsrf = enteteCsrf; }
    }

    /** Limitation de débit de la connexion (§3.4.1). */
    public static class Limitation {
        private int tentatives = 5;
        private Duration fenetre = Duration.ofMinutes(1);

        public int getTentatives() { return tentatives; }
        public void setTentatives(int tentatives) { this.tentatives = tentatives; }
        public Duration getFenetre() { return fenetre; }
        public void setFenetre(Duration fenetre) { this.fenetre = fenetre; }
    }

    /** Cache annuaire (§3.4.2, décision T2). */
    public static class Cache {
        private Duration duree = Duration.ofMinutes(15);

        public Duration getDuree() { return duree; }
        public void setDuree(Duration duree) { this.duree = duree; }
    }

    /**
     * Amorçage du premier Administrateur. Sans lui, personne ne pourrait
     * attribuer de rôle : une identité provisionnée n'en reçoit aucun. Les
     * identifiants listés reçoivent le rôle Administrateur à leur PREMIÈRE
     * connexion seulement ; ensuite, les rôles ne se gèrent que dans la GED.
     */
    public static class Amorcage {
        private List<String> administrateurs = new ArrayList<>();

        public List<String> getAdministrateurs() { return administrateurs; }
        public void setAdministrateurs(List<String> administrateurs) { this.administrateurs = administrateurs; }
    }
}
