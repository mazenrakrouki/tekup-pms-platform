# Frontend Internationalization (i18n) — Architecture & Migration Guide

**Scope:** Angular 21 frontend (`frontend/pms-frontend`) · **Decision:** [ADR-025](../DECISIONS.md#adr-025)
**Languages:** 🇫🇷 French (default) · 🇬🇧 English · extensible to any number of languages.
**Maintained by:** Technical Lead / Solution Architect

---

## 1. Why Transloco (library decision)

| Option | Runtime switch (no reload) | Per-module lazy scopes | Angular 21 | Verdict |
|--------|:--:|:--:|:--:|---------|
| `@angular/localize` (built-in) | ❌ (compile-time, one build per locale) | ❌ | ✅ | **Rejected** — cannot switch at runtime |
| `ngx-translate` | ✅ | partial | ✅ | Viable, weaker scoping |
| **`@jsverse/transloco`** | ✅ `reRenderOnLangChange` | ✅ first-class | ✅ (`peer >=16`) | **Chosen** |

The hard requirement — *switch instantly, no reload, preserve the page* — eliminates the built-in
compiler i18n. Transloco adds reactive re-render and native module scopes, keeping catalogs small and
lazily loaded.

## 2. File layout

```
frontend/pms-frontend/
├── src/app/core/i18n/
│   ├── transloco-loader.ts        # HttpClient loader → /i18n/<lang>.json and /i18n/<scope>/<lang>.json
│   ├── language.service.ts        # active-lang signal, switch, persistence, browser detection
│   └── transloco.providers.ts     # provideI18n(): Transloco config + APP_INITIALIZER
├── src/app/layout/language-switcher/
│   └── language-switcher.component.ts   # reusable <app-language-switcher>
└── public/i18n/                    # served at /i18n/ (Angular 21 uses public/, not src/assets)
    ├── fr.json   en.json           # ROOT scope — always loaded (shared namespaces)
    ├── auth/     fr.json en.json   # feature scopes — lazy-loaded per component
    ├── dashboard/ …
    ├── project/  user/  billing/  workload/  mission/  kpi/  tcc/  governance/  di/
```

> **Assets note:** Angular 21 serves static files from `public/` (see `angular.json` → `assets.input: "public"`).
> Translation files therefore live in `public/i18n/`, **not** `src/assets/i18n/`. Documented here so the
> location is intentional, not accidental.

### Root namespaces (`public/i18n/{lang}.json`) — always loaded

`app` · `lang` · `theme` · `common` · `nav` · `roles` · `status` · `validation` · `notification` ·
`pagination` · `palette`

These cover the app chrome (sidebar, switcher) and cross-cutting tokens (statuses, role badges,
generic actions) reused by **every** page.

## 3. How to use it in a component

**Shared tokens** (no scope needed — root is always loaded):

```html
{{ 'common.save' | transloco }}
{{ 'roles.' + user.roleName | transloco }}      <!-- ADMIN → Administrator/Administrateur -->
{{ 'status.' + project.status | transloco }}    <!-- ACTIVE → Active/Actif -->
```

**Feature strings** — declare the scope, then use fully-qualified keys:

```ts
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';

@Component({
  imports: [/* … */, TranslocoModule],
  providers: [provideTranslocoScope('project')],   // lazy-loads public/i18n/project/<lang>.json
  template: `<h1>{{ 'project.list.title' | transloco }}</h1>`
})
```

**Errors / dynamic messages** — store the **key**, not the literal, so it re-translates on switch:

```ts
this.error.set('auth.login.errors.invalid');   // template: {{ error() | transloco }}
```

**Reactive TS logic** (labels built in code, e.g. a command-palette search): read the active-lang
signal so the computed re-runs on switch, and translate via `TranslocoService.translate(key)`.

## 4. Language switcher, persistence, detection

- `<app-language-switcher>` — globe + flag dropdown. Placed in the **sidebar footer** (authenticated)
  and on the **login screen** (pre-auth, so language can be chosen before signing in).
- `LanguageService` — single source of truth. `current` is a signal; `setLang()` applies + persists.
- **Resolution order at bootstrap:** `localStorage['pms_lang']` → `navigator.language` → `fr`.
- Switching sets `document.documentElement.lang` and re-renders the whole UI **without reload**;
  the current route/page is preserved.

## 5. Backend contract (language-independent)

The backend **never** returns translated UI labels. It returns **stable codes**; Angular maps them:

| Domain | Code (API/DB) | `fr` | `en` | Key |
|--------|---------------|------|------|-----|
| Status | `ACTIVE` | Actif | Active | `status.ACTIVE` |
| Status | `ON_HOLD` | En pause | On Hold | `status.ON_HOLD` |
| Role | `CHEF_PROJET` | Chef de Projet | Project Manager | `roles.CHEF_PROJET` |

Backend-authored free-text messages are treated as non-localized; the frontend prefers its own i18n
keys for user-facing errors (e.g. login/validation) rather than echoing backend prose.

## 6. Adding a new language (zero code change)

1. Add the code to `availableLangs` in `core/i18n/transloco.providers.ts` and to
   `LanguageService.SUPPORTED` + `options`.
2. Drop `public/i18n/<lang>.json` (root) and `public/i18n/<scope>/<lang>.json` for each scope.

No component is modified. Missing keys fall back to `fr` (`fallbackLang` + `useFallbackTranslation`).

## 7. Adding / migrating a component (the repeatable pattern)

1. Create `public/i18n/<scope>/fr.json` + `en.json` for that feature's strings.
2. In the component: import `TranslocoModule`; add `providers: [provideTranslocoScope('<scope>')]`.
3. Replace every literal in the template with `{{ '<scope>.<key>' | transloco }}` (and shared tokens
   with `common.*` / `status.*` / `roles.*`). Bind placeholders/titles with the pipe too.
4. Move any error/notification literals in the `.ts` to keys (store key in the signal, pipe in template).
5. Verify both languages via the switcher.

## 8. Migration status (per component)

| Component | Scope | Status |
|-----------|-------|:------:|
| `layout/sidebar` (nav, palette, footer) | root (`nav`,`common`) | ✅ done |
| `layout/language-switcher` | root (`lang`) | ✅ done |
| `features/auth/login` | `auth` | ✅ done |
| `features/auth/change-password` | `auth` | ✅ done |
| Shared tokens (`roles.*`, `status.*`, `common.*`, `validation.*`) | root | ✅ done |
| `features/dashboard` | `dashboard` | ⏳ pattern-ready |
| `features/projects/project-list` | `project` | ⏳ pattern-ready |
| `features/projects/project-detail` | `project` | ⏳ pattern-ready |
| `features/projects/project-form` | `project` | ⏳ pattern-ready |
| `features/admin/user-list` | `user` | ⏳ pattern-ready |
| `features/billing` | `billing` | ⏳ pattern-ready |
| `features/workload` | `workload` | ⏳ pattern-ready |
| `features/missions` | `mission` | ⏳ pattern-ready |
| `features/kpi` | `kpi` | ⏳ pattern-ready |
| `features/resources` | `tcc` | ⏳ pattern-ready |
| `features/governance` | `governance` | ⏳ pattern-ready |
| `features/di` | `di` | ⏳ pattern-ready |

`✅ done` = refactored + verified in FR/EN. `⏳ pattern-ready` = infrastructure in place; apply §7.
The architecture, switcher, persistence, root catalogs and two feature scopes are proven end-to-end;
remaining pages are a mechanical application of the same pattern.

## 9. Guardrail — no hardcoded strings going forward

Any new component MUST follow §7. A literal user-facing string in a template or component is a
review-blocking defect. (A lint rule / CI grep for suspicious literals in `template:` blocks is a
recommended follow-up enhancement.)
