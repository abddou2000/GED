import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { API_BASE } from '../../../core/api';
import { DossierSimpleForm } from './dossier-simple-form';

/** ANO-F-016 : le formulaire simple crée un dossier par POST /noeuds/{parent}/dossiers. */
describe('DossierSimpleForm', () => {
  let serveur: HttpTestingController;
  const fermetures: unknown[] = [];

  beforeEach(() => {
    fermetures.length = 0;
    TestBed.configureTestingModule({
      imports: [DossierSimpleForm],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: MatDialogRef, useValue: { close: (v: unknown) => fermetures.push(v) } },
        { provide: MAT_DIALOG_DATA, useValue: { parentId: 'd1', parentNom: 'Lot 1' } },
      ],
    });
    serveur = TestBed.inject(HttpTestingController);
  });

  it('ne demande que le nom (et une description facultative), puis crée le dossier', () => {
    const f = TestBed.createComponent(DossierSimpleForm);
    f.detectChanges();
    const el: HTMLElement = f.nativeElement;
    // Ni code, ni propriétaire, ni statut : ce n'est pas le formulaire d'administration.
    expect(el.querySelectorAll('input, textarea, mat-select').length).toBe(2);

    const creer = el.querySelector('button.creer') as HTMLButtonElement;
    creer.click();
    serveur.expectNone(`${API_BASE}/noeuds/d1/dossiers`);   // nom obligatoire

    const nom = el.querySelector('input[formcontrolname="nom"]') as HTMLInputElement;
    nom.value = '  Offres  ';
    nom.dispatchEvent(new Event('input'));
    creer.click();
    const post = serveur.expectOne(r => r.method === 'POST' && r.url === `${API_BASE}/noeuds/d1/dossiers`);
    expect(post.request.body).toEqual({ nom: 'Offres', description: null });
    post.flush({ id: 'd2', name: 'Offres' });
    expect(fermetures).toEqual([{ id: 'd2', name: 'Offres' }]);
  });

  it("affiche le refus du serveur sans fermer la fenêtre", () => {
    const f = TestBed.createComponent(DossierSimpleForm);
    f.detectChanges();
    f.componentInstance.form.patchValue({ nom: 'Offres' });
    f.componentInstance.creer();
    serveur.expectOne(`${API_BASE}/noeuds/d1/dossiers`)
      .flush({ detail: 'Permission DEPOSER requise' }, { status: 403, statusText: 'Forbidden' });
    f.detectChanges();
    expect(f.nativeElement.querySelector('.erreur')?.textContent).toContain('DEPOSER');
    expect(fermetures).toEqual([]);
  });
});
