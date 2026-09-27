import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { codeErreur, erreursParChamp, messageErreur, problemeInterceptor } from './probleme';

const PROBLEME = {
  type: 'urn:ged:erreur:validation-echouee',
  title: 'Requête invalide',
  status: 400,
  detail: 'Données invalides.',
  instance: '/api/v1/etiquettes',
  code: 'VALIDATION_ECHOUEE',
  traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
  erreurs: { tag: 'Le tag est obligatoire.' },
};

describe('Erreurs problem+json', () => {
  let http: HttpClient;
  let serveur: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemeInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    serveur = TestBed.inject(HttpTestingController);
  });

  afterEach(() => serveur.verify());

  it('lit code, detail et erreurs par champ', async () => {
    const appel = firstValueFrom(http.post('/api/v1/etiquettes', {})).catch(e => e);
    serveur.expectOne('/api/v1/etiquettes').flush(PROBLEME, {
      status: 400, statusText: 'Bad Request', headers: { 'Content-Type': 'application/problem+json' },
    });
    const err = await appel as HttpErrorResponse;
    expect(codeErreur(err)).toBe('VALIDATION_ECHOUEE');
    expect(messageErreur(err, 'défaut')).toBe('Données invalides.');
    expect(erreursParChamp(err)).toEqual({ tag: 'Le tag est obligatoire.' });
    // Alias lus par les écrans antérieurs au format problem+json.
    expect(err.error.message).toBe('Données invalides.');
    expect(err.error.errors).toEqual({ tag: 'Le tag est obligatoire.' });
  });

  it('relit un corps d’erreur reçu en Blob (téléchargement)', async () => {
    const appel = firstValueFrom(http.get('/api/v1/documents/x/contenu', { responseType: 'blob' })).catch(e => e);
    const corps = new Blob([JSON.stringify({ ...PROBLEME, status: 422, code: 'FICHIER_INFECTE', detail: 'Infecté.' })],
      { type: 'application/problem+json' });
    serveur.expectOne('/api/v1/documents/x/contenu').flush(corps, {
      status: 422, statusText: 'Unprocessable Entity', headers: { 'Content-Type': 'application/problem+json' },
    });
    const err = await appel as HttpErrorResponse;
    expect(err.status).toBe(422);
    expect(codeErreur(err)).toBe('FICHIER_INFECTE');
    expect(messageErreur(err, 'défaut')).toBe('Infecté.');
  });

  it('rend le libellé par défaut quand le serveur ne répond pas en problem+json', async () => {
    const appel = firstValueFrom(http.get('/api/v1/documents')).catch(e => e);
    serveur.expectOne('/api/v1/documents').flush(null, { status: 0, statusText: 'Unknown Error' });
    const err = await appel as HttpErrorResponse;
    expect(codeErreur(err)).toBeNull();
    expect(messageErreur(err, 'Serveur injoignable.')).toBe('Serveur injoignable.');
  });
});
