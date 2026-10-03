import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { API_BASE } from '../../../core/api';
import { AuthService } from '../../../core/auth.service';
import { GED_ICONS } from '../../../core/ged-icons';
import { NotifyService } from '../../../core/notify.service';
import { JournalAudit } from './journal-audit';

const SARA = '0192a100-0000-7000-8000-000000000001';
const KARIM = '0192a100-0000-7000-8000-000000000002';
const PAGE_VIDE = { content: [], total: 0, page: 0, size: 50, totalPages: 0 };

/**
 * ANO-F-033 : le filtre « Utilisateur » du journal envoyait le texte saisi au
 * serveur, qui attend l'UUID de l'identité GED (400 « attend un UUID »). On
 * saisit le nom ou l'identifiant de connexion, l'écran envoie l'UUID.
 */
describe('JournalAudit — filtre Utilisateur (ANO-F-033)', () => {
  let serveur: HttpTestingController;
  let erreurs: string[];

  function preparer(roles: string[], permissions: string[]): void {
    erreurs = [];
    TestBed.configureTestingModule({
      imports: [JournalAudit],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: NotifyService, useValue: { error: (m: string) => erreurs.push(m), success: () => {}, info: () => {} } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    TestBed.inject(AuthService).utilisateur.set({
      id: SARA, identifiant: 'sbennani', employeId: 'e1', fullName: 'Sara Bennani', email: null, direction: null,
      roles, permissions,
    });
    serveur = TestBed.inject(HttpTestingController);
  }

  /** Écran ouvert, contrôles du formulaire enregistrés (NgForm les ajoute au tour suivant). */
  async function ouvrir(admin: boolean): Promise<ComponentFixture<JournalAudit>> {
    const f = TestBed.createComponent(JournalAudit);
    f.detectChanges();
    serveur.expectOne(r => r.url === `${API_BASE}/employes`).flush([
      { id: 'e1', firstName: 'Sara', lastName: 'Bennani', fullName: 'Sara Bennani', utilisateurId: SARA },
      { id: 'e2', firstName: 'Karim', lastName: 'El Fassi', fullName: 'Karim El Fassi', utilisateurId: KARIM },
      { id: 'e3', firstName: 'Sans', lastName: 'Compte', fullName: 'Sans Compte', utilisateurId: null },
    ]);
    if (admin) {
      serveur.expectOne(`${API_BASE}/admin/utilisateurs`).flush([
        { id: SARA, identifiant: 'sbennani', fullName: 'Sara Bennani', email: null, direction: null, roles: [] },
        { id: KARIM, identifiant: 'kelfassi', fullName: 'Karim El Fassi', email: null, direction: null, roles: [] },
      ]);
    } else {
      serveur.expectNone(`${API_BASE}/admin/utilisateurs`);
    }
    serveur.expectOne(r => r.url === `${API_BASE}/audit/evenements`).flush(PAGE_VIDE);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f;
  }

  /** Saisie réelle dans le champ, puis « Rechercher » ; rend la requête émise, ou null. */
  function rechercher(f: ComponentFixture<JournalAudit>, texte: string) {
    const champ = f.nativeElement.querySelector('input[name="utilisateur"]') as HTMLInputElement;
    champ.value = texte;
    champ.dispatchEvent(new Event('input'));
    f.detectChanges();
    (f.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    f.detectChanges();
    const reqs = serveur.match(r => r.url === `${API_BASE}/audit/evenements`);
    for (const r of reqs) r.flush(PAGE_VIDE);
    return reqs.length ? reqs[0].request : null;
  }

  afterEach(() => serveur.verify());

  it('l\'identifiant de connexion saisi (Administrateur) part sous forme d\'UUID', async () => {
    preparer(['ADMINISTRATEUR'], ['GERER_ROLES_HABILITATIONS', 'CONSULTER_AUDIT']);
    const f = await ouvrir(true);
    expect(rechercher(f, 'KElfassi')?.params.get('utilisateur')).toBe(KARIM);
    expect(erreurs).toEqual([]);
  });

  it('le nom, même sans accent ni casse, part sous forme d\'UUID ; la liste propose nom et identifiant', async () => {
    preparer(['ADMINISTRATEUR'], ['GERER_ROLES_HABILITATIONS', 'CONSULTER_AUDIT']);
    const f = await ouvrir(true);
    const c = f.componentInstance;
    c.saisieUtilisateur.set('benn');
    expect(c.suggestions().map(u => c.libelleUtilisateur(u))).toEqual(['Sara Bennani (sbennani)']);
    expect(rechercher(f, 'karim el fassi')?.params.get('utilisateur')).toBe(KARIM);
  });

  it('la personne choisie dans la liste part sous forme d\'UUID', async () => {
    preparer(['ADMINISTRATEUR'], ['GERER_ROLES_HABILITATIONS', 'CONSULTER_AUDIT']);
    const f = await ouvrir(true);
    const c = f.componentInstance;
    c.saisieUtilisateur.set(c.utilisateurs().find(u => u.identifiant === 'sbennani')!);
    c.rechercher();
    const req = serveur.expectOne(r => r.url === `${API_BASE}/audit/evenements`);
    expect(req.request.params.get('utilisateur')).toBe(SARA);
    req.flush(PAGE_VIDE);
  });

  it('une saisie qui ne désigne personne n\'est pas envoyée et le dit', async () => {
    preparer(['ADMINISTRATEUR'], ['GERER_ROLES_HABILITATIONS', 'CONSULTER_AUDIT']);
    const f = await ouvrir(true);
    expect(rechercher(f, 'inconnu')).toBeNull();
    expect(erreurs[0]).toContain('Utilisateur inconnu');
    // Vider le champ lève le filtre.
    expect(rechercher(f, '')?.params.has('utilisateur')).toBe(false);
  });

  it('Direction Générale (sans /admin) : recherche par le nom, sans appel réservé à l\'Administrateur', async () => {
    preparer(['DIRECTION_GENERALE'], ['CONSULTER_AUDIT']);
    const f = await ouvrir(false);
    expect(f.componentInstance.utilisateurs().map(u => u.nom)).toEqual(['Karim El Fassi', 'Sara Bennani']);
    expect(rechercher(f, 'Sara')?.params.get('utilisateur')).toBe(SARA);
    // Un UUID collé tel quel reste accepté.
    expect(rechercher(f, KARIM)?.params.get('utilisateur')).toBe(KARIM);
  });
});
