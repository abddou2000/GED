package com.ipt.ged.fichier;

import com.ipt.ged.fichier.cles.CleEnveloppee;
import com.ipt.ged.fichier.cles.CleFichier;
import com.ipt.ged.fichier.cles.DepotClesFichier;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Double de test de la table {@code cle_fichier}, pour exercer le chiffrement
 * sans base. L'implémentation réelle (JDBC) a son propre test.
 */
public class DepotClesFichierMemoire implements DepotClesFichier {

    private final TreeMap<UUID, CleFichier> lignes = new TreeMap<>();
    /** Simule une panne de base à l'enregistrement. */
    public volatile boolean echouerAEnregistrement;

    @Override
    public synchronized void enregistrer(CleFichier cle) {
        if (echouerAEnregistrement) throw new IllegalStateException("panne simulée");
        if (lignes.putIfAbsent(cle.id(), cle) != null) throw new IllegalStateException("clé déjà présente");
    }

    @Override
    public synchronized Optional<CleFichier> trouver(UUID id) {
        return Optional.ofNullable(lignes.get(id));
    }

    @Override
    public synchronized boolean supprimer(UUID id) {
        return lignes.remove(id) != null;
    }

    @Override
    public synchronized List<CleFichier> lotHorsKek(String kekActive, UUID apres, int taille) {
        return (apres == null ? lignes : lignes.tailMap(apres, false)).values().stream()
                .filter(c -> !c.kekId().equals(kekActive))
                .limit(taille)
                .toList();
    }

    @Override
    public synchronized boolean remplacerEnveloppe(UUID id, String ancienneKek, CleEnveloppee nouvelle) {
        CleFichier c = lignes.get(id);
        if (c == null || !c.kekId().equals(ancienneKek)) return false;
        lignes.put(id, new CleFichier(id, nouvelle.octets(), nouvelle.kekId(), c.algorithme()));
        return true;
    }

    @Override
    public synchronized long compterParKek(String kekId) {
        return lignes.values().stream().filter(c -> c.kekId().equals(kekId)).count();
    }

    public synchronized int taille() {
        return lignes.size();
    }
}
