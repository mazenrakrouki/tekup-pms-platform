// Dev settings, swapped for environment.prod.ts at build time via angular.json's
// fileReplacements (see that file's header). Only environment.apiUrl is read anywhere.

export const environment = {
  production: false,

  // Full address: dev server (4200) and backend (8090) are different origins, so a relative
  // '/api' would resolve against 4200 and 404. CORS + withCredentials handle the cross-origin
  // cookie.
  apiUrl: 'http://localhost:8090/api'
};
