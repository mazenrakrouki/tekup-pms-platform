import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/services/theme.service';

// Root component Angular creates at start-up (<app-root> in index.html). It hosts
// <router-outlet>, where app.routes.ts puts the matching page (ShellComponent for
// everything except /login). app.html exists but is unused: 'template:' below wins.
@Component({
  selector: 'app-root',
  // Standalone component: declares RouterOutlet itself since this project has no NgModule.
  imports: [RouterOutlet],
  template: `<router-outlet></router-outlet>`
})
export class App {
  // Forces ThemeService to be created here (root-provided services are lazy otherwise),
  // so the saved light/dark theme is applied before the login page paints, not after.
  constructor() { inject(ThemeService); }
}
