# Design Review

**Date:** 2026-08-22
**Scope:** whole Angular front end — page headers, project lists, shared components, i18n coverage
**Method:** static audit of all 63 component files, plus live inspection in the browser against the
running stack

---

## Assessment

The interface is in better shape than the reported symptoms suggest. The design system is sound:
semantic tokens for colour, spacing, radius and motion, a working dark mode, a consistent card and
table vocabulary, and a permission-driven navigation that hides what a user cannot reach. The
problems found are not architectural. They are two habits applied repeatedly.

**Habit one: explaining the page to someone already on it.** Six modules carried a paragraph under
the title restating what the page does, and two carried a running count dressed up as prose — "20
projets · liste complète". None of it helped anyone operate the screen. The worst case stacked four
lines of heading before the first control, because a shared component printed its own title and
description directly beneath the page's own.

**Habit two: shipping a module without wiring it to i18n.** This is the real finding, and it was
reported as a bug in the language switcher. The switcher is not broken. Eight components were wired
to Transloco and fifteen were not, and the fifteen are precisely the modules that "do not
translate". The i18n architecture is fine — scopes, lazy catalogues, a fallback handler, a working
switcher. It simply was never applied past the first few screens.

A subtlety worth recording: the fallback is configured as `fallbackLang: 'fr'` with
`useFallbackTranslation: true`. A missing English key renders French rather than raising anything.
That is why the gap looked like intermittent breakage instead of absent coverage, and it is why the
default language must stay French until the catalogues are complete.

## What was corrected

Shared components were taken first, because one fix propagates to every module that embeds them.
`project-picker` appears in seven modules and was responsible, on its own, for four of the six
reported UX complaints: the duplicate title, the "Accès rapide" banner, the project code above every
name, and the six-item list.

| Area | Before | After |
|---|---|---|
| Headings per screen | up to 4 lines | 1 title, 1 action line |
| Helper paragraphs | 6 modules | 0 |
| "Accès rapide" banner | present | removed |
| Project code placement | above every card name | tooltip; table column retained |
| Project list page size | 6 shortcuts / 10 rows | 12 and 12 |
| Picker language switch | non-functional | FR↔EN verified, including status badges |

## What is not finished

**609 lines of hardcoded French across 32 files.** Eleven feature modules still need scope creation
and string extraction. This is mechanical but not small, and doing it badly — extracting strings
without checking key parity — produces a worse failure than leaving it, because the French fallback
masks the gaps.

The order is set in the backlog (D-09), largest first, and the rule is that `defaultLang` flips to
English only after the last module is converted.

## Judgements worth stating

**The shortcut cards were kept.** The rule was to delete marketing-style sections, and the "Accès
rapide" label was exactly that. The cards underneath are not: they are a direct route back into work
in progress, they replace navigating a 20-row table, and removing them would cost a real
interaction to satisfy a rule aimed at the label. The label went; the function stayed.

**The project code was moved, not deleted.** It identifies a record, and people search by it. Under
every card name it was noise, competing with the name for the first line. As a tooltip and a table
column it stays available where it is actually used.

**Tooltips were not used as a dumping ground.** The instruction was to relocate helper text to
hover. Applied literally, that trades a visible paragraph for a hidden one and calls it an
improvement. Where the removed text was genuinely redundant — "Marge, coût estimé final (EAC) et
rentabilité de votre portefeuille projets" above a page of exactly those figures — it was deleted
outright rather than tucked behind an icon.

## Verified in the browser

Against the running stack, logged in as a project manager:

- One `.page-title` per screen; zero `.page-subtitle`
- `.pp-hero-title`, `.pp-qa-label` and `.pp-qa-code` absent from the DOM
- Browser table renders 12 rows, pagination reports `1–12 of 20 · Page 1 of 2`
- FR→EN→FR round trip on the picker updates prompt, button and status badge with no refresh
- No console errors, no missing-key warnings
