import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { SupervisionOcr } from './supervision-ocr';

/**
 * ANO-F-035 : la ligne d'état de la chaîne OCR était recouverte par la barre
 * d'onglets, en-tête de carte à marges négatives (`.toolbar`) placé après
 * elle dans la même carte. L'état se lit hors de la carte, dont la barre
 * d'onglets est le premier élément.
 */
describe('SupervisionOcr', () => {
  it('ANO-F-035 : ligne d\'état hors de la carte, barre d\'onglets en tête de carte', () => {
    TestBed.configureTestingModule({
      imports: [SupervisionOcr],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    const serveur = TestBed.inject(HttpTestingController);

    const f = TestBed.createComponent(SupervisionOcr);
    f.detectChanges();
    serveur.expectOne(`${API_BASE}/ocr/etat`)
      .flush({ actif: true, moteurDisponible: true, languesInstallees: ['ara', 'fra'], langueDefaut: 'ara+fra' });
    serveur.expectOne(`${API_BASE}/admin/ocr/compteurs`)
      .flush({ EN_ATTENTE_OCR: 0, EN_COURS_OCR: 0, OCR_ECHEC: 0, OCR_TERMINE: 3 });
    serveur.expectOne(r => r.url === `${API_BASE}/admin/ocr/jobs`).flush([]);
    serveur.expectOne(`${API_BASE}/admin/recherche/reindexation`).flush({ etat: 'INACTIVE', traites: 0, total: 0 });
    f.detectChanges();

    const el: HTMLElement = f.nativeElement;
    const etat = el.querySelector('.etat') as HTMLElement;
    expect(etat.textContent).toContain('Chaîne active');
    expect(etat.textContent).toContain('Moteur disponible');
    expect(etat.getAttribute('role')).toBe('status');
    expect(etat.closest('.card')).toBeNull();
    expect(el.querySelector('.card')?.firstElementChild?.classList.contains('toolbar')).toBe(true);
    f.destroy();
  });
});
