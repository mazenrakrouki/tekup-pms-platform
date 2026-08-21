# Task 2 — Humanization pass on the PFE report prose

## Chat interface output

Ran the AI-pattern removal pass over all 14,395 words of report prose. The vocabulary was already
clean; the one real tell was em dash density. Eliminated all 59 parenthetical em dashes in running
prose, restructured into commas, colons, parentheses or separate sentences depending on what each
sentence needed. Total `---` count dropped from 332 to 257; the remainder are chapter titles, table
captions and one genuine definition list, all of which are correct typography and were kept.

Also cut one instance of promotional vocabulary: "expresses a rich and proven business logic"
became "expresses a proven business logic" ("rich" in the figurative sense is on the promotional
list).

Rebuild: 79 pages, 0 errors. The two worst overfull boxes (68pt, 60pt) were confirmed pre-existing
and then fixed, along with a template bug that was overflowing every abbreviation entry. See the
typography follow-up below.

---

## Task overview

Apply the humanizer passes to the report prose without damaging its academic register.

**Passes 1–7 applied** (structure tells, significance inflation, AI vocabulary, grammar-level
patterns, rhythm and style, hedging and filler, connective tissue).

**Pass 8 deliberately not applied.** That pass calls for first-person voice, casual asides, opinions
and "letting some mess in". That is right for a blog post and wrong for a PFE report submitted to a
jury. The formal register was preserved throughout.

## What the scan found

Scanned for the full Tier 1 and Tier 2 tell lists across `introduction.tex`, `chap_01`–`chap_07.tex`
and `conclusion.tex`.

| Pattern | Occurrences |
|---|---|
| `delve`, `landscape`, `tapestry`, `leverage`, `harness`, `realm`, `myriad`, `plethora`, `showcase` | 0 |
| `crucial`, `pivotal`, `testament`, `groundbreaking`, `seamless`, `cutting-edge` | 0 |
| `Additionally`, `Moreover`, `Furthermore`, `In today's`, `ever-evolving` | 0 |
| `It is important to note`, `serves as`, `stands as` | 0 |
| `rich` (figurative) | 1 — fixed |
| Negative parallelism ("not only… but") | 1 — kept, single use is normal English |
| Parenthetical em dashes in prose | **59 — all fixed** |

The prose was written well to begin with. Em dash density was the only pattern present at a level
that reads as machine-generated: one every 43 words counting all uses, against a human frequency of
roughly one per three or four paragraphs.

## Method

A naive count of `---` returns 332, which is misleading. Three uses are legitimate and were
excluded before any editing:

- chapter and section titles, e.g. `\chapter{Release 1 --- Foundation and Security}`
- table captions, e.g. `\caption{Test cases --- Sprint 1}`
- the four-item architecture definition list in `chap_03.tex`, e.g.
  `\item \textbf{Controller} --- REST entry point, request validation, authorization annotation.`

Filtering those left 59 genuine parenthetical asides, distributed as: introduction 3, chap_01 9,
chap_02 2, chap_03 5, chap_04 5, chap_05 6, chap_06 5, chap_07 8, conclusion 2.

Each was rewritten individually rather than substituted mechanically, because the right replacement
differs by sentence. Examples:

| Before | After |
|---|---|
| `Separating the two tokens --- access token in memory, refresh token in a cookie --- protects` | `Separating the two tokens, access token in memory and refresh token in a cookie, protects` |
| `a spreadsheet cannot partition information finely --- for instance, preventing a developer…` | `a spreadsheet cannot partition information finely; it cannot, for instance, prevent a developer…` |
| `interdependent --- the KPI engine consumes workload, billing, cost rates and the internal quote --- so it cannot be built…` | `interdependent: the KPI engine consumes workload, billing, cost rates and the internal quote. It cannot be built…` |
| `distinguishes the workload plan --- what is forecast --- from the actual workload --- what was consumed.` | `distinguishes the workload plan (what is forecast) from the actual workload (what was consumed).` |
| `a permission the user is not entitled to use is not merely disabled --- it is absent.` | `…is not merely disabled: it is absent.` |

## Final status

Prose parenthetical em dash count is now **0 in every file**. Report compiles at **79 pages,
0 errors**.

## Typography follow-up (same session)

The two large overfull boxes flagged above were then fixed, and a third defect was found while
doing so.

| Defect | Cause | Fix | Result |
|---|---|---|---|
| 68.6pt overfull | `\texttt{POST /api/auth/change-password}` cannot hyphenate | `\allowbreak` at the path separators | resolved |
| 60.5pt overfull | `\texttt{spring.jpa.hibernate.ddl-auto=validate}` cannot hyphenate; `\allowbreak` alone was not enough because TeX declined the break | sentence restructured so the property and its value are separate `\texttt` runs | resolved |
| **28 identical 32.2pt overfulls** | **Template bug in `tpl/isipfe.cls`.** `\sortitem` lays out three minipages at `0.1 + 0.05 + 0.85 = 1.00\columnwidth`, but they sit inside an `itemize`, which indents. Every abbreviation therefore overflowed by the same fixed amount | third minipage `0.85` → `0.75\columnwidth` | all 28 resolved |

The third one is pre-existing, not introduced by the abbreviations rewrite: the original 34-entry
list produced the same defect 34 times. Backup of the class file at `tpl/isipfe.cls.bak-acronyms`.

**Overfull progression:** 84 boxes / worst 68.6pt → **55 boxes / worst 29.0pt**.

The remaining worst two (29.0pt and 22.8pt) are in the school's front-matter template — cover page
and signature block — and were left alone. Verified the abbreviations page still renders correctly
at the narrower width: English, alphabetically sorted, no overflow.
