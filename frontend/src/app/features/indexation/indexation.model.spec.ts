import { afficherValeurIndex, lireBooleen } from './indexation.model';

/** ANO-F-020 : lecture et affichage d'une valeur d'index booléenne. */
describe('Index booléen (ANO-F-020)', () => {
  it('lit les écritures admises par le serveur', () => {
    for (const v of ['true', 'VRAI', 'oui', '1', 'o', 'yes', true]) expect(lireBooleen(v)).toBe(true);
    for (const v of ['false', 'faux', 'Non', '0', 'n', 'no', false]) expect(lireBooleen(v)).toBe(false);
    for (const v of ['', '  ', 'peut-être', null, undefined]) expect(lireBooleen(v)).toBeNull();
  });

  it('affiche Oui / Non pour un booléen, la valeur telle quelle sinon', () => {
    expect(afficherValeurIndex('BOOLEEN', 'true')).toBe('Oui');
    expect(afficherValeurIndex('BOOLEEN', false)).toBe('Non');
    expect(afficherValeurIndex('BOOLEEN', 'illisible')).toBe('illisible');
    expect(afficherValeurIndex('TEXTE', 'true')).toBe('true');
    expect(afficherValeurIndex(undefined, 'x')).toBe('x');
    expect(afficherValeurIndex('BOOLEEN', null)).toBe('');
  });
});
