import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { DateAdapter, MAT_NATIVE_DATE_FORMATS } from '@angular/material/core';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatInputModule } from '@angular/material/input';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { appConfig } from '../app.config';
import { DateAdapterFr, provideDateAdapterFr } from './date-adapter-fr';

/** Champ date réel (sélecteur Material) pour vérifier la lecture d'une saisie au clavier. */
@Component({
  imports: [ReactiveFormsModule, MatInputModule, MatDatepickerModule],
  template: `<input [matDatepicker]="dp" [formControl]="date" /><mat-datepicker #dp></mat-datepicker>`,
})
class ChampDate {
  date = new FormControl<Date | null>(null);
}

/** ANO-F-022 : une date tapée au clavier se lit jj/mm/aaaa, comme elle s'affiche. */
describe('DateAdapterFr', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideDateAdapterFr(), provideNoopAnimations()] });
  });

  it('lit jour / mois / année, et refuse une date impossible', () => {
    const a = TestBed.inject(DateAdapter) as DateAdapter<Date>;
    expect(a).toBeInstanceOf(DateAdapterFr);
    const d = a.parse('15/03/2026', null)!;
    expect([d.getFullYear(), d.getMonth(), d.getDate()]).toEqual([2026, 2, 15]);
    const avril = a.parse('03/04/2026', null)!;
    expect([avril.getMonth(), avril.getDate()]).toEqual([3, 3]);        // 3 avril, pas 4 mars
    expect(a.parse('3.4.2026', null)!.getMonth()).toBe(3);
    expect(a.parse('2026-04-03', null)!.getDate()).toBe(3);
    expect(a.isValid(a.parse('31/02/2026', null)!)).toBe(false);      // jamais reportée au 3 mars
    expect(a.isValid(a.parse('n\'importe quoi', null)!)).toBe(false);
    expect(a.parse('', null)).toBeNull();
    expect(a.format(new Date(2026, 3, 3), MAT_NATIVE_DATE_FORMATS.display.dateInput)).toBe('03/04/2026');
    expect(a.getFirstDayOfWeek()).toBe(1);
  });

  it('dans un champ Material, « 03/04/2026 » tapé au clavier donne le 3 avril', () => {
    const f = TestBed.createComponent(ChampDate);
    f.detectChanges();
    const input = f.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = '03/04/2026';
    input.dispatchEvent(new Event('input'));
    f.detectChanges();
    const v = f.componentInstance.date.value!;
    expect([v.getFullYear(), v.getMonth(), v.getDate()]).toEqual([2026, 3, 3]);

    input.value = '15/03/2026';
    input.dispatchEvent(new Event('input'));
    f.detectChanges();
    expect(f.componentInstance.date.valid).toBe(true);
    expect(f.componentInstance.date.value!.getDate()).toBe(15);
  });

  it("l'application emploie cet adaptateur pour tous ses champs date", () => {
    const fournis = (appConfig.providers as unknown[]).flatMap(p =>
      (p as { ɵproviders?: unknown[] })?.ɵproviders ?? [p]);
    expect(fournis.some(p => (p as { useClass?: unknown })?.useClass === DateAdapterFr)).toBe(true);
  });
});
