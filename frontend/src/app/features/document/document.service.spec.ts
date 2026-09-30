import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../../core/api';
import { DocumentService } from './document.service';

describe('DocumentService', () => {
  let service: DocumentService;
  let serveur: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(DocumentService);
    serveur = TestBed.inject(HttpTestingController);
  });

  it("ANO-F-006 : le dépôt transmet l'objet et la date du document (POST /documents)", () => {
    const fichier = new File(['x'], 'facture.pdf', { type: 'application/pdf' });
    service.upload(fichier, 't1', '', null, [], null, undefined,
      { objet: '  Maintenance annuelle ', dateDocument: '2026-03-12' }).subscribe();
    const req = serveur.expectOne(r => r.method === 'POST' && r.url === `${API_BASE}/documents`);
    const corps = req.request.body as FormData;
    expect(corps.get('objet')).toBe('Maintenance annuelle');
    expect(corps.get('dateDocument')).toBe('2026-03-12');
    req.flush({ id: 'd1' });
  });

  it("n'envoie ni objet vide ni date absente : le serveur applique la date du dépôt", () => {
    const fichier = new File(['x'], 'facture.pdf', { type: 'application/pdf' });
    service.upload(fichier, 't1', '', null, [], null, undefined, { objet: '  ', dateDocument: null }).subscribe();
    const corps = serveur.expectOne(`${API_BASE}/documents`).request.body as FormData;
    expect(corps.has('objet')).toBe(false);
    expect(corps.has('dateDocument')).toBe(false);
  });
});
