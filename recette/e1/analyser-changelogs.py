#!/usr/bin/env python3
"""Recette E1 — analyse statique des changelogs Liquibase et revue de configuration.

Complète verifier-socle.sh (qui lit la base) par ce qui ne se voit que dans les
sources : convention de nommage des fichiers, rollback de chaque changeset,
étiquetage data-initial, absence de DDL hors Liquibase (ddl-auto, schema.sql,
Flyway), amorçage par code Java.

Bibliothèque standard seulement (exécutable sur un serveur d'intégration nu).

Usage : python analyser-changelogs.py [--backend CHEMIN] [--maitre FICHIER]
Sortie : RESULTAT|id|OK/ECHEC/AVERT/NA|libellé|détail, puis BILAN. Code 0 si aucun ECHEC.
Références DAT V3 : §2.2, §4.2.1, §4.2.2, §12.1.
"""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

NOM_FICHIER = re.compile(r"^(\d{12})_[a-z0-9]+(?:_[a-z0-9]+)*\.xml$")
SNAKE = re.compile(r"^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")

# Changements dont Liquibase sait générer seul le retour arrière (documentation
# Liquibase 4, « auto rollback »). Tout autre changement exige un <rollback>.
AUTO_REVERSIBLES = {
    "addColumn", "addDefaultValue", "addForeignKeyConstraint", "addLookupTable", "addNotNullConstraint",
    "addPrimaryKey", "addUniqueConstraint", "createIndex", "createSequence", "createTable", "createView",
    "dropNotNullConstraint", "renameColumn", "renameSequence", "renameTable", "renameView", "tagDatabase",
}
# Éléments d'un changeSet qui ne sont pas des changements.
NON_CHANGEMENTS = {"comment", "preConditions", "rollback", "validCheckSum", "modifySql"}
CHANGEMENTS_DONNEES = {"insert", "update", "delete", "loadData", "loadUpdateData"}
DESTRUCTIFS = {"dropTable", "dropColumn", "modifyDataType", "renameColumn", "renameTable", "mergeColumns", "dropView"}
SQL_DONNEES = re.compile(r"\b(insert\s+into|update\s+\w|delete\s+from|merge\s+into|copy\s+\w)", re.I)
SQL_DDL = re.compile(r"\b(create|alter|drop)\s+(table|index|unique\s+index|view|sequence|function|trigger|type|schema)\b", re.I)

# Référentiels métier : créés depuis l'interface uniquement (DAT §4.2.1, principe P1).
REFERENTIELS = {"type_document", "index_def", "plan_indexation", "plan_index", "noeud", "regle_workflow",
                "regle_validateur", "groupe_ged", "groupe_membre", "habilitation", "application", "cle_api",
                "cle_api_portee", "document", "utilisateur"}

CATALOGUE = [
    ("E1-A01", "Changelog maître présent [4.2.2]"),
    ("E1-A02", "Le maître ne fait qu'inclure des fichiers [4.2.2]"),
    ("E1-A03", "Un fichier par évolution nommé AAAAMMJJHHmm_objet.xml [4.2.2]"),
    ("E1-A04", "Ordre d'application déterministe et chronologique [4.2.2]"),
    ("E1-A05", "Rollback explicite sur chaque changeset non auto-réversible [4.2.2]"),
    ("E1-A06", "Rollback vide justifié [4.2.2]"),
    ("E1-A07", "Changesets de données étiquetés data-initial [4.2.1]"),
    ("E1-A08", "Aucun référentiel métier amorcé par changeset [4.2.1]"),
    ("E1-A09", "Identifiants de changeset uniques [4.2.2]"),
    ("E1-A10", "Objets déclarés en snake_case, idx_, uk_, fk_, ck_ [4.2.2]"),
    ("E1-A11", "Colonnes id déclarées en uuid [12.1]"),
    ("E1-A12", "Opérations destructives signalées pour revue expand/contract [4.2.2]"),
    ("E1-A20", "Hibernate ne modifie pas le schéma (ddl-auto validate ou none) [4.2.1]"),
    ("E1-A21", "Liquibase présent, Flyway absent des dépendances [2.2]"),
    ("E1-A22", "Aucun script schema.sql / data.sql hors Liquibase [4.2.1]"),
    ("E1-A23", "Pas d'amorçage de données par code Java au démarrage [4.2.1]"),
    ("E1-A24", "Pilote PostgreSQL présent, MySQL retiré [2.2]"),
    ("E1-A25", "Profil de test sur PostgreSQL (pas H2) [critère de sortie E1]"),
]


class Constats:
    def __init__(self) -> None:
        self.par_id: dict[str, list[tuple[str, str]]] = {i: [] for i, _ in CATALOGUE}
        self.na: dict[str, str] = {}

    def ajouter(self, cid: str, gravite: str, texte: str) -> None:
        self.par_id[cid].append((gravite, texte))


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def trouver_maitre(backend: Path) -> Path | None:
    racine = backend / "src/main/resources"
    candidats = [p for p in racine.rglob("*.xml")
                 if re.search(r"(master|maitre|changelog)", p.name, re.I) and not NOM_FICHIER.match(p.name)]
    candidats = [p for p in candidats if b"databaseChangeLog" in p.read_bytes()[:4000]]
    return sorted(candidats, key=lambda p: len(p.parts))[0] if candidats else None


def fichiers_inclus(maitre: Path, racine_cp: Path, c: Constats) -> list[Path]:
    arbre = ET.parse(maitre).getroot()
    inclus: list[Path] = []
    for el in arbre:
        nom = local(el.tag)
        if nom == "changeSet":
            c.ajouter("E1-A02", "ERREUR", f"{maitre.name} : changeSet {el.get('id')} écrit dans le maître")
        elif nom == "include":
            f = el.get("file", "")
            base = maitre.parent if el.get("relativeToChangelogFile") == "true" else racine_cp
            p = (base / f.removeprefix("classpath:").lstrip("/")).resolve()
            if not p.exists():
                p = (maitre.parent / f).resolve()
            inclus.append(p)
        elif nom == "includeAll":
            base = maitre.parent if el.get("relativeToChangelogFile") == "true" else racine_cp
            d = (base / el.get("path", "").removeprefix("classpath:").lstrip("/")).resolve()
            inclus += sorted(x for x in d.rglob("*") if x.is_file())
    return inclus


def analyser_changelog(fichier: Path, c: Constats, vus: set[tuple[str, str, str]]) -> None:
    try:
        racine = ET.parse(fichier).getroot()
    except ET.ParseError as e:
        c.ajouter("E1-A03", "ERREUR", f"{fichier.name} : XML illisible ({e})")
        return
    for cs in racine.iter():
        if local(cs.tag) != "changeSet":
            continue
        ident = (cs.get("id", ""), cs.get("author", ""), fichier.name)
        ref = f"{fichier.name}::{ident[0]}"
        if ident in vus:
            c.ajouter("E1-A09", "ERREUR", f"{ref} : identifiant dupliqué")
        vus.add(ident)
        enfants = [e for e in cs if local(e.tag) not in NON_CHANGEMENTS]
        rollbacks = [e for e in cs if local(e.tag) == "rollback"]
        noms = {local(e.tag) for e in enfants}
        non_auto = sorted(n for n in noms if n not in AUTO_REVERSIBLES)
        if non_auto and not rollbacks:
            c.ajouter("E1-A05", "ERREUR", f"{ref} : {', '.join(non_auto)} sans <rollback>")
        for rb in rollbacks:
            if len(rb) == 0 and not (rb.text or "").strip():
                c.ajouter("E1-A06", "AVERT", f"{ref} : <rollback/> vide (retour arrière volontairement nul, à justifier)")
        contextes = f"{cs.get('context', '')} {cs.get('contextFilter', '')} {cs.get('labels', '')}".lower()
        sql_textes = " ".join((e.text or "") for e in enfants if local(e.tag) == "sql")
        est_donnees = bool(noms & CHANGEMENTS_DONNEES) or bool(SQL_DONNEES.search(sql_textes))
        if est_donnees and "data-initial" not in contextes:
            c.ajouter("E1-A07", "ERREUR", f"{ref} : changement de données sans contexte ni label data-initial")
        if est_donnees:
            cibles = {e.get("tableName", "") for e in enfants if local(e.tag) in CHANGEMENTS_DONNEES}
            cibles |= {m.group(1).lower() for m in re.finditer(r"insert\s+into\s+(?:\w+\.)?\"?(\w+)", sql_textes, re.I)}
            for t in sorted(cibles & REFERENTIELS):
                c.ajouter("E1-A08", "ERREUR", f"{ref} : alimente le référentiel métier {t}")
        for n in sorted(noms & DESTRUCTIFS):
            c.ajouter("E1-A12", "AVERT", f"{ref} : {n} (vérifier expand/contract, sauvegarde ciblée, rollback testé en UAT)")
        controler_noms(cs, ref, c)


def controler_noms(cs: ET.Element, ref: str, c: Constats) -> None:
    for e in cs.iter():
        n = local(e.tag)
        if n in {"createTable", "renameTable"}:
            t = e.get("tableName") or e.get("newTableName") or ""
            if t and not SNAKE.match(t):
                c.ajouter("E1-A10", "ERREUR", f"{ref} : table « {t} » non snake_case")
        if n == "column":
            col = e.get("name", "")
            if col and not SNAKE.match(col):
                c.ajouter("E1-A10", "ERREUR", f"{ref} : colonne « {col} » non snake_case")
            if col == "id" and e.get("type") and "uuid" not in e.get("type", "").lower():
                c.ajouter("E1-A11", "ERREUR", f"{ref} : colonne id de type {e.get('type')}")
            if (e.get("autoIncrement") or "").lower() == "true":
                c.ajouter("E1-A11", "ERREUR", f"{ref} : colonne {col} autoIncrement")
        if n == "constraints":
            for attr, prefixe in (("foreignKeyName", "fk_"), ("uniqueConstraintName", "uk_"), ("checkConstraint", None)):
                v = e.get(attr)
                if v and prefixe and not v.startswith(prefixe):
                    c.ajouter("E1-A10", "ERREUR", f"{ref} : {attr}=« {v} » sans préfixe {prefixe}")
        if n == "createIndex":
            v = e.get("indexName", "")
            ok = v.startswith("idx_") or ((e.get("unique") or "").lower() == "true" and v.startswith("uk_"))
            if not ok:
                c.ajouter("E1-A10", "ERREUR", f"{ref} : index « {v} » sans préfixe idx_")
        for balise, attr, prefixe in (("addForeignKeyConstraint", "constraintName", "fk_"),
                                      ("addUniqueConstraint", "constraintName", "uk_")):
            if n == balise and not e.get(attr, "").startswith(prefixe):
                c.ajouter("E1-A10", "ERREUR", f"{ref} : {balise} « {e.get(attr)} » sans préfixe {prefixe}")
        if n == "sql":
            for m in re.finditer(r"constraint\s+\"?(\w+)\"?\s+(check|unique|foreign\s+key)", e.text or "", re.I):
                attendu = {"check": "ck_", "unique": "uk_"}.get(m.group(2).lower(), "fk_")
                if not m.group(1).lower().startswith(attendu):
                    c.ajouter("E1-A10", "ERREUR", f"{ref} : contrainte SQL « {m.group(1)} » sans préfixe {attendu}")
            for m in re.finditer(r"create\s+(unique\s+)?index\s+(?:concurrently\s+)?(?:if\s+not\s+exists\s+)?\"?(\w+)", e.text or "", re.I):
                if not (m.group(2).startswith("idx_") or (m.group(1) and m.group(2).startswith("uk_"))):
                    c.ajouter("E1-A10", "ERREUR", f"{ref} : index SQL « {m.group(2)} » sans préfixe idx_")


def revue_configuration(backend: Path, c: Constats) -> None:
    res = backend / "src/main/resources"
    for f in sorted(list(res.glob("application*.yml")) + list(res.glob("application*.yaml")) + list(res.glob("application*.properties"))):
        texte = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r"ddl-auto\s*[:=]\s*\$?\{?([\w:-]+)", texte):
            valeur = m.group(1).split(":")[-1]
            if valeur not in {"validate", "none"}:
                c.ajouter("E1-A20", "ERREUR", f"{f.name} : ddl-auto = {m.group(1)}")
        if re.search(r"^\s*flyway\s*:", texte, re.M) and re.search(r"enabled\s*:\s*true", texte):
            c.ajouter("E1-A21", "ERREUR", f"{f.name} : configuration Flyway activée")
        m = re.search(r"sql\s*:\s*\n\s*init\s*:\s*\n\s*mode\s*:\s*(\w+)", texte)
        if m and m.group(1) != "never":
            c.ajouter("E1-A22", "ERREUR", f"{f.name} : spring.sql.init.mode = {m.group(1)}")
        if "jdbc:h2" in texte:
            c.ajouter("E1-A25", "AVERT", f"{f.name} : URL H2 encore présente dans un profil applicatif")
        if "jdbc:mysql" in texte:
            c.ajouter("E1-A24", "ERREUR", f"{f.name} : URL MySQL encore présente")
    for nom in ("schema.sql", "data.sql", "import.sql"):
        for f in res.rglob(nom):
            c.ajouter("E1-A22", "ERREUR", f"{f.relative_to(backend)} présent (exécuté hors Liquibase)")
    for f in res.rglob("db/migration/V*__*.sql"):
        c.ajouter("E1-A22", "ERREUR", f"{f.relative_to(backend)} : migration Flyway résiduelle")

    pom = backend / "pom.xml"
    if pom.exists():
        p = pom.read_text(encoding="utf-8", errors="replace")
        if "flyway" in p.lower():
            c.ajouter("E1-A21", "ERREUR", "pom.xml : dépendance ou plugin Flyway présent")
        if "liquibase-core" not in p:
            c.ajouter("E1-A21", "ERREUR", "pom.xml : liquibase-core absent")
        if "org.postgresql" not in p:
            c.ajouter("E1-A24", "ERREUR", "pom.xml : pilote org.postgresql absent")
        if "mysql-connector" in p:
            c.ajouter("E1-A24", "AVERT", "pom.xml : pilote MySQL encore présent (acceptable seulement pour l'outil de reprise)")

    src = backend / "src/main/java"
    for f in src.rglob("*.java"):
        t = f.read_text(encoding="utf-8", errors="replace")
        if re.search(r"implements\s+(CommandLineRunner|ApplicationRunner)|ApplicationReadyEvent", t) and re.search(r"\.save(All)?\(", t):
            c.ajouter("E1-A23", "AVERT", f"{f.relative_to(backend)} : écrit en base au démarrage (revoir : amorçage par changeset data-initial)")

    test_cfg = backend / "src/test/resources"
    for f in test_cfg.glob("application*.y*ml"):
        if "jdbc:h2" in f.read_text(encoding="utf-8", errors="replace"):
            c.ajouter("E1-A25", "ERREUR", f"src/test/resources/{f.name} : les tests tournent encore sur H2")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    depot = Path(__file__).resolve().parents[2]
    ap.add_argument("--backend", type=Path, default=depot / "backend")
    ap.add_argument("--maitre", type=Path, help="changelog maître (détecté sinon)")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    c = Constats()
    maitre = args.maitre or trouver_maitre(args.backend)
    if maitre is None or not maitre.exists():
        c.ajouter("E1-A01", "ERREUR", f"aucun changelog maître Liquibase sous {args.backend / 'src/main/resources'}")
        for cid in ("E1-A02", "E1-A03", "E1-A04", "E1-A05", "E1-A06", "E1-A07", "E1-A08", "E1-A09", "E1-A10", "E1-A11", "E1-A12"):
            c.na[cid] = "pas de changelog à analyser"
    else:
        print(f"# changelog maître : {maitre}")
        racine_cp = next((p for p in maitre.parents if p.name == "resources"), maitre.parent)
        inclus = fichiers_inclus(maitre, racine_cp, c)
        horodatages = []
        vus: set[tuple[str, str, str]] = set()
        for f in inclus:
            m = NOM_FICHIER.match(f.name)
            if not f.exists():
                c.ajouter("E1-A03", "ERREUR", f"{f} : fichier inclus introuvable")
                continue
            if not m:
                c.ajouter("E1-A03", "ERREUR", f"{f.name} : nom hors convention AAAAMMJJHHmm_objet_metier.xml")
            else:
                try:
                    datetime.strptime(m.group(1), "%Y%m%d%H%M")
                    horodatages.append((m.group(1), f.name))
                except ValueError:
                    c.ajouter("E1-A03", "ERREUR", f"{f.name} : horodatage {m.group(1)} invalide")
            if f.suffix == ".xml":
                analyser_changelog(f, c, vus)
        for (h1, n1), (h2, n2) in zip(horodatages, horodatages[1:]):
            if h2 < h1:
                c.ajouter("E1-A04", "AVERT", f"{n2} inclus après {n1} (ordre non chronologique)")
        print(f"# {len(inclus)} fichier(s) de changeset analysé(s)")
    revue_configuration(args.backend, c)

    echecs = 0
    for cid, libelle in CATALOGUE:
        lignes = c.par_id[cid]
        err = [t for g, t in lignes if g == "ERREUR"]
        av = [t for g, t in lignes if g == "AVERT"]
        detail = " ; ".join((err or av)[:6]) + (f" … ({len(err or av)} au total)" if len(err or av) > 6 else "")
        if err:
            statut = "ECHEC"; echecs += 1; detail = f"{len(err)} écart(s) : {detail}"
        elif av:
            statut = "AVERT"; detail = f"{len(av)} point(s) : {detail}"
        elif cid in c.na:
            statut = "NA"; detail = c.na[cid]
        else:
            statut = "OK"; detail = ""
        print(f"RESULTAT|{cid}|{statut}|{libelle}|{detail}")
    nb = {s: 0 for s in ("OK", "ECHEC", "AVERT", "NA")}
    # Recompte pour le bilan (même format que lib/commun.sh).
    for cid, _ in CATALOGUE:
        lignes = c.par_id[cid]
        s = "ECHEC" if any(g == "ERREUR" for g, _ in lignes) else "AVERT" if lignes else "NA" if cid in c.na else "OK"
        nb[s] += 1
    print(f"BILAN|E1 changelogs et configuration|ok={nb['OK']}|echec={nb['ECHEC']}|avert={nb['AVERT']}|na={nb['NA']}")
    return 1 if echecs else 0


if __name__ == "__main__":
    sys.exit(main())
