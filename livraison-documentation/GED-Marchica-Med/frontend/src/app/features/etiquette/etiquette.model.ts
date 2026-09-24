/** Une étiquette (tag coloré) telle que renvoyée par l'API. */
export interface Etiquette {
  id: number;
  code: string;
  tag: string;
  couleur: string;
}

/** Corps envoyé pour créer / modifier une étiquette. */
export interface EtiquetteRequest {
  code: string;
  tag: string;
  couleur: string;
}

export interface PageResult<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}
