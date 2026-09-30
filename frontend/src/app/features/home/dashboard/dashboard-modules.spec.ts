import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { AuthService } from '../../../core/auth.service';
import { GED_ICONS } from '../../../core/ged-icons';
import { CodeModule, ModulesService } from '../../../core/modules.service';
import { StatTiles } from '../../../core/stat-tiles/stat-tiles';
import { DashboardPrefs } from './dashboard-prefs.service';
import { Personnaliser } from './personnaliser/personnaliser';

/** T-088 : l'accueil ne montre pas les encarts liés à un module désactivé. */
describe('Accueil — modules métier (T-088)', () => {
  const inactifs = new Set<CodeModule>();

  beforeEach(() => {
    inactifs.clear();
    try { localStorage.clear(); } catch { /* stockage indisponible */ }
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: ModulesService, useValue: { charger: () => of(undefined), actif: (c: CodeModule) => !inactifs.has(c) } },
        { provide: MatDialogRef, useValue: { close: () => undefined } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
  });

  it('workflow actif : « À valider » et « Activité récente » sont rendus', () => {
    const prefs = TestBed.inject(DashboardPrefs);
    prefs.basculer('activite');
    expect(prefs.visibles().map(w => w.cle)).toEqual(['indicateurs', 'a-valider', 'raccourcis', 'activite']);
  });

  it('workflow inactif : ni rendus, ni proposés dans « Personnaliser »', () => {
    inactifs.add('workflow');
    const prefs = TestBed.inject(DashboardPrefs);
    prefs.basculer('activite');
    expect(prefs.visibles().map(w => w.cle)).toEqual(['indicateurs', 'raccourcis']);

    const f = TestBed.createComponent(Personnaliser);
    f.detectChanges();
    const titres = [...(f.nativeElement as HTMLElement).querySelectorAll('.perso-nom')].map(e => e.textContent ?? '');
    expect(titres.some(t => t.includes('À valider'))).toBe(false);
    expect(titres.some(t => t.includes('Activité récente'))).toBe(false);
    expect(titres.some(t => t.includes('Raccourcis'))).toBe(true);
  });

  it('workflow inactif : pas de tuile « En attente de signature »', () => {
    inactifs.add('workflow');
    TestBed.inject(AuthService).utilisateur.set({
      id: 'u', identifiant: 'x', employeId: 'e', fullName: 'X', email: null, direction: null,
      roles: ['R'], permissions: ['CONSULTER'],
    });
    const f = TestBed.createComponent(StatTiles);
    f.detectChanges();
    TestBed.inject(HttpTestingController).expectOne(`${API_BASE}/stats/overview`)
      .flush({ workspaces: 1, documents: 2, pendingSignatures: 3, accessGroups: 4 });
    f.detectChanges();
    const hrefs = [...(f.nativeElement as HTMLElement).querySelectorAll('a.tile')].map(a => a.getAttribute('href'));
    expect(hrefs).toEqual(['/espaces-de-travail', '/televerser']);
  });
});
