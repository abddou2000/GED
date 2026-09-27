import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuditService } from './audit.service';

describe('AuditService', () => {
  let service: AuditService;
  let serveur: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AuditService);
    serveur = TestBed.inject(HttpTestingController);
  });

  afterEach(() => serveur.verify());

  it('ne transmet que les critères renseignés', async () => {
    const appel = firstValueFrom(service.evenements({ action: ' DOCUMENT_TELECHARGE ', objetId: '', resultat: '' }, 2, 50));
    const req = serveur.expectOne(r => r.url === '/api/v1/audit/evenements');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('action')).toBe('DOCUMENT_TELECHARGE');
    expect(req.request.params.has('objetId')).toBe(false);
    expect(req.request.params.has('resultat')).toBe(false);
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ content: [], total: 0, page: 2, size: 50, totalPages: 0 });
    expect((await appel).total).toBe(0);
  });

  it("rend l'empreinte SHA-256 de l'export", async () => {
    const appel = firstValueFrom(service.exporter({}, 'csv'));
    const req = serveur.expectOne(r => r.url === '/api/v1/audit/export');
    expect(req.request.params.get('format')).toBe('csv');
    req.flush(new Blob(['id;action\n']), { headers: { 'X-Empreinte-SHA256': 'ab12' } });
    expect((await appel).headers.get('X-Empreinte-SHA256')).toBe('ab12');
  });

  it('vérifie le scellement par POST, sans autre écriture possible', async () => {
    const appel = firstValueFrom(service.verifier());
    const req = serveur.expectOne('/api/v1/audit/verifications');
    expect(req.request.method).toBe('POST');
    req.flush({ verifieLe: '', periodesVerifiees: 3, enregistrementsVerifies: 10, scelleJusquA: null, anomalies: [] });
    expect((await appel).anomalies).toEqual([]);
  });
});
