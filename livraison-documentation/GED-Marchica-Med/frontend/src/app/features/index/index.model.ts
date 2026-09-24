export type IndexFieldType = 'TEXTE' | 'NOMBRE' | 'DATE' | 'LISTE';

/** Un index (champ de métadonnée) tel que renvoyé par l'API. */
export interface IndexField {
  id: number;
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
  id: number;
  name: string;
}

/** Types de champ (fidèles à CCISTTA : texte / nombre / date / liste). */
export const FIELD_TYPES: { value: IndexFieldType; label: string }[] = [
  { value: 'TEXTE', label: 'Texte' },
  { value: 'NOMBRE', label: 'Nombre' },
  { value: 'DATE', label: 'Date' },
  { value: 'LISTE', label: 'Liste' },
];

export function fieldTypeLabel(t: IndexFieldType): string {
  return FIELD_TYPES.find(x => x.value === t)?.label ?? t;
}
