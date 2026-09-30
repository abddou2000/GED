import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { GED_ICONS } from '../../../core/ged-icons';
import { CodeModule, ModulesService } from '../../../core/modules.service';
import { CycleDossier } from './cycle-dossier';

/**
 * Cycle de vie d'un dossier : l'archivage suit le module « cycle de vie », l'export
 * ZIP le module « export » (T-088), et l'archivage la permission ARCHIVER du nœud
 * (ANO-F-018).
 */
describe('CycleDossier', () => {
  let serveur: HttpTestingController;
  const inactifs = new Set<CodeModule>();

  beforeEach(() => {
    inactifs.clear();
    TestBed.configureTestingModule({
      imports: [CycleDossier],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: ModulesService, useValue: { charger: () => of(undefined), actif: (c: CodeModule) => !inactifs.has(c) } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  function ouvrir(peutArchiver = true): ComponentFixture<CycleDossier> {
    const f = TestBed.createComponent(CycleDossier);
    f.componentRef.setInput('dossierId', 'w1');
    f.componentRef.setInput('dossierNom', 'Achats');
    f.componentRef.setInput('peutArchiver', peutArchiver);
    f.detectChanges();
    for (const r of serveur.match(req => req.url.includes('/archivage/'))) {
      r.flush(r.request.url.endsWith('/jobs') ? [] : { statutConservation: 'ACTIF' });
    }
    f.detectChanges();
    return f;
  }

  const present = (f: ComponentFixture<unknown>, sel: string) =>
    (f.nativeElement as HTMLElement).querySelector(sel) !== null;

  it('modules actifs : export ZIP et archivage du dossier', () => {
    const f = ouvrir();
    expect(present(f, '.act-exporter')).toBe(true);
    expect(present(f, '.act-archiver')).toBe(true);
  });

  it('sans ARCHIVER sur le nœud : export seulement (ANO-F-018)', () => {
    const f = ouvrir(false);
    expect(present(f, '.act-exporter')).toBe(true);
    expect(present(f, '.act-archiver')).toBe(false);
  });

  it('module cycle de vie inactif : ni archivage ni appel à ses routes (T-088)', () => {
    inactifs.add('cycledevie');
    const f = ouvrir();
    serveur.expectNone(req => req.url.includes('/archivage/'));
    expect(present(f, '.act-archiver')).toBe(false);
    expect(present(f, '.act-exporter')).toBe(true);
  });

  it('module export inactif : pas d\'export ZIP (T-088)', () => {
    inactifs.add('export');
    const f = ouvrir();
    expect(present(f, '.act-exporter')).toBe(false);
    expect(present(f, '.act-archiver')).toBe(true);
  });

  it('les deux modules inactifs : aucun encart', () => {
    inactifs.add('cycledevie');
    inactifs.add('export');
    expect(present(ouvrir(), '.cycle')).toBe(false);
  });
});
