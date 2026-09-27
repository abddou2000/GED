import { HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { API_BASE } from './api';

/**
 * Pose un en-tête `Idempotency-Key` (UUID) sur chaque création envoyée à l'API
 * (DAT §5.3.2 : obligatoire sur les créations).
 *
 * <p>Une clé neuve par requête émise : si la même requête est rejouée par le
 * réseau (proxy, reprise), le serveur renvoie la réponse initiale au lieu de
 * créer un doublon. Une nouvelle action de l'utilisateur est une nouvelle
 * requête, donc une nouvelle clé. Le serveur n'exige la clé que sur les
 * créations déclarées ; ailleurs, elle est ignorée.
 */
export function idempotenceInterceptor(requete: HttpRequest<unknown>, suite: HttpHandlerFn) {
  const versNotreApi = requete.url.startsWith(API_BASE) || requete.url.includes(`${API_BASE}/`);
  if (requete.method !== 'POST' || !versNotreApi || requete.headers.has('Idempotency-Key')
      || requete.url.includes(`${API_BASE}/auth/`)) {
    return suite(requete);
  }
  return suite(requete.clone({ setHeaders: { 'Idempotency-Key': nouvelleCle() } }));
}

function nouvelleCle(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // Contexte non sécurisé (HTTP sans TLS en développement) : UUID v4 depuis getRandomValues.
  const o = crypto.getRandomValues(new Uint8Array(16));
  o[6] = (o[6] & 0x0f) | 0x40;
  o[8] = (o[8] & 0x3f) | 0x80;
  const h = Array.from(o, b => b.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
