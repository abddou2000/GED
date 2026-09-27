import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../../core/api';
import { NotificationsService } from './notifications.service';

describe('NotificationsService', () => {
  let service: NotificationsService;
  let serveur: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(NotificationsService);
    serveur = TestBed.inject(HttpTestingController);
  });

  afterEach(() => serveur.verify());

  it('alimente la pastille et reste silencieux en cas d\'échec', () => {
    service.rafraichirCompteur();
    serveur.expectOne(`${API_BASE}/notifications/compteur`).flush({ nonLues: 4 });
    expect(service.nonLues()).toBe(4);
    service.rafraichirCompteur();
    serveur.expectOne(`${API_BASE}/notifications/compteur`).flush(null, { status: 500, statusText: 'Erreur' });
    expect(service.nonLues()).toBe(4);
  });

  it('liste les non lues avec la pagination demandée', () => {
    service.lister(true, 2).subscribe();
    const req = serveur.expectOne(r => r.url === `${API_BASE}/notifications`);
    expect(req.request.params.get('nonLues')).toBe('true');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('50');
    req.flush({ content: [], total: 0, page: 2, size: 50, totalPages: 0 });
  });

  it('marque lue puis recompte ; « tout marquer » remet la pastille à zéro', () => {
    service.nonLues.set(3);
    service.marquerLue('n-1').subscribe();
    serveur.expectOne(`${API_BASE}/notifications/n-1/lecture`).flush({ id: 'n-1', lue: true });
    serveur.expectOne(`${API_BASE}/notifications/compteur`).flush({ nonLues: 2 });
    expect(service.nonLues()).toBe(2);
    service.toutMarquerLu().subscribe();
    serveur.expectOne(`${API_BASE}/notifications/lecture`).flush({ marquees: 2 });
    expect(service.nonLues()).toBe(0);
  });

  it("enregistre la préférence e-mail", () => {
    service.definirPreference(false).subscribe();
    const req = serveur.expectOne(`${API_BASE}/notifications/preferences`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ courrielActif: false });
    req.flush({ courrielActif: false, inAppActif: true });
  });
});
