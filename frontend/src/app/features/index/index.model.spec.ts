import { FIELD_TYPES, fieldTypeLabel } from './index.model';
import { ouiNon } from './index-form/index-form';

/** ANO-F-007 : le type Booléen, géré par l'API, est proposé et affiché en clair. */
describe('Types de champ d\'index', () => {
  it('propose les cinq natures du méta-modèle, dont Booléen', () => {
    expect(FIELD_TYPES.map(t => t.value)).toEqual(['TEXTE', 'NOMBRE', 'DATE', 'LISTE', 'BOOLEEN']);
  });

  it('affiche « Booléen » et non la valeur brute BOOLEEN', () => {
    expect(fieldTypeLabel('BOOLEEN')).toBe('Booléen');
    expect(fieldTypeLabel('TEXTE')).toBe('Texte');
  });

  it('ramène la valeur par défaut d\'un booléen à « oui » ou « non »', () => {
    expect(ouiNon('true')).toBe('oui');
    expect(ouiNon(' Vrai ')).toBe('oui');
    expect(ouiNon('0')).toBe('non');
    expect(ouiNon('peut-être')).toBe('');
    expect(ouiNon(null)).toBe('');
  });
});
