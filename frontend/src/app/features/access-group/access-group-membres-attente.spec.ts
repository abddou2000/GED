import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatIconTestingModule } from '@angular/material/icon/testing';
import { MatSelect } from '@angular/material/select';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_BASE } from '../../core/api';
import { Employe } from '../../core/employe.service';
import { AccessGroupDetail } from './access-group-detail/access-group-detail';
import { AccessGroupForm } from './access-group-form/access-group-form';
import { AccessGroup } from './access-group.model';

/**
 * ANO-F-029 (T-025) : un groupe a des membres réels (identité GED) et des
 * membres « en attente » (fiche employé sans identité, `pendingUserIds`),
 * convertis à la première connexion. L'écran doit les distinguer, les
 * conserver à l'enregistrement, permettre d'en ajouter et d'en retirer.
 */

const KARIM: Employe = { id: 'e-karim', firstName: 'Karim', lastName: 'El Fassi', fullName: 'Karim El Fassi', utilisateurId: 'u-karim' };
const SARA: Employe = { id: 'e-sara', firstName: 'Sara', lastName: 'Alaoui', fullName: 'Sara Alaoui', utilisateurId: 'u-sara' };
const ATTENTE: Employe = { id: 'e-attente', firstName: 'Attente', lastName: 'Ecran R6', fullName: 'Attente Ecran R6', utilisateurId: null };
const JAMAIS: Employe = { id: 'e-jamais', firstName: 'Nadia', lastName: 'Bennis', fullName: 'Nadia Bennis', utilisateurId: null };

const GROUPE: AccessGroup = {
  id: 'g1', code: 'QA2-R6', name: 'QA2 Écran attente r6',
  workspaces: [],
  users: [{ id: KARIM.id, label: KARIM.fullName }, { id: ATTENTE.id, label: ATTENTE.fullName }],
  workspacesCount: 0, usersCount: 2,
  pendingUserIds: [ATTENTE.id],
};

describe('Groupe : membres en attente de première connexion (ANO-F-029)', () => {
  let serveur: HttpTestingController;
  const fermetures: unknown[] = [];

  function formulaire(group: AccessGroup | null) {
    fermetures.length = 0;
    TestBed.configureTestingModule({
      imports: [AccessGroupForm],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: MatDialogRef, useValue: { close: (v: unknown) => fermetures.push(v) } },
        { provide: MAT_DIALOG_DATA, useValue: { group } },
      ],
    });
    serveur = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(AccessGroupForm);
    f.detectChanges();
    serveur.expectOne(`${API_BASE}/workspaces/for-select`).flush([]);
    return f;
  }

  /** Le sélecteur doit lire TOUTES les fiches employé, pas seulement celles qui ont une identité. */
  function servirEmployes(liste: Employe[]): void {
    serveur.expectOne(r => r.method === 'GET' && r.url === `${API_BASE}/employes` && !r.params.has('has_user'))
      .flush(liste);
  }

  function selecteurMembres(f: ReturnType<typeof formulaire>): MatSelect {
    return f.debugElement.queryAll(By.directive(MatSelect))
      .map(d => d.componentInstance as MatSelect)
      .find(s => s.ngControl?.name === 'userIds')!;
  }

  /**
   * Coche ou décoche une option comme le ferait l'utilisateur (clic dans la
   * liste). Le sélecteur synchronise sa sélection sur les options dans une
   * micro-tâche : on la laisse passer, comme le ferait l'utilisateur.
   */
  async function cliquerOption(f: ReturnType<typeof formulaire>, id: string): Promise<void> {
    await f.whenStable();
    const s = selecteurMembres(f);
    s.open();
    f.detectChanges();
    const opt = s.options.find(o => o.value === id)!;
    expect(opt).toBeTruthy();
    opt._selectViaInteraction();
    f.detectChanges();
  }

  function enregistrer(f: ReturnType<typeof formulaire>): { userIds: string[] } {
    (f.nativeElement.querySelector('mat-dialog-actions button[color="primary"]') as HTMLButtonElement).click();
    const req = serveur.expectOne(r => r.method === (f.componentInstance.isEdit ? 'PUT' : 'POST'));
    const corps = req.request.body;
    req.flush({ ...GROUPE, id: 'g1' });
    return corps;
  }

  // Chaque cas configure son propre module (formulaire ou fiche).
  beforeEach(() => TestBed.resetTestingModule());
  afterEach(() => serveur.verify());

  it('ajouter un membre conserve le membre en attente (il n\'est plus effacé du groupe)', async () => {
    const f = formulaire(GROUPE);
    servirEmployes([KARIM, SARA, ATTENTE]);
    f.detectChanges();

    await cliquerOption(f, SARA.id);

    const corps = enregistrer(f);
    expect([...corps.userIds].sort()).toEqual([ATTENTE.id, KARIM.id, SARA.id].sort());
  });

  it('retirer un membre réel conserve le membre en attente', async () => {
    const f = formulaire(GROUPE);
    servirEmployes([KARIM, SARA, ATTENTE]);
    f.detectChanges();

    await cliquerOption(f, KARIM.id);

    expect(enregistrer(f).userIds).toEqual([ATTENTE.id]);
  });

  it("conserve un membre en attente même absent de la liste des fiches (liste indisponible)", async () => {
    const f = formulaire(GROUPE);
    servirEmployes([KARIM, SARA]);
    f.detectChanges();

    await cliquerOption(f, SARA.id);

    expect(enregistrer(f).userIds).toContain(ATTENTE.id);
  });

  it('propose les personnes jamais connectées, avec le libellé « en attente de première connexion »', () => {
    const f = formulaire(null);
    servirEmployes([KARIM, JAMAIS]);
    f.detectChanges();

    const s = selecteurMembres(f);
    s.open();
    f.detectChanges();
    const libelles = s.options.map(o => o.viewValue);
    expect(libelles.find(l => l.includes('Nadia Bennis'))).toContain('en attente de première connexion');
    expect(libelles.find(l => l.includes('Karim El Fassi'))).not.toContain('en attente');
  });

  it('permet de créer un groupe avec une personne jamais connectée', async () => {
    const f = formulaire(null);
    servirEmployes([KARIM, JAMAIS]);
    f.componentInstance.form.patchValue({ code: 'G-NEW', name: 'Nouveau' });
    f.detectChanges();

    await cliquerOption(f, JAMAIS.id);

    expect(enregistrer(f).userIds).toEqual([JAMAIS.id]);
  });

  it('liste les membres en attente sélectionnés et permet de les retirer explicitement', () => {
    const f = formulaire(GROUPE);
    servirEmployes([KARIM, SARA, ATTENTE]);
    f.detectChanges();

    const el: HTMLElement = f.nativeElement;
    const bloc = el.querySelector('.membres-attente') as HTMLElement;
    expect(bloc).toBeTruthy();
    expect(bloc.textContent).toContain('Attente Ecran R6');
    expect(bloc.textContent).toContain('première connexion');
    expect(bloc.textContent).not.toContain('Karim');

    const retirer = bloc.querySelector('button[aria-label="Retirer Attente Ecran R6 du groupe"]') as HTMLButtonElement;
    expect(retirer).toBeTruthy();
    retirer.click();
    f.detectChanges();
    expect(el.querySelector('.membres-attente')).toBeNull();

    expect(enregistrer(f).userIds).toEqual([KARIM.id]);
  });

  it("la fiche du groupe distingue le membre en attente et le décompte", () => {
    TestBed.configureTestingModule({
      imports: [AccessGroupDetail, MatIconTestingModule],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'g1' })) } },
      ],
    });
    serveur = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(AccessGroupDetail);
    f.detectChanges();
    serveur.expectOne(`${API_BASE}/access-groups/g1`).flush(GROUPE);
    f.detectChanges();

    const lignes = Array.from(f.nativeElement.querySelectorAll('.liste li')) as HTMLElement[];
    const attente = lignes.find(l => l.textContent!.includes('Attente Ecran R6'))!;
    const reel = lignes.find(l => l.textContent!.includes('Karim El Fassi'))!;
    expect(attente.textContent).toContain('en attente de première connexion');
    expect(reel.textContent).not.toContain('en attente');
    expect(f.nativeElement.querySelector('.fiche-grille')!.textContent)
      .toContain('dont 1 en attente de première connexion');
  });
});
