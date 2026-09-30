export type IndexFieldType = 'TEXTE' | 'NOMBRE' | 'DATE' | 'LISTE' | 'BOOLEEN';

/** Un index (champ de métadonnée) tel que renvoyé par l'API. */
export interface IndexField {
  id: string;
  code: string;
  nomIndex: string;
  fieldType: IndexFieldType;
  valeurs: string | null;
  valeurParDefaut: string | null;
  obligatoire: boolean;
  indexePourRecherche: boolean;
  indexDeGroupage: boolean;
}

/** Corps envoyé pour créer / modifier un index. */
export interface IndexRequest {
  code: string;
  nomIndex: string;
  fieldType: IndexFieldType;
  valeurs?: string | null;
  valeurParDefaut?: string | null;
  obligatoire: boolean;
  indexePourRecherche: boolean;
  indexDeGroupage: boolean;
}

export interface PageResult<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface SelectOption {
  id: string;
  name: string;
}

/** Natures d'un index (méta-modèle §12.7 : texte, nombre, date, liste, booléen). */
export const FIELD_TYPES: { value: IndexFieldType; label: string }[] = [
  { value: 'TEXTE', label: 'Texte' },
  { value: 'NOMBRE', label: 'Nombre' },
  { value: 'DATE', label: 'Date' },
  { value: 'LISTE', label: 'Liste' },
  { value: 'BOOLEEN', label: 'Booléen' },
];

export function fieldTypeLabel(t: IndexFieldType): string {
  return FIELD_TYPES.find(x => x.value === t)?.label ?? t;
}
