import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { WorkspaceForm } from './workspace-form/workspace-form';
import { AccessGroupForm } from '../access-group/access-group-form/access-group-form';

/** Texte visible d'un formulaire : contenu rendu et textes d'aide des champs. */
function texteVisible<T>(composant: Type<T>, data: unknown): string {
  TestBed.configureTestingModule({
    imports: [composant],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
      { provide: MAT_DIALOG_DATA, useValue: data },
      { provide: MatDialogRef, useValue: { close: () => undefined } },
    ],
  });
  const f = TestBed.createComponent(composant);
  f.detectChanges();
  const el = f.nativeElement as HTMLElement;
  const aides = Array.from(el.querySelectorAll('[placeholder]')).map(e => e.getAttribute('placeholder'));
  return [el.textContent ?? '', ...aides].join(' ');
}

/** ANO-F-008 : langue française exclusive (§5) dans les formulaires d'administration. */
describe('Libellés en français (ANO-F-008)', () => {
  const ANGLAIS = /\b(work ?spaces?|group)\b/i;

  it('formulaire d\'espace de travail', () => {
    const t = texteVisible(WorkspaceForm, { workspace: null });
    expect(t).toContain('Entrez le nom de l\'espace de travail');
    expect(t).not.toMatch(ANGLAIS);
  });

  it('formulaire de groupe d\'accès', () => {
    const t = texteVisible(AccessGroupForm, { group: null });
    expect(t).toContain('Nom du groupe');
    expect(t).not.toMatch(ANGLAIS);
  });
});
