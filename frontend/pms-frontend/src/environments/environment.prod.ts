// Production settings, swapped in for environment.ts at build time via angular.json's
// fileReplacements (see that file's header). Nothing imports this file by name.

export const environment = {
  // Disables Angular's dev-only checks (extra warnings, double change-detection pass).
  production: true,

  // Relative on purpose: nginx serves the built app and proxies /api to the backend, so the
  // API is always same-origin as the page. That also keeps the HttpOnly/SameSite=Strict
  // refresh cookie working — a cross-origin address would make the browser drop it.
  apiUrl: '/api'
};
