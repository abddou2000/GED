import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { idempotenceInterceptor } from './idempotence';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('idempotenceInterceptor', () => {
  let http: HttpClient;
  let serveur: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([idempotenceInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    serveur = TestBed.inject(HttpTestingController);
  });

  afterEach(() => serveur.verify());

  it('pose une clé UUID neuve sur chaque création', () => {
    http.post('/api/v1/documents', {}).subscribe();
    http.post('/api/v1/documents', {}).subscribe();
    const [a, b] = serveur.match('/api/v1/documents');
    expect(a.request.headers.get('Idempotency-Key')).toMatch(UUID);
    expect(b.request.headers.get('Idempotency-Key')).not.toBe(a.request.headers.get('Idempotency-Key'));
    a.flush({}); b.flush({});
  });

  it("ne touche ni aux lectures, ni à la connexion, ni à une clé déjà posée", () => {
    http.get('/api/v1/documents').subscribe();
    http.post('/api/v1/auth/login', {}).subscribe();
    http.post('/api/v1/workspaces', {}, { headers: { 'Idempotency-Key': 'fixe' } }).subscribe();
    expect(serveur.expectOne('/api/v1/documents').request.headers.has('Idempotency-Key')).toBe(false);
    expect(serveur.expectOne('/api/v1/auth/login').request.headers.has('Idempotency-Key')).toBe(false);
    expect(serveur.expectOne('/api/v1/workspaces').request.headers.get('Idempotency-Key')).toBe('fixe');
  });
});
