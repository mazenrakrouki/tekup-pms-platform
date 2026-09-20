// =============================================================================
// FILE: environment.prod.ts   (settings used by the really deployed application)
// =============================================================================
// WHAT THIS FILE IS
//   The PRODUCTION half of the pair started in environment.ts. Read that file
//   first: it explains that the Angular builder REPLACES it with this one at
//   build time, following the "fileReplacements" rule in angular.json.
//   Nothing in the application ever imports this file by name, and no test on
//   "production" decides which of the two is used.
//
// HOW TO CHECK WHICH ONE YOU BUILT
//   ng build --configuration production  -> this file
//   ng build / ng serve                  -> environment.ts
//
// WHY IT EXISTS (what breaks if you delete it)
//   The production build would keep the development values, and every user's
//   browser would try to reach http://localhost:8090 - that is, a backend on
//   THEIR OWN computer, which does not exist. The application would load, show
//   its layout, and then fail on every single request. The screen would look
//   broken with no clear reason, which is far harder to diagnose than a build
//   that refuses to start.
// =============================================================================

export const environment = {

  // Tells the app this is the production build. Angular then drops its
  // development-only checks, including the second change-detection pass that
  // warns when a value changes twice in one cycle. Those checks are useful while
  // developing and only cost speed once the app is deployed.
  production: true,

  // RELATIVE address, deliberately - just "/api", with no host and no port.
  // WHY: in production the browser only ever talks to ONE server, nginx. nginx
  // serves the compiled Angular files, and forwards anything starting with /api
  // to the Spring Boot backend (see the docker compose setup and
  // application-prod.yml). The API therefore lives at the same address as the
  // page itself, so the address of the page is exactly the prefix we want.
  //
  // WHAT A RELATIVE ADDRESS BUYS US
  //   1. The build does not need to know the final domain name. The same
  //      compiled files work on localhost:8081, on a test server and on the
  //      company domain, with no rebuild.
  //   2. Same origin means the browser does not treat the API as a third party.
  //      The refresh cookie, which is HttpOnly and SameSite=Strict, is sent
  //      normally. Written as a full address on another domain, SameSite=Strict
  //      would make the browser drop that cookie and the user would be logged
  //      out on the first token refresh, with no error message to explain it.
  apiUrl: '/api'
};
