package com.ipt.ged.fichier.cles;

/**
 * Une clé nécessaire au déchiffrement n'est pas disponible : KEK inconnue du
 * fournisseur, enveloppe altérée, ou DEK détruite par une purge.
 */
public class CleIndisponibleException extends RuntimeException {

    public CleIndisponibleException(String message) {
        super(message);
    }

    public CleIndisponibleException(String message, Throwable cause) {
        super(message, cause);
    }
}
