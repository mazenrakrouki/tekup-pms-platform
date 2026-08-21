# UX Navigation Improvements

## Problem Statement

The application had a critical UX regression: selecting a project in any feature module
stored the selection in an Angular in-memory signal only. Browser back would exit the
page entirely instead of returning to the project-picker state, and there was no in-app
back button to return to the project list.

---

## Pages Fixed

### 1. Workload (`/workload`)
**Before:** Selecting a project had no URL change. Browser back exited the workload
page. No in-app back button existed.  
**After:**
- Selecting a project pushes `/workload?p={projectId}` to the browser history.
- Browser back → restores `/workload` (empty project picker).
- Breadcrumb shows `← Charges de travail` back button when a project is selected.
- Refreshing `/workload?p=123` auto-selects the project.

### 2. Billing (`/billing`)
**Before:** Same as Workload — in-memory only, no back button.  
**After:**
- URL: `/billing?p={projectId}`
- Back button: `← Facturation` in breadcrumb.
- Full query-param persistence and auto-selection on refresh.

### 3. Missions (`/missions`)
**Before:** Same pattern.  
**After:**
- URL: `/missions?p={projectId}`
- Back button: `← Missions` in breadcrumb.

### 4. Governance (`/governance`)
**Before:** Same pattern. Sub-tab (Risques / Livrables / Changements / Parties) was
lost on browser back.  
**After:**
- URL: `/governance?p={projectId}&tab={tabName}`
- Back button: `← Gouvernance` in breadcrumb.
- Sub-tab is preserved in the URL and restored on browser back.
- Switching tabs updates the URL (replaceUrl: true — no history spam).

### 5. Project List (`/projects`)
**Before:** Page number, search text, status filter, and active/archived mode were
all in-memory signals. Navigating to a project detail page and pressing browser back
returned to `/projects` but reset all filters to default.  
**After:**
- URL: `/projects?page=2&search=foo&status=ACTIVE&mode=archived`
- All filter/pagination state is synced to the URL using `replaceUrl: true` (no
  history spam per keystroke).
- On init, the component reads URL params and restores the exact previous state.
- Browser back from project detail restores the exact page/search/filter/mode the
  user had before navigating away.

---

## Navigation Strategy

### URL Query Params (Primary Strategy)

All list/picker state is encoded in URL query params. This approach:

- **Works with browser back/forward natively** — Angular Router restores the previous URL,
  the component reads params on init and restores state.
- **Supports deep linking and bookmarking** — share a URL with filters and it works.
- **No service required** — state lives in the URL, not in memory or sessionStorage.
- **Survives component destruction/recreation** — params are always available.

### State Persistence Rules

| Component | Params persisted | replaceUrl? |
|-----------|-----------------|-------------|
| Project List | `page`, `search`, `status`, `mode` | `true` (replace on change) |
| Workload | `p` (projectId) | `false` (push on select) |
| Billing | `p` (projectId) | `false` (push on select) |
| Missions | `p` (projectId) | `false` (push on select) |
| Governance | `p` (projectId), `tab` (sub-tab) | `false` for project select; `true` for tab change |

Using `replaceUrl: true` for filters means the user doesn't have to press back 20 times
to exit the list. Using `replaceUrl: false` for project selection means browser back
correctly un-selects the project and returns to the picker.

### queryParamMap Subscription Pattern

Project-picker pages subscribe to `route.queryParamMap` inside the `listAll()` callback:

```typescript
ngOnInit(): void {
  this.projectSvc.listAll().subscribe(list => {
    this.projects.set(list);
    this.route.queryParamMap.subscribe(params => {
      const pid = params.get('p');
      if (!pid) { this.selected.set(null); return; }
      const project = list.find(p => String(p.id) === pid);
      if (project && this.selected()?.id !== project.id) {
        this.selected.set(project);
        this.loadData(project);
      }
    });
  });
}
```

This handles both initial load (with `?p=` already in URL) and same-component
navigation (e.g., `clearSelection()` updating URL params without leaving the page).

### In-App Back Button

Every project-picker page now shows a breadcrumb back button when a project is selected:

```html
@if (selected()) {
  <button class="bc-back-btn" (click)="clearSelection()" title="Retour">
    <i class="bi bi-arrow-left"></i> Feature Name
  </button>
  <span class="bc-sep">›</span>
  <span class="bc-curr">{{ selected()!.code }}</span>
} @else {
  <span class="bc-curr">Feature Name</span>
}
```

`clearSelection()` navigates to the same route without `?p=`, which triggers the
queryParamMap subscription and clears the signal.

The `.bc-back-btn` class is defined globally in `styles.scss` under `.tb-breadcrumb`.

---

## Files Changed

| File | Change |
|------|--------|
| `src/styles.scss` | Added `.bc-back-btn` global style inside `.tb-breadcrumb` |
| `features/workload/workload.component.ts` | Router injection, `?p=` sync, `clearSelection()`, breadcrumb |
| `features/billing/billing.component.ts` | Router injection, `?p=` sync, `clearSelection()`, breadcrumb |
| `features/missions/missions.component.ts` | Router injection, `?p=` sync, `clearSelection()`, breadcrumb |
| `features/governance/governance.component.ts` | Router injection, `?p=`+`?tab=` sync, `clearSelection()`, `setGovTab()`, breadcrumb |
| `features/projects/project-list/project-list.component.ts` | URL sync for page/search/status/mode |

---

## Future Recommendations

1. **Scroll position**: Angular does not natively restore scroll position on browser back
   inside an SPA. Add a `ScrollRestorationService` that saves `window.scrollY` before
   route leave and restores it after route enter for list pages.

2. **Admin pages** (`/admin/users`, `/admin/roles`, `/admin/permissions`): apply the
   same URL-sync pattern for their search/pagination if they add filters later.

3. **KPI page**: currently has a view toggle (portfolio/cards). Add `?view=` to preserve
   the user's preferred view across navigation.

4. **Project Detail tabs**: the `tab` query param pattern used in Governance could be
   applied to the project detail page (`/projects/:id?tab=equipe`) so the active tab is
   preserved when the user goes back to the project after navigating elsewhere.

5. **sessionStorage fallback**: for any state that cannot easily be encoded in the URL
   (e.g., expanded accordion sections, scroll position), consider a lightweight
   `NavigationStateService` that writes to `sessionStorage` so state survives
   cross-route navigation but not page refresh.
