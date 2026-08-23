# PMS Internationalization Audit

**Date:** 2026-08-23
**Scope:** whole Angular front end — every feature module, shared component, service, model and route
**Method:** static scan of all `.ts` files plus live verification in the browser against the running
stack, driving the language switcher and reading the rendered DOM

---

## 1. Root cause

**The language switcher is not broken.** Transloco is configured correctly: `reRenderOnLangChange`
is on, the loader resolves scoped catalogues, the switcher calls `setActiveLang`, and the selection
persists in `localStorage` under `pms_lang`.

The defect is **coverage**, not architecture. Auditing every component for a Transloco import gave a
clean split, and the modules reported as "not translating" were exactly the ones with no import.
Those components hold French string literals inside inline templates and inside TypeScript, so no
language switch can reach them.

Three aggravating factors turned missing coverage into something that *looked* like an
intermittent bug:

**1. The French fallback hides the gap.** `transloco.providers.ts` sets `fallbackLang: 'fr'` with
`missingHandler.useFallbackTranslation: true`. A missing English key silently renders French and
raises nothing. A page can therefore be half-translated with a clean console.

**2. Angular's formatting pipes never followed the language at all.** This was the finding not
visible from reading templates: **no locale data was registered and `LOCALE_ID` was never
provided**. Angular's `date`, `number` and `currency` pipes read `LOCALE_ID`, not Transloco, so they
formatted as `en-US` regardless of the selected language — including while the rest of the interface
was in French. Dates such as `15/01/2022` were hardcoded formats rather than localized output.

**3. `translate()` is a one-shot read.** Values obtained through `TranslocoService.translate()` are
plain strings. They are correct for transient output such as toasts and confirm dialogs, but a
string captured once into a signal or an array would not update on a language change. Enum-to-label
maps in the data layer are the same problem in a different place: `PROJECT_STATUS_LABELS` in
`core/models/project.model.ts` is a French `Record` that cannot react to anything.

**Correction of an earlier figure.** The first audit reported "609 lines across 32 files". That
count included code comments. Re-measured with comments separated
(`frontend/pms-frontend/tools/i18n-audit.py`), the real figure was **482 visible UI lines across 28
files**, with 105 comment lines that have no effect on the interface. In particular the `core/`
files were reported as a translation problem when they are mostly French explanations of code — only
two genuine label maps live there.

## 2. Architectural corrections applied

| Correction | Where | Effect |
|---|---|---|
| French locale data registered; `LOCALE_ID` resolved from the persisted language | `main.ts`, `app/app.config.ts` | `date`, `number` and `currency` pipes now format per language instead of always `en-US` |
| Reactive locale exposed to templates | `LanguageService.current()` → `computed()` in components | pipes given an explicit locale switch live, with no reload |
| Single business-value catalogue `labels.*` | `public/i18n/{en,fr}.json` | 34 enum values translated at presentation time; codes stay technical in the database |
| Project status routed through the existing root `status.*` | picker, project detail | one vocabulary for statuses everywhere |
| Month names via `Intl.DateTimeFormat` | project detail | replaced a hardcoded French array |
| Page titles reuse `nav.*` | every module | menu label and page heading are always the same words |

## 3. Findings

| Module | Issue | Type | Severity | Root cause | Fixed | Verified |
|---|---|---|---|---|---|---|
| Global | `date`/`number`/`currency` always `en-US` | Localization | **Critical** | no locale data, no `LOCALE_ID` | ✅ | ✅ browser |
| Global | Enum/business values hardcoded French | Dynamic content | **Critical** | label maps in `core/models` | ✅ `labels.*` | ✅ browser |
| Global | Missing English key renders French silently | Architecture | High | `fallbackLang: 'fr'` + `useFallbackTranslation` | ⚠️ kept deliberately | — |
| Project detail | Tabs, info card, KPI block, tables, dialogs, toasts | Hardcoded text | **Critical** | not wired to Transloco | ✅ | ✅ browser |
| Project detail | `dd/MM/yyyy` hardcoded, French month array | Localization | High | fixed format strings | ✅ | ✅ browser |
| Admin — users | Whole module | Hardcoded text | High | not wired | ✅ | ✅ browser |
| Admin — roles | Whole module | Hardcoded text | High | not wired | ✅ | ✅ browser |
| Admin — permissions | Module codes shown raw (`CHARGE`, `EQUIPE`) | Missing keys | Medium | no label map | ✅ `admin.modules.*` | ✅ browser |
| Project picker (shared) | Whole component | Hardcoded text | **Critical** | shared by 7 modules | ✅ | ✅ browser |
| Resources / TCC | Whole module | Hardcoded text | High | not wired | ✅ | ✅ browser |
| Agile planning | Whole module | — | — | built bilingual | ✅ | ✅ browser |
| Billing, Missions, Governance | Page had no `<h1>` at all | UX / structure | High | relied on the picker's title | ✅ | ✅ browser |
| Governance | 84 lines | Hardcoded text | High | not wired | ❌ | — |
| Billing | 57 lines | Hardcoded text | High | not wired | ❌ | — |
| Missions | 39 lines | Hardcoded text | High | not wired | ❌ | — |
| Workload | 36 lines | Hardcoded text | High | not wired | ❌ | — |
| Project form | 33 lines | Hardcoded text | High | not wired | ❌ | — |
| KPI | 32 lines | Hardcoded text | High | not wired | ❌ | — |
| Project list | 26 lines | Hardcoded text | High | not wired | ❌ | — |
| Project detail | 22 lines remaining (dialogs, minor labels) | Hardcoded text | Medium | partial conversion | ❌ | — |
| Internal quote | 19 lines | Hardcoded text | Medium | not wired | ❌ | — |
| `core/services/*` | 20 lines of user-facing text | Hardcoded text | Medium | messages built in services | ❌ | — |
| `app.routes.ts` | 7 route titles (browser tab) | Hardcoded text | Low | static route `title` | ❌ | — |
| `confirm-modal`, `toast-container` | 3 lines | Hardcoded text | Medium | shared components | ❌ | — |

## 4. Progress

| Measurement | Lines | Files |
|---|---|---|
| Start of this pass | 482 | 28 |
| Now | **412** | 28 |

Converted so far: project picker, resources, agile, admin (users, roles, permissions), and the
overview of project detail — plus the two architectural fixes, which benefit every module including
the unconverted ones.

## 5. Verified in the browser

Driven against the running stack, reading the rendered DOM rather than the source.

**Project detail** — the page reported in the request:

| Element | English | French |
|---|---|---|
| Tabs | Overview & KPIs · Team · Workload · Billing · Missions · Governance | Infos & KPI · Équipe · Charges · Facturation · Missions · Gouvernance |
| Card headers | Project information · Real-time KPIs | Informations du projet · KPI temps réel |
| Status badge | Completed | Terminé |
| Definition list | Status, Project manager, Period, Created, Initial budget, Revised budget, Effective budget | Statut, Chef de projet, Période, Date de création, Budget initial, Budget révisé, Budget effectif |
| Created date | `Jan 15, 2022, 12:00 AM` | `15 janv. 2022, 00:00` |

The date row is the proof that the `LOCALE_ID` fix works: the same value, formatted by locale, with
no page reload.

**Admin** — permission catalogue module labels switch `Agile planning / Workload / Team / Billing`
↔ `Planification agile / Charges / Équipe / Facturation`, and the new AGILE module appears with a
proper label rather than a raw code.

**Shared picker** — prompt, button and status badge all switch, in every module that embeds it.

## 6. Not done

**412 lines across the remaining feature modules**, listed in the table above and ordered by size in
`docs/design/DESIGN_IMPROVEMENT_BACKLOG.md` (D-09). Governance, Billing and Missions are the three
largest.

**`defaultLang` stays `'fr'`.** It must not flip to English until the last module is converted:
with `fallbackLang: 'fr'` the missing keys would render French inside an otherwise English
interface, which is the exact failure mode this audit set out to remove, only reversed.

**Automated i18n tests are not yet written.** The practical substitute in place is
`tools/i18n-audit.py`, which reports the remaining count and fails visibly when it grows, plus the
key-parity check run after every catalogue edit (EN and FR key sets must be identical).
