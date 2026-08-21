# Task 1 — PFE report continuation: front matter, bibliography and sprint-count consistency

## Chat interface output

Continued the PFE report. Four substantive problems fixed, all verified by rebuilding:

1. **The bibliography was silently empty.** `\nocite{*}` was commented out in `main.tex`, and `biblio.bib` still contained the LaTeX template author's 2020 references — Discord, Jira, Adobe XD, Bitbucket and **MySQL**. The report would have cited MySQL for a PostgreSQL project. Replaced with 24 references to the official documentation of technologies the code actually uses, and enabled printing.
2. **Abbreviations were in French with French glosses** in an English report, and missing several used terms. Rewritten in English, restricted to the 29 abbreviations that actually appear in the text (verified by word-boundary search).
3. **Sprint counts were stale.** Ten places said "eight sprints" / "eight increments" after Sprint 9 was added. Corrected to nine where a count was asserted; reworded where the sentence was about the 26-story backlog, which legitimately spans sprints 1–8.
4. **Chapter 7 described a Sprint 9 that chapter 2 never planned.** Added the missing Sprint 9 row to the release/sprint planning table.

Result: **79 pages, 0 errors**, 24 bibliography entries rendered, no citation warnings.

---

## Task overview

Continue the PFE report under the standing constraints: English throughout, Release/Sprint
structure, and no fabricated agile artefacts (no invented dates, ceremonies, story points,
velocity or retrospectives).

Success criteria: the report compiles without errors; every claim in it is consistent with the
implemented system; no placeholder or third-party template content remains.

Parent context: `PROJECT_TODO.md` Phase F, ADR-026.

## Execution timeline

1. Surveyed the report — file inventory, per-file word counts, remaining `% TODO` markers.
   Established that all five remaining TODOs are screenshot slots, which require the running
   application and cannot be completed from the source tree.
2. Verified Sprint 9 already present in `chap_07.tex` with all seven required subsections
   (Objective, Functional Scope, Features, Technical Realisation, Tests and Validation, Outcome,
   Transition). Content confirmed vendor-neutral, which is appropriate for an academic report.
3. Extracted every capitalised token from the body text and counted occurrences with word
   boundaries, to determine which abbreviations are genuinely used. This exposed false positives:
   `RAF` matched inside `DRAFT`, `HT` inside `HTTP`/`HTML`.
4. Rewrote `acronymes.tex` in English, limited to abbreviations actually present.
5. Inspected `biblio.bib` and found it was entirely the template author's unrelated references.
   Rewrote it with 24 entries covering the backend platform, persistence, frontend, security,
   deployment and modelling sources the implementation relies on.
6. Discovered `\nocite{*}` commented out in `main.tex`, meaning the `Webographie` section rendered
   empty. Enabled it. Commented out `\input{webo}`, which printed a duplicate one-entry
   "Bibliographie" chapter from the template.
7. Ran the LaTeX toolchain. First attempted `biber`, which failed with `Cannot find 'main.bcf'`;
   inspection of `tpl/isipfe.cls:145` showed `\usepackage[backend=bibtex]{biblatex}`, so `bibtex`
   is the correct tool. Re-ran correctly.
8. Scanned the prose for AI writing patterns. Result: essentially clean — no `delve`, `crucial`,
   `pivotal`, `showcase`, `testament`, `landscape`, `leverage`, `Additionally`, `Moreover` or
   `Furthermore` across 14,395 words.
9. Measured em dash usage: 332 total, but the great majority are legitimate — chapter and section
   titles (`Release 1 --- Foundation and Security`), table captions, and commented-out screenshot
   slots. Only 59 are parenthetical em dashes in running prose.
10. While reading those, found the substantive defects: stale sprint counts and a planning table
    that stopped at Sprint 8. Prioritised these over punctuation.
11. Applied the corrections, rebuilt, verified.

## Inputs / outputs

**Read:** `main.tex`, `tpl/isipfe.cls`, `introduction.tex`, `chap_01`–`chap_07.tex`,
`conclusion.tex`, `acronymes.tex`, `webo.tex`, `biblio.bib`, `dedicaces.tex`, `remerciement.tex`.

**Modified:**

| File | Change |
|---|---|
| `acronymes.tex` | Rewritten in English; 29 abbreviations, all verified as used |
| `biblio.bib` | Replaced template references with 24 project-relevant entries |
| `main.tex` | `\nocite{*}` enabled; duplicate `\input{webo}` commented out |
| `introduction.tex` | Sprint count corrected; backlog sentence reworded |
| `chap_01.tex` | Sprint count corrected; backlog sentence reworded |
| `chap_02.tex` | Two sprint references corrected; **Sprint 9 row added to the planning table** |
| `chap_03.tex` | Sprint count corrected |
| `chap_07.tex` | Sprint count corrected |
| `conclusion.tex` | Increment count corrected; Release 4 scope now names containerization and CI; superseded "single-artefact decision" reworded to "modular monolith" |

**Generated:** `main.pdf` (79 pages), `main.bbl` (24 entries).

## Error handling

- **`biber` failed** with `ERROR - Cannot find 'main.bcf'`. Cause: the document class declares
  `backend=bibtex`, which produces `.aux`/`-blx.bib` rather than `.bcf`. Resolved by running
  `bibtex main`.
- **Two string replacements silently missed** on the first pass, caused by backslash handling in
  the shell heredoc. Detected because the patch routine reports per-replacement status rather than
  assuming success. Re-applied using `chr(92)` to build the LaTeX backslashes, and confirmed both
  landed.
- **False positives in abbreviation detection.** A substring search counted `RAF` (inside `DRAFT`)
  and `HT` (inside `HTTP`). Re-run with word boundaries; both dropped to zero and were excluded.

## Final status

Report compiles: **79 pages, 0 errors** across two passes, no citation or bibliography warnings.

**Deliberately not done:**

- **`dedicaces.tex` and `remerciement.tex` are empty** — only headings, no content. These are
  personal statements naming family, supervisors and the host company. Writing them would mean
  inventing names and sentiments, so they are left for the author.
- **59 parenthetical em dashes in prose** remain. Reducing them is a style improvement, not a
  correctness fix, and a blanket substitution would damage otherwise good sentences. Left as a
  judgement call for the author.
- **Five screenshot slots** (`chap_05`, `chap_06`) and eight Excel figure slots (`chap_01`) remain
  commented out, awaiting captures from the running application.
- **`babel` main language is still French**, so LaTeX-generated strings ("Figure", "Table",
  "Chapitre") render in French inside an English report. Changing it touches `tpl/isipfe.cls` and
  is an open decision for the author.

**Verify the bibliography before submission.** The 24 entries are the official documentation of
technologies the code demonstrably uses, and access dates are recorded as August 2026. Confirm the
list matches what was actually consulted, and remove anything that was not.
