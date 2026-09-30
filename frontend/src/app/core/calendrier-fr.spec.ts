import { Component, Provider } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatDatepickerIntl, MatDatepickerModule } from '@angular/material/datepicker';
import { MatDialogRef } from '@angular/material/dialog';
import { MatInputModule } from '@angular/material/input';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Subject } from 'rxjs';
import { appConfig } from '../app.config';
import { DocumentUpload } from '../features/document/document-upload/document-upload';
import { CHAMP_DATE, CalendrierFr } from './calendrier-fr';

const GABARIT = `
  <mat-form-field>
    <input matInput [matDatepicker]="dp" />
    <mat-datepicker-toggle matIconSuffix [for]="dp"></mat-datepicker-toggle>
    <mat-datepicker #dp></mat-datepicker>
  </mat-form-field>`;

@Component({ imports: [CHAMP_DATE, MatInputModule], template: GABARIT })
class ChampDate {}

/** Ce que faisaient les écrans : le module réintroduit la version anglaise. */
@Component({ imports: [MatDatepickerModule, MatInputModule], template: GABARIT })
class ChampDateAvecModule {}

/** ANO-F-024 : les libellés du sélecteur de date sont en français. */
describe('CalendrierFr (ANO-F-024)', () => {
  /** Le fournisseur tel que la configuration de l'application le déclare. */
  const fournisseur = (appConfig.providers as Provider[]).find(
    p => typeof p === 'object' && p !== null && 'provide' in p && p.provide === MatDatepickerIntl);

  function configurer(): void {
    TestBed.configureTestingModule({
      providers: [
        provideNativeDateAdapter(), provideNoopAnimations(), provideHttpClient(), provideHttpClientTesting(),
        fournisseur!,
        {
          provide: MatDialogRef, useValue: {
            disableClose: false, backdropClick: () => new Subject(), keydownEvents: () => new Subject(),
            close: () => undefined,
          },
        },
      ],
    });
  }

  const libelleBouton = (el: HTMLElement) =>
    el.querySelector('mat-datepicker-toggle button')?.getAttribute('aria-label');

  it('est déclaré dans la configuration globale de l\'application', () => {
    expect(fournisseur).toEqual({ provide: MatDatepickerIntl, useClass: CalendrierFr });
  });

  it('le bouton du calendrier se lit « Ouvrir le calendrier »', () => {
    configurer();
    const f = TestBed.createComponent(ChampDate);
    f.detectChanges();
    expect(libelleBouton(f.nativeElement)).toBe('Ouvrir le calendrier');
  });

  it('MatDatepickerModule masque la version française : les écrans ne l\'importent plus', () => {
    configurer();
    const f = TestBed.createComponent(ChampDateAvecModule);
    f.detectChanges();
    expect(libelleBouton(f.nativeElement)).toBe('Open calendar');

    const depot = TestBed.createComponent(DocumentUpload);
    depot.detectChanges();
    TestBed.inject(HttpTestingController).match(() => true);
    expect(libelleBouton(depot.nativeElement)).toBe('Ouvrir le calendrier');
  });

  it('traduit la navigation et les plages d\'années', () => {
    const intl = new CalendrierFr();
    expect(intl.nextMonthLabel).toBe('Mois suivant');
    expect(intl.switchToMultiYearViewLabel).toBe("Choisir le mois et l'année");
    expect(intl.formatYearRangeLabel('2020', '2043')).toBe('de 2020 à 2043');
  });
});
