import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { API_BASE } from './api';
import { ModulesService } from './modules.service';

/** T-088 : état des modules métier lu sur `GET /modules`. */
describe('ModulesService', () => {
  let modules: ModulesService;
  let serveur: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    modules = TestBed.inject(ModulesService);
    serveur = TestBed.inject(HttpTestingController);
  });

  afterEach(() => serveur.verify());

  it('tient un module pour actif tant que l\'état est inconnu', () => {
    expect(modules.actif('workflow')).toBe(true);
  });

  it('retient les modules déclarés inactifs par le serveur, une seule fois', async () => {
    const fin = firstValueFrom(modules.charger());
    modules.charger().subscribe();   // appel simultané : même aller-retour
    serveur.expectOne(`${API_BASE}/modules`).flush([
      { code: 'workflow', libelle: 'Circuits', reference: '', actif: false },
      { code: 'export', libelle: 'Export', reference: '', actif: true },
    ]);
    await fin;
    expect(modules.actif('workflow')).toBe(false);
    expect(modules.actif('export')).toBe(true);
    expect(modules.actif('ocr')).toBe(true);   // non décrit : actif
    await firstValueFrom(modules.charger());   // déjà connu : aucun nouvel appel
  });

  it('reste permissif si le serveur ne répond pas, et réessaie ensuite', async () => {
    const fin = firstValueFrom(modules.charger());
    serveur.expectOne(`${API_BASE}/modules`).flush('panne', { status: 503, statusText: 'Indisponible' });
    await fin;
    expect(modules.actif('notifications')).toBe(true);
    modules.charger().subscribe();
    serveur.expectOne(`${API_BASE}/modules`).flush([]);
  });

  it('ignore une réponse qui n\'est pas une liste (mode démonstration)', async () => {
    const fin = firstValueFrom(modules.charger());
    serveur.expectOne(`${API_BASE}/modules`).flush({ content: [] });
    await fin;
    expect(modules.actif('ocr')).toBe(true);
    await firstValueFrom(modules.charger());   // état connu : aucun nouvel appel
  });
});
