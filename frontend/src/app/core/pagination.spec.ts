import { HttpHeaders, HttpResponse } from '@angular/common/http';
import { TAILLES_PAGE, TAILLE_PAGE_DEFAUT, TAILLE_PAGE_MAX, avecChampsIgnores, champsIgnores } from './pagination';

describe('pagination commune (T-050, DAT §5.3.2)', () => {
  it('50 par défaut, sélecteur jamais au-delà du plafond serveur (200)', () => {
    expect(TAILLE_PAGE_DEFAUT).toBe(50);
    expect(TAILLE_PAGE_MAX).toBe(200);
    expect(TAILLES_PAGE).toContain(TAILLE_PAGE_DEFAUT);
    expect(TAILLES_PAGE.every(t => t <= TAILLE_PAGE_MAX)).toBe(true);
  });

  it("lit l'en-tête GED-Champs-Ignores : noms séparés par des virgules, encodés en pourcentage", () => {
    expect(champsIgnores(null)).toEqual([]);
    expect(champsIgnores('')).toEqual([]);
    expect(champsIgnores('canal, criteres[0].valeurr')).toEqual(['canal', 'criteres[0].valeurr']);
    expect(champsIgnores('d%C3%A9pos%C3%A9,%2C,...')).toEqual(['déposé', ',', '...']);
    // Un encodage invalide est rendu tel quel plutôt que de faire échouer l'écran.
    expect(champsIgnores('%E0%A4%A')).toEqual(['%E0%A4%A']);
  });

  it('sépare le corps et les champs ignorés d’une réponse complète', () => {
    const r = new HttpResponse({ body: { total: 1 }, headers: new HttpHeaders({ 'GED-Champs-Ignores': 'tri' }) });
    expect(avecChampsIgnores(r)).toEqual({ corps: { total: 1 }, champsIgnores: ['tri'] });
    expect(avecChampsIgnores(new HttpResponse({ body: 1 })).champsIgnores).toEqual([]);
  });
});
