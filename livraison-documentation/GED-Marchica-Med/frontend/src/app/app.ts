import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Racine applicative : ne porte que le routeur. La coque (barre latérale + barre
 *  supérieure) est fournie par le composant Shell ; l'écran de connexion vit en dehors. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class App {}
