// =============================================================================
// FILE: environment.ts   (settings used when you run the app on your own machine)
// =============================================================================
// WHAT THIS FILE IS
//   The DEVELOPMENT half of a pair. Its twin is environment.prod.ts, right next
//   to it, which holds the values used by the real deployed application.
//
// HOW THE TWO ARE SWAPPED - this is the part that surprises people
//   The application code NEVER chooses between them. Every file writes the same
//   import:
//       import { environment } from '../../environments/environment';
//   When you build for production, the Angular builder REPLACES this file with
//   environment.prod.ts. The rule is written in angular.json:
//       "fileReplacements": [
//         { "replace": "src/environments/environment.ts",
//           "with":    "src/environments/environment.prod.ts" } ]
//   So the swap happens at BUILD time, not while the app runs. There is no "if
//   production" test anywhere in the code, and the values of this file are not
//   even shipped in the production bundle.
//
// WHERE IT IS USED
//   Only one value is read anywhere in the app: environment.apiUrl. Every
//   service in core/services builds its URLs from it, for example
//   `${environment.apiUrl}/projects`.
//
// WHY IT EXISTS (what breaks if you delete it)
//   The address of the backend is not the same on a developer machine and on the
//   server. Without this file that address would be written by hand in every
//   service, and shipping the app would mean editing a dozen files and hoping
//   none was forgotten.
// =============================================================================

export const environment = {

  // Tells the app it is NOT the production build.
  // Angular uses the same idea internally to keep its development checks on:
  // extra warnings in the console, and the double change-detection run that
  // reports a value changing twice in one cycle. Those checks cost speed, which
  // is why the production twin sets this to true.
  production: false,

  // FULL address, with http:// and the port, because in development the app and
  // the backend are two separate servers:
  //     the Angular dev server serves the pages on  http://localhost:4200
  //     Spring Boot answers the API on             http://localhost:8090
  // WHY IT CANNOT SIMPLY BE '/api' HERE: a relative address is resolved against
  // the page's own address, so the browser would call
  // http://localhost:4200/api/projects, where nothing answers, and every screen
  // would show an empty list or a 404.
  // NOTE: because the two addresses differ, the browser treats the API as
  // another origin. That is why the backend enables CORS for localhost:4200 and
  // why the services send withCredentials: true, so the refresh cookie travels.
  apiUrl: 'http://localhost:8090/api'
};
