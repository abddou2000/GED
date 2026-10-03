import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, JsonPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EmployeService } from '../../../core/employe.service';
import { NotifyService } from '../../../core/notify.service';
import { messageErreur } from '../../../core/probleme';
import { SkeletonTable } from '../../../core/skeleton-table/skeleton-table';
import { AuditService, FiltreAudit, LigneAudit } from '../audit.service';

/** Personne proposée par le filtre « Utilisateur » : son identité GED (UUID), son nom, son identifiant de connexion s'il est connu. */
export interface UtilisateurAudit {
  id: string;
  nom: string;
  identifiant: string | null;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Minuscules sans accents : « Bénnani » se trouve en tapant « benn ». */
function normaliser(texte: string): string {
  return texte.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
}

/**
 * Écran « Journal d'audit » (DAT §7.4.3) : consultation filtrée par période,
 * utilisateur, application, action, objet et résultat ; export CSV et JSON
 * avec empreinte ; vérification du scellement.
 *
 * <p>Lecture seule : aucune action ne modifie ni ne supprime un enregistrement
 * (revue technique D11). Chaque consultation et chaque export sont eux-mêmes
 * tracés par le serveur.
 */
@Component({
  selector: 'app-journal-audit',
  imports: [FormsModule, DatePipe, JsonPipe, MatAutocompleteModule, MatButtonModule, MatIconModule,
    MatPaginatorModule, MatTooltipModule, SkeletonTable],
  templateUrl: './journal-audit.html',
  styleUrl: './journal-audit.scss',
})
export class JournalAudit implements OnInit {
  private service = inject(AuditService);
  private notify = inject(NotifyService);
  private employes = inject(EmployeService);

  readonly RESULTATS = [
    { valeur: '', libelle: 'Tous' },
    { valeur: 'SUCCES', libelle: 'Succès' },
    { valeur: 'REFUS', libelle: 'Refus' },
    { valeur: 'ECHEC', libelle: 'Échec' },
  ];

  /** Saisie des critères ; `du`/`au` au format du champ datetime-local (heure locale). */
  criteres: FiltreAudit = {};
  lignes = signal<LigneAudit[]>([]);
  total = signal(0);
  page = signal(0);
  taille = signal(50);
  chargement = signal(true);
  ouverte = signal<number | null>(null);
  verification = signal<string | null>(null);

  /**
   * Filtre « Utilisateur » (ANO-F-033) : le serveur attend l'UUID de
   * l'identité GED. On saisit le nom ou l'identifiant de connexion, on choisit
   * dans la liste, et c'est l'UUID qui part. Valeur : le texte tapé, ou la
   * personne choisie (l'autocomplétion pose l'objet).
   */
  utilisateurs = signal<UtilisateurAudit[]>([]);
  saisieUtilisateur = signal<string | UtilisateurAudit>('');
  suggestions = computed(() => {
    const v = this.saisieUtilisateur();
    const texte = normaliser(typeof v === 'string' ? v : v.nom);
    const liste = this.utilisateurs();
    if (!texte) return liste.slice(0, 20);
    return liste.filter(u => normaliser(u.nom).includes(texte) || normaliser(u.identifiant ?? '').includes(texte))
      .slice(0, 20);
  });
  readonly libelleUtilisateur = (u: UtilisateurAudit | string | null): string =>
    !u ? '' : typeof u === 'string' ? u : u.identifiant ? `${u.nom} (${u.identifiant})` : u.nom;

  ngOnInit(): void {
    this.chargerUtilisateurs();
    this.charger();
  }

  /**
   * Personnes dotées d'une identité GED (`GET /employes?has_user=1` : nom et
   * UUID). Le serveur y ajoute l'identifiant de connexion pour qui peut
   * consulter le journal (CONSULTER_AUDIT : Administrateur et Direction
   * Générale, ANO-F-036) : c'est lui que la colonne « Acteur » affiche. Un
   * échec rend la liste vide, jamais une erreur.
   */
  private chargerUtilisateurs(): void {
    this.employes.personnes().subscribe(personnes => this.utilisateurs.set(personnes.map(p =>
      ({ id: p.utilisateurId, nom: p.nom, identifiant: p.identifiant ?? null }))));
  }

  /**
   * UUID à transmettre pour la saisie du filtre : la personne choisie, sinon
   * un UUID collé tel quel, une correspondance exacte (identifiant, nom), ou
   * la seule personne que la saisie partielle désigne (« Sara »).
   * `null` si rien n'est saisi ; `undefined` si la saisie ne désigne personne.
   */
  private utilisateurChoisi(): string | null | undefined {
    const v = this.saisieUtilisateur();
    if (typeof v !== 'string') return v.id;
    const texte = v.trim();
    if (!texte) return null;
    if (UUID.test(texte)) return texte;
    const cle = normaliser(texte);
    const exacts = this.utilisateurs().filter(u =>
      normaliser(u.identifiant ?? '') === cle || normaliser(u.nom) === cle || normaliser(this.libelleUtilisateur(u)) === cle);
    if (exacts.length === 1) return exacts[0].id;
    const proches = this.suggestions();
    return proches.length === 1 ? proches[0].id : undefined;
  }

  rechercher(): void {
    this.page.set(0);
    this.charger();
  }

  reinitialiser(): void {
    this.criteres = {};
    this.saisieUtilisateur.set('');
    this.rechercher();
  }

  changerPage(e: PageEvent): void {
    this.page.set(e.pageIndex);
    this.taille.set(e.pageSize);
    this.charger();
  }

  basculer(id: number): void {
    this.ouverte.set(this.ouverte() === id ? null : id);
  }

  charger(): void {
    const filtre = this.filtre();
    if (!filtre) return;
    this.chargement.set(true);
    this.service.evenements(filtre, this.page(), this.taille()).subscribe({
      next: p => {
        this.lignes.set(p.content);
        this.total.set(p.total);
        this.chargement.set(false);
      },
      error: err => {
        this.chargement.set(false);
        this.notify.error(messageErreur(err, 'Consultation du journal impossible.'));
      },
    });
  }

  exporter(format: 'csv' | 'json'): void {
    const filtre = this.filtre();
    if (!filtre) return;
    this.service.exporter(filtre, format).subscribe({
      next: rep => {
        const nom = nomDepuisEntete(rep.headers.get('Content-Disposition')) ?? `journal-audit.${format}`;
        if (rep.body) enregistrer(rep.body, nom);
        const empreinte = rep.headers.get('X-Empreinte-SHA256');
        this.notify.success(`Export enregistré${empreinte ? ` — SHA-256 ${empreinte.slice(0, 16)}…` : ''}`);
      },
      error: err => this.notify.error(messageErreur(err, 'Export impossible.')),
    });
  }

  verifier(): void {
    this.verification.set('Vérification en cours…');
    this.service.verifier().subscribe({
      next: r => {
        const texte = r.anomalies.length === 0
          ? `Chaîne intègre : ${r.periodesVerifiees} périodes, ${r.enregistrementsVerifies} enregistrements vérifiés.`
          : `${r.anomalies.length} anomalie(s) : ${r.anomalies[0].type}${r.anomalies[0].periodeDebut ? ' (' + r.anomalies[0].periodeDebut + ')' : ''}.`;
        this.verification.set(texte);
        if (r.anomalies.length === 0) this.notify.success('Scellement vérifié : aucune anomalie.');
        else this.notify.error('Scellement : anomalies détectées.');
      },
      error: err => {
        this.verification.set(null);
        this.notify.error(messageErreur(err, 'Vérification impossible.'));
      },
    });
  }

  /**
   * Les champs datetime-local sont en heure locale : on les convertit en
   * instants UTC. `null` (et un message) si le filtre « Utilisateur » ne
   * désigne personne : rien n'est demandé au serveur.
   */
  private filtre(): FiltreAudit | null {
    const utilisateur = this.utilisateurChoisi();
    if (utilisateur === undefined) {
      this.notify.error('Utilisateur inconnu : choisissez une personne dans la liste (nom ou identifiant de connexion).');
      return null;
    }
    return {
      ...this.criteres,
      utilisateur: utilisateur ?? undefined,
      du: this.criteres.du ? new Date(this.criteres.du).toISOString() : undefined,
      au: this.criteres.au ? new Date(this.criteres.au).toISOString() : undefined,
    };
  }
}

function nomDepuisEntete(entete: string | null): string | null {
  if (!entete) return null;
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(entete);
  if (utf8) return decodeURIComponent(utf8[1]);
  const simple = /filename="?([^";]+)"?/i.exec(entete);
  return simple ? simple[1] : null;
}

function enregistrer(blob: Blob, nom: string): void {
  const href = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = href;
    a.download = nom;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(href));
  }
}
