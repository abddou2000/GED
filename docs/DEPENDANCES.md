# Registre des dépendances — GED Marchica Med

> **Fichier généré** par `node outils/registre-dependances.mjs` à partir des SBOM CycloneDX
> (`backend/target/bom.json`, `frontend/dist/bom.json`). Ne pas modifier à la main : corriger
> la table des usages ou des arbitrages dans le script, puis régénérer. La CI signale un registre en retard.

Exigences couvertes : DAT 8.3 (traçabilité complète des dépendances, version et licence),
DAT 11.2 / Article 45 (licence compatible avec la cession de propriété à MMED), DAT 6.2.3 A06.

## Synthèse

| | Nombre |
|---|---|
| Dépendances back-end (Maven, portée d'exécution) | 119 |
| Dépendances front-end livrées (npm, hors outillage de développement) | 18 |
| Composants hors gestionnaire de paquets | 3 |
| Licence permissive — compatible | 132 |
| Copyleft faible — compatible sous condition de non-modification | 7 |
| **À examiner** | 1 |

## Règle de compatibilité appliquée (DAT 11.2)

La cession à MMED porte sur le code développé pour le marché ; les bibliothèques tierces restent
sous leur licence. Une licence est compatible si elle permet à MMED d'utiliser, modifier et
redistribuer la GED sans obligation de publier son code ni redevance :

- **Permissive** (Apache-2.0, MIT, BSD, ISC, OFL-1.1 pour les polices, CC0) : compatible.
- **Copyleft faible** (LGPL, EPL, MPL, CDDL, GPL avec exception Classpath) : compatible tant que la
  bibliothèque est utilisée **telle quelle** ; toute modification de la bibliothèque elle-même
  devrait être publiée. Règle d'équipe : ne jamais modifier une bibliothèque tierce.
- **Copyleft fort** (GPL, AGPL) ou licence absente : **à examiner**, bloquant pour la mise en production.

## Points d'attention et arbitrages

- **com.h2database:h2 2.3.232** (MPL-2.0 ou EPL-1.0) — Double licence MPL-2.0 ou EPL-1.0 : copyleft au niveau du fichier, non modifié. Base de développement, sans usage en production.
- **com.mysql:mysql-connector-j 9.1.0** (GPL-2.0-with-universal-foss-exception) — GPL-2.0 avec exception FOSS universelle : l'exception couvre l'usage avec un logiciel sous licence libre, **pas une application propriétaire cédée à MMED**. Risque réel. Le composant disparaît avec la migration PostgreSQL (E1, dev1) : à retirer du `pom.xml` à ce moment, et ne pas livrer en production avant.
- **ch.qos.logback:logback-classic 1.5.12** (EPL-1.0 ou LGPL-2.1) — Double licence EPL-1.0 ou LGPL-2.1, au choix : EPL-1.0 retenue, bibliothèque non modifiée.
- **ch.qos.logback:logback-core 1.5.12** (EPL-1.0 ou LGPL-2.1) — Idem logback-classic.
- **jakarta.annotation:jakarta.annotation-api 2.1.1** (EPL-2.0 ou GPL-2.0-with-classpath-exception) — EPL-2.0 ou GPL-2.0 avec exception Classpath : EPL-2.0 retenue, API non modifiée.
- **jakarta.transaction:jakarta.transaction-api 2.0.1** (EPL-2.0 ou GPL-2.0-with-classpath-exception) — Idem jakarta.annotation-api.
- **org.aspectj:aspectjweaver 1.9.22.1** (EPL-2.0) — EPL-2.0 : copyleft au niveau du fichier, bibliothèque non modifiée (tirée par spring-boot-starter-data-jpa).
- **org.hibernate.orm:hibernate-core 6.6.4.Final** (LGPL-2.1-only) — LGPL-2.1 : utilisation comme bibliothèque non modifiée, liée dynamiquement (JAR séparé dans le JAR Spring Boot) — aucune obligation sur le code de la GED. Ne jamais modifier ni recompiler Hibernate dans le livrable.

## Composants hors gestionnaire de paquets

| Nom | Version | Licence | Usage |
|---|---|---|---|
| Tesseract OCR | 5.x (binaire du serveur) | Apache-2.0 | Moteur OCR, appelé en processus externe (DAT 4.3.1) |
| Modèles Tesseract (fra, eng, osd) | non tracée (dépôt tesseract-ocr/tessdata) | Apache-2.0 | Modèles LSTM livrés dans backend/tessdata ; version à consigner (E6, ajout de ara) |
| Icônes lucide-static | 1.26.0 | ISC | Tracés SVG recopiés dans frontend/src/app/core/ged-icons.ts |

## Back-end — dépendances directes

| Nom | Version | Licence | Compatibilité | Usage |
|---|---|---|---|---|
| com.h2database:h2 | 2.3.232 | MPL-2.0 ou EPL-1.0 | Compatible sous condition | Base de développement embarquée (hors production) |
| com.mysql:mysql-connector-j | 9.1.0 | GPL-2.0-with-universal-foss-exception | **À examiner** | Pilote MySQL (socle historique, remplacé par PostgreSQL en E1) |
| io.jsonwebtoken:jjwt-api | 0.12.6 | Apache-2.0 | Compatible | Jetons JWT (API) |
| io.jsonwebtoken:jjwt-impl | 0.12.6 | Apache-2.0 | Compatible | Jetons JWT (implémentation) |
| io.jsonwebtoken:jjwt-jackson | 0.12.6 | Apache-2.0 | Compatible | Jetons JWT (sérialisation JSON) |
| io.micrometer:micrometer-registry-prometheus | 1.14.2 | Apache-2.0 | Compatible | Export des métriques au format Prometheus (DAT 6.7) |
| org.apache.pdfbox:pdfbox | 3.0.8 | Apache-2.0 | Compatible | Couche texte et rendu des PDF (OCR, DAT 4.3.4) |
| org.apache.poi:poi-ooxml | 5.3.0 | Apache-2.0 | Compatible | Lecture des formats Office (OCR, indexation) |
| org.apache.poi:poi-scratchpad | 5.3.0 | Apache-2.0 | Compatible | Lecture des anciens formats Office |
| org.flywaydb:flyway-core | 10.20.1 | Apache-2.0 | Compatible | Migrations de schéma (socle historique, remplacé par Liquibase en E1) |
| org.flywaydb:flyway-mysql | 10.20.1 | Apache-2.0 | Compatible | Migrations de schéma MySQL (socle historique) |
| org.projectlombok:lombok | 1.18.36 | MIT | Compatible | Génération de code à la compilation (absent du JAR livré) |
| org.springdoc:springdoc-openapi-starter-webmvc-ui | 2.7.0 | Apache-2.0 | Compatible | Spécification OpenAPI 3 et Swagger UI (DAT 5.3) |
| org.springframework.boot:spring-boot-starter-actuator | 3.4.1 | Apache-2.0 | Compatible | Sondes de santé et métriques (DAT 6.7) |
| org.springframework.boot:spring-boot-starter-data-jpa | 3.4.1 | Apache-2.0 | Compatible | Persistance JPA / Hibernate |
| org.springframework.boot:spring-boot-starter-security | 3.4.1 | Apache-2.0 | Compatible | Chaîne de sécurité, authentification |
| org.springframework.boot:spring-boot-starter-validation | 3.4.1 | Apache-2.0 | Compatible | Bean Validation des DTO (DAT 6.3) |
| org.springframework.boot:spring-boot-starter-web | 3.4.1 | Apache-2.0 | Compatible | API REST, serveur Tomcat embarqué |

## Front-end — dépendances directes livrées

| Nom | Version | Licence | Compatibilité | Usage |
|---|---|---|---|---|
| @angular/animations | 22.0.8 | MIT | Compatible | Animations Angular |
| @angular/cdk | 22.0.6 | MIT | Compatible | Composants de base Angular (CDK) |
| @angular/common | 22.0.8 | MIT | Compatible | Socle Angular |
| @angular/compiler | 22.0.8 | MIT | Compatible | Socle Angular |
| @angular/core | 22.0.8 | MIT | Compatible | Socle Angular |
| @angular/forms | 22.0.8 | MIT | Compatible | Formulaires réactifs et validation (DAT 6.3) |
| @angular/material | 22.0.6 | MIT | Compatible | Composants graphiques Material |
| @angular/platform-browser | 22.0.8 | MIT | Compatible | Socle Angular (navigateur) |
| @angular/router | 22.0.8 | MIT | Compatible | Routage de l'application |
| @fontsource-variable/fraunces | 5.3.0 | OFL-1.1 | Compatible | Police de titres, servie localement |
| @fontsource-variable/inter | 5.3.0 | OFL-1.1 | Compatible | Police de texte, servie localement |
| @fontsource-variable/nunito | 5.3.0 | OFL-1.1 | Compatible | Police de texte, servie localement |
| primeicons | 8.0.0 | MIT (vérifiée à la main) | Compatible | Jeu d'icônes |
| rxjs | 7.8.2 | Apache-2.0 | Compatible | Programmation réactive (socle Angular) |
| tslib | 2.8.1 | 0BSD | Compatible | Assistants d'exécution TypeScript |

## Back-end — dépendances transitives

| Nom | Version | Licence | Compatibilité | Usage |
|---|---|---|---|---|
| ch.qos.logback:logback-classic | 1.5.12 | EPL-1.0 ou LGPL-2.1 | Compatible sous condition | transitive de org.springframework.boot:spring-boot-starter-web |
| ch.qos.logback:logback-core | 1.5.12 | EPL-1.0 ou LGPL-2.1 | Compatible sous condition | transitive de org.springframework.boot:spring-boot-starter-web |
| com.fasterxml:classmate | 1.7.0 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| com.fasterxml.jackson.core:jackson-annotations | 2.18.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| com.fasterxml.jackson.core:jackson-core | 2.18.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| com.fasterxml.jackson.core:jackson-databind | 2.18.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| com.fasterxml.jackson.dataformat:jackson-dataformat-toml | 2.18.2 | Apache-2.0 | Compatible | transitive de org.flywaydb:flyway-core |
| com.fasterxml.jackson.dataformat:jackson-dataformat-yaml | 2.18.2 | Apache-2.0 | Compatible | transitive de org.springdoc:springdoc-openapi-starter-webmvc-ui |
| com.fasterxml.jackson.datatype:jackson-datatype-jdk8 | 2.18.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | 2.18.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| com.fasterxml.jackson.module:jackson-module-parameter-names | 2.18.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| com.github.virtuald:curvesapi | 1.08 | BSD-3-Clause | Compatible | transitive de org.apache.poi:poi-ooxml |
| com.sun.istack:istack-commons-runtime | 4.1.2 | BSD-3-Clause | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| com.zaxxer:HikariCP | 5.1.0 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| com.zaxxer:SparseBitSet | 1.3 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| commons-codec:commons-codec | 1.17.1 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| commons-io:commons-io | 2.16.1 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| commons-logging:commons-logging | 1.4.0 | Apache-2.0 | Compatible | transitive de org.apache.pdfbox:pdfbox |
| io.micrometer:micrometer-commons | 1.14.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| io.micrometer:micrometer-core | 1.14.2 | Apache-2.0 | Compatible | transitive de io.micrometer:micrometer-registry-prometheus |
| io.micrometer:micrometer-jakarta9 | 1.14.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-actuator |
| io.micrometer:micrometer-observation | 1.14.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| io.prometheus:prometheus-metrics-config | 1.3.5 | Apache-2.0 | Compatible | transitive de io.micrometer:micrometer-registry-prometheus |
| io.prometheus:prometheus-metrics-core | 1.3.5 | Apache-2.0 | Compatible | transitive de io.micrometer:micrometer-registry-prometheus |
| io.prometheus:prometheus-metrics-exposition-formats | 1.3.5 | Apache-2.0 | Compatible | transitive de io.micrometer:micrometer-registry-prometheus |
| io.prometheus:prometheus-metrics-exposition-textformats | 1.3.5 | Apache-2.0 | Compatible | transitive de io.micrometer:micrometer-registry-prometheus |
| io.prometheus:prometheus-metrics-model | 1.3.5 | Apache-2.0 | Compatible | transitive de io.micrometer:micrometer-registry-prometheus |
| io.prometheus:prometheus-metrics-tracer-common | 1.3.5 | Apache-2.0 | Compatible | transitive de io.micrometer:micrometer-registry-prometheus |
| io.smallrye:jandex | 3.2.0 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| io.swagger.core.v3:swagger-annotations-jakarta | 2.2.25 | Apache-2.0 | Compatible | transitive de org.springdoc:springdoc-openapi-starter-webmvc-ui |
| io.swagger.core.v3:swagger-core-jakarta | 2.2.25 | Apache-2.0 | Compatible | transitive de org.springdoc:springdoc-openapi-starter-webmvc-ui |
| io.swagger.core.v3:swagger-models-jakarta | 2.2.25 | Apache-2.0 | Compatible | transitive de org.springdoc:springdoc-openapi-starter-webmvc-ui |
| jakarta.activation:jakarta.activation-api | 2.1.3 | BSD-3-Clause | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| jakarta.annotation:jakarta.annotation-api | 2.1.1 | EPL-2.0 ou GPL-2.0-with-classpath-exception | Compatible sous condition | transitive de org.springframework.boot:spring-boot-starter-web |
| jakarta.inject:jakarta.inject-api | 2.0.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| jakarta.persistence:jakarta.persistence-api | 3.1.0 | EPL-2.0 ou BSD-3-Clause | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| jakarta.transaction:jakarta.transaction-api | 2.0.1 | EPL-2.0 ou GPL-2.0-with-classpath-exception | Compatible sous condition | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| jakarta.validation:jakarta.validation-api | 3.0.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-validation |
| jakarta.xml.bind:jakarta.xml.bind-api | 4.0.2 | BSD-3-Clause | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| net.bytebuddy:byte-buddy | 1.15.11 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.antlr:antlr4-runtime | 4.13.0 | BSD-3-Clause | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.apache.commons:commons-collections4 | 4.4 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| org.apache.commons:commons-compress | 1.26.2 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| org.apache.commons:commons-lang3 | 3.17.0 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| org.apache.commons:commons-math3 | 3.6.1 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| org.apache.logging.log4j:log4j-api | 2.24.3 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| org.apache.logging.log4j:log4j-to-slf4j | 2.24.3 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.apache.pdfbox:fontbox | 3.0.8 | Apache-2.0 | Compatible | transitive de org.apache.pdfbox:pdfbox |
| org.apache.pdfbox:pdfbox-io | 3.0.8 | Apache-2.0 | Compatible | transitive de org.apache.pdfbox:pdfbox |
| org.apache.poi:poi | 5.3.0 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| org.apache.poi:poi-ooxml-lite | 5.3.0 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| org.apache.tomcat.embed:tomcat-embed-core | 10.1.34 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.apache.tomcat.embed:tomcat-embed-el | 10.1.34 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.apache.tomcat.embed:tomcat-embed-websocket | 10.1.34 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.apache.xmlbeans:xmlbeans | 5.2.1 | Apache-2.0 | Compatible | transitive de org.apache.poi:poi-ooxml |
| org.aspectj:aspectjweaver | 1.9.22.1 | EPL-2.0 | Compatible sous condition | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.eclipse.angus:angus-activation | 2.0.2 | BSD-3-Clause | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.glassfish.jaxb:jaxb-core | 4.0.5 | BSD-3-Clause | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.glassfish.jaxb:jaxb-runtime | 4.0.5 | BSD-3-Clause | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.glassfish.jaxb:txw2 | 4.0.5 | BSD-3-Clause | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.hdrhistogram:HdrHistogram | 2.2.2 | CC0-1.0 ou BSD-2-Clause | Compatible | transitive de io.micrometer:micrometer-registry-prometheus |
| org.hibernate.common:hibernate-commons-annotations | 7.0.3.Final | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.hibernate.orm:hibernate-core | 6.6.4.Final | LGPL-2.1-only | Compatible sous condition | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.hibernate.validator:hibernate-validator | 8.0.2.Final | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-validation |
| org.jboss.logging:jboss-logging | 3.6.1.Final | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.jspecify:jspecify | 1.0.0 | Apache-2.0 | Compatible | transitive de org.springdoc:springdoc-openapi-starter-webmvc-ui |
| org.latencyutils:LatencyUtils | 2.0.3 | CC0-1.0 | Compatible | transitive de io.micrometer:micrometer-registry-prometheus |
| org.slf4j:jul-to-slf4j | 2.0.16 | MIT | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.slf4j:slf4j-api | 2.0.16 | MIT | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springdoc:springdoc-openapi-starter-common | 2.7.0 | Apache-2.0 | Compatible | transitive de org.springdoc:springdoc-openapi-starter-webmvc-ui |
| org.springdoc:springdoc-openapi-starter-webmvc-api | 2.7.0 | Apache-2.0 | Compatible | transitive de org.springdoc:springdoc-openapi-starter-webmvc-ui |
| org.springframework:spring-aop | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework:spring-aspects | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.springframework:spring-beans | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework:spring-context | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework:spring-core | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework:spring-expression | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework:spring-jcl | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework:spring-jdbc | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.springframework:spring-orm | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.springframework:spring-tx | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.springframework:spring-web | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework:spring-webmvc | 6.2.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework.boot:spring-boot | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework.boot:spring-boot-actuator | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-actuator |
| org.springframework.boot:spring-boot-actuator-autoconfigure | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-actuator |
| org.springframework.boot:spring-boot-autoconfigure | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework.boot:spring-boot-starter | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework.boot:spring-boot-starter-jdbc | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.springframework.boot:spring-boot-starter-json | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework.boot:spring-boot-starter-logging | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework.boot:spring-boot-starter-tomcat | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |
| org.springframework.data:spring-data-commons | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.springframework.data:spring-data-jpa | 3.4.1 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-data-jpa |
| org.springframework.security:spring-security-config | 6.4.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-security |
| org.springframework.security:spring-security-core | 6.4.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-security |
| org.springframework.security:spring-security-crypto | 6.4.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-security |
| org.springframework.security:spring-security-web | 6.4.2 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-security |
| org.webjars:swagger-ui | 5.18.2 | Apache-2.0 | Compatible | transitive de org.springdoc:springdoc-openapi-starter-webmvc-ui |
| org.webjars:webjars-locator-lite | 1.0.1 | MIT | Compatible | transitive de org.springdoc:springdoc-openapi-starter-webmvc-ui |
| org.yaml:snakeyaml | 2.3 | Apache-2.0 | Compatible | transitive de org.springframework.boot:spring-boot-starter-web |

## Front-end — dépendances transitives livrées

| Nom | Version | Licence | Compatibilité | Usage |
|---|---|---|---|---|
| @standard-schema/spec | 1.1.0 | MIT | Compatible | transitive de @angular:forms |
| parse5 | 8.0.1 | MIT | Compatible | transitive de @angular:cdk |
| zod | 4.4.2 | MIT | Compatible | transitive de @angular:forms |
