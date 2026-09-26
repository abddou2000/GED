#!/usr/bin/env python3
"""Écrit le fichier de test antivirus EICAR à la demande.

Pourquoi il n'est pas versionné tel quel : la chaîne EICAR est reconnue par tous
les antivirus. Écrite en clair dans le dépôt, elle ferait mettre en quarantaine
la copie de travail par Windows Defender, bloquerait les clones sur un poste
protégé et déclencherait les analyseurs de la forge. La chaîne est donc
reconstituée ici à partir de deux moitiés, au moment du test, dans un dossier
temporaire que le script appelant supprime ensuite.

Usage : python generer_eicar.py <fichier_sortie>
        (sortie standard si aucun argument : python generer_eicar.py > eicar.txt)
"""
import sys

# Chaîne standard EICAR (68 octets ASCII), https://www.eicar.org — coupée en deux
# pour que ce fichier source ne soit lui-même jamais signalé.
_A = r"X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-"
_B = r"ANTIVIRUS-TEST-FILE!$H+H*"


def contenu() -> bytes:
    return (_A + _B).encode("ascii")


if __name__ == "__main__":
    donnees = contenu()
    assert len(donnees) == 68, "chaîne EICAR altérée"
    if len(sys.argv) > 1:
        with open(sys.argv[1], "wb") as f:
            f.write(donnees)
    else:
        sys.stdout.buffer.write(donnees)
