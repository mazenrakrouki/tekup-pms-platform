import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/services/theme.service';

// =============================================================================
// app.ts - the root component of the whole Angular application.
//
// WHAT IT IS: the single component that Angular creates at start-up. Its tag
// <app-root> is the one written in index.html.
//
// WHERE IT SITS IN THE FLOW:
//   main.ts  ->  bootstrapApplication(App, appConfig)
//   appConfig (app.config.ts) installs the router, the HTTP client and i18n
//   App (this file) shows <router-outlet>
//   the router reads app.routes.ts and puts the matching page inside that outlet
//   (for every page except /login the matched thing is ShellComponent, which
//    draws the sidebar and the top bar and has its own inner outlet)
//
// WHY IT EXISTS: without it there is no host element for the router to fill,
// so the browser would show an empty page. It is also the one place that is
// alive for the whole life of the app, which is why the theme service is
// started here (see the constructor below).
// =============================================================================
// @Component turns this plain class into an Angular component: it links the
// class to a tag name and to a template.
// Why: Angular only renders classes that carry this decorator.
// Without it, bootstrapApplication(App, ...) throws "is not a component" at
// start-up and nothing is displayed at all.
@Component({
  // The HTML tag this component answers to. index.html contains <app-root>.
  // Why: this string is the contract between the static index.html page and
  // Angular. If it were renamed to 'pms-root', index.html would still hold an
  // unknown <app-root> tag and the screen would stay blank.
  selector: 'app-root',
  // Standalone component: it declares by itself the other components/directives
  // its template may use. Here only RouterOutlet.
  // Why: this project uses standalone components (no NgModule).
  // Without this import line, <router-outlet> would be an unknown tag and the
  // router would never be able to display any page.
  imports: [RouterOutlet],
  // Inline template on purpose: the real layout (sidebar, header, content) lives
  // in ShellComponent, so the root only needs the place where the router writes.
  // Why not a separate app.html file: a second file would only hold one tag.
  // Note: the file app.html still exists next to this one but is NOT used,
  // because "template:" below wins over any "templateUrl:".
  template: `<router-outlet></router-outlet>`
})
export class App {
  // The constructor does not store anything; it only asks Angular for the
  // ThemeService so that the service is created.
  // Why: ThemeService is provided at root level, and Angular creates such a
  // service lazily, only the first time somebody asks for it. The service is
  // what reads the saved light/dark choice and puts the right class/attribute
  // on the page. Asking for it here means it runs once, at start-up, for the
  // whole session.
  // Without this line the service would only wake up the first time a screen
  // injects it: the user would see the default (light) theme flash on the login
  // page even though he had chosen dark mode.
  // inject() is used instead of a constructor parameter simply because the
  // value is not kept in a field.
  constructor() { inject(ThemeService); }
}
