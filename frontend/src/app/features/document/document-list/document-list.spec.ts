import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectorRef } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../../../core/api';
import { AuthService } from '../../../core/auth.service';
import { GED_ICONS } from '../../../core/ged-icons';
import { DocumentItem } from '../document.model';
import { DocumentList } from './document-list';

/** Liste des documents : issue « à indexer » (ANO-F-005), date du document (ANO-F-006). */
describe('DocumentList', () => {
  let serveur: HttpTestingController;

  const DOC: DocumentItem = {
    id: 'd1', name: 'Facture', workspace: null, typeDocument: null, fileName: 'facture.pdf', extension: 'pdf',
    sizeKo: 1, sizeLabel: '1 Ko', expirationDate: null, active: true, verrouille: false, chemin: '',
    createdBy: null, etiquettes: [], versions: [], createdAt: '2026-09-30T10:00:00Z',
    statutIndexation: 'A_INDEXER', dateDocument: '2026-03-12',
  };

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [DocumentList],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  function ouvrir(): ComponentFixture<DocumentList> {
    const f = TestBed.createComponent(DocumentList);
    f.detectChanges();
    serveur.expectOne(r => r.url === `${API_BASE}/documents`)
      .flush({ content: [DOC], total: 1, page: 0, size: 10, totalPages: 1 });
    f.detectChanges();
    return f;
  }

  it('ANO-F-005 : signale un document « à indexer » et mène à sa fiche', () => {
    const badge = ouvrir().nativeElement.querySelector('.badge-a-indexer') as HTMLAnchorElement;
    expect(badge?.textContent).toContain('À indexer');
    expect(badge.getAttribute('href')).toBe('/televerser/d1');
  });

  it("ANO-F-017 : signale l'état OCR (en attente, en échec) dans la liste", () => {
    const f = TestBed.createComponent(DocumentList);
    f.detectChanges();
    serveur.expectOne(r => r.url === `${API_BASE}/documents`).flush({ content: [
      { ...DOC, id: 'a', statutIndexation: 'INDEXE', statutOcr: 'EN_ATTENTE_OCR' },
      { ...DOC, id: 'b', statutIndexation: 'INDEXE', statutOcr: 'OCR_ECHEC' },
      { ...DOC, id: 'c', statutIndexation: 'INDEXE', statutOcr: 'OCR_TERMINE' },
    ], total: 3, page: 0, size: 10, totalPages: 1 });
    f.detectChanges();
    const badges = Array.from(f.nativeElement.querySelectorAll('.badge-ocr') as NodeListOf<HTMLElement>);
    expect(badges.map(b => b.textContent?.trim())).toEqual(['OCR en attente', 'OCR en échec']);
    expect(badges[1].classList).toContain('echec');
  });

  it("ANO-F-023 : la corbeille s'appelle « Corbeille », pas « Archive » (l'archivage est autre chose, D10)", () => {
    const el: HTMLElement = ouvrir().nativeElement;
    const bouton = el.querySelector('button.corbeille') as HTMLButtonElement;
    expect(bouton.textContent?.trim()).toBe('Corbeille');
    expect(Array.from(el.querySelectorAll('button')).some(b => b.textContent?.trim() === 'Archive')).toBe(false);
  });

  it('ANO-F-006 : affiche la date du document et trie la liste sur elle (sortBy=dateDocument)', () => {
    const f = ouvrir();
    const el: HTMLElement = f.nativeElement;
    const entete = Array.from(el.querySelectorAll('th')).find(th => th.textContent?.includes('Date du document'));
    expect(entete).toBeTruthy();
    expect(el.querySelector('td.mat-column-dateDocument')?.textContent).toContain('12/03/2026');

    (entete!.querySelector('.mat-sort-header-container') as HTMLElement ?? entete!).click();
    f.detectChanges();
    const req = serveur.expectOne(r => r.url === `${API_BASE}/documents`);
    expect(req.request.params.get('sortBy')).toBe('dateDocument');
    req.flush({ content: [DOC], total: 1, page: 0, size: 10, totalPages: 1 });
  });

  function connecte(roles: string[], permissions: string[]): void {
    TestBed.inject(AuthService).utilisateur.set({
      id: 'u1', identifiant: 'qa2dg', employeId: 'e1', fullName: 'Direction', email: null, direction: null,
      roles, permissions,
    });
  }

  /** Coche le document : la sélection n'est pas un signal, la vue est marquée à revoir. */
  function selectionner(f: ComponentFixture<DocumentList>): void {
    f.componentInstance.selection.select(DOC);
    f.componentRef.injector.get(ChangeDetectorRef).markForCheck();
    f.detectChanges();
  }

  function libellesBoutons(el: HTMLElement): string[] {
    return Array.from(el.querySelectorAll('button')).map(b => b.getAttribute('aria-label') ?? b.textContent?.trim() ?? '');
  }

  it('ANO-F-002 : la Direction Générale (sans Supprimer) ne voit « Supprimer » ni sur la ligne ni sur la sélection', () => {
    connecte(['DIRECTION_GENERALE'], ['CONSULTER', 'CONSULTER_AUDIT', 'DEPOSER', 'DIFFUSER', 'MODIFIER', 'VALIDER',
      'VOIR_CONFIDENTIEL', 'VOIR_PRIVE']);
    const f = ouvrir();
    const el: HTMLElement = f.nativeElement;
    expect(libellesBoutons(el)).not.toContain('Supprimer');
    expect(libellesBoutons(el)).toContain('Ouvrir la fiche');
    selectionner(f);
    expect(el.querySelector('button.bulk')).toBeNull();
  });

  it('ANO-F-002 : qui détient Supprimer garde « Supprimer », ligne et sélection', () => {
    connecte(['AGENT_ARCHIVE'], ['CONSULTER', 'DEPOSER', 'SUPPRIMER', 'PURGER']);
    const f = ouvrir();
    const el: HTMLElement = f.nativeElement;
    expect(libellesBoutons(el)).toContain('Supprimer');
    selectionner(f);
    expect((el.querySelector('button.bulk') as HTMLButtonElement)?.textContent).toContain('Supprimer (1)');
  });
});
