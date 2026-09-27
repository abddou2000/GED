import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../../core/api';
import { ClesApiService } from './cles-api.service';

describe('ClesApiService', () => {
  let service: ClesApiService;
  let serveur: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ClesApiService);
    serveur = TestBed.inject(HttpTestingController);
  });

  afterEach(() => serveur.verify());

  it('génère une clé en transmettant le choix de délégation', () => {
    service.generer('app-1', true).subscribe(r => expect(r.cle).toBe('ged_dev_0123456789abcdef_x'));
    const req = serveur.expectOne(`${API_BASE}/applications/app-1/cles`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ delegation: true });
    req.flush({ cle: 'ged_dev_0123456789abcdef_x', details: {} });
  });

  it('régénère et révoque par identifiant de clé, avec le motif', () => {
    service.regenerer('c-1').subscribe();
    service.revoquer('c-1', 'fuite').subscribe();
    const reg = serveur.expectOne(`${API_BASE}/cles-api/c-1/regeneration`);
    expect(reg.request.method).toBe('POST');
    const rev = serveur.expectOne(`${API_BASE}/cles-api/c-1/revocation`);
    expect(rev.request.body).toEqual({ motif: 'fuite' });
    reg.flush({});
    rev.flush({});
  });

  it("bascule l'activation d'une application", () => {
    service.activer('app-1', false).subscribe();
    const req = serveur.expectOne(`${API_BASE}/applications/app-1/activation`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ active: false });
    req.flush({});
  });
});
