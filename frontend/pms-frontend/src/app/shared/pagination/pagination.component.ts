import { Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslocoModule } from '@jsverse/transloco';

/**
 * FILE: pagination.component.ts
 *
 * WHAT THIS FILE IS
 * One small, reusable Angular component (<app-pagination>) that draws the bar under a
 * list: the text "1-20 of 137", an "items per page" dropdown, a "Page 2 of 7" label and
 * the previous / next arrows.
 *
 * WHERE IT SITS IN THE FLOW
 * A parent page (for example the Projects list, the Roles list, a DI lines table) owns the
 * real state: which page is shown and how many rows fit on a page. The parent passes that
 * state down through the inputs page, pageSize and total. This component draws the bar and,
 * when the user clicks, sends a number back up through the outputs pageChange and
 * pageSizeChange. It calls no service and no HTTP endpoint: it does not know where the rows
 * come from, so the same bar works for a list already loaded in memory (client-side slicing)
 * and for a list fetched page by page from the backend. It is a "dumb" (presentational)
 * component on purpose.
 *
 * WHY IT EXISTS
 * Without it, every list screen would hand-write its own arrows, its own "of N" label and
 * its own dropdown. They would drift apart (different sizes, different wording, one of them
 * forgetting to disable the arrow on the last page) and each one would need its own
 * translations. It is also how the project keeps its UX rule "no giant dropdowns": a long
 * list is shown in pages instead of one endless select.
 *
 * THE REST OF THE SLICE
 * This folder holds this single file. Its natural partner elsewhere in shared/ is
 * <app-project-picker>, which applies the same paging idea to choosing one project.
 */
@Component({
  selector: 'app-pagination',
  // standalone: true means the component declares its own dependencies below and does not
  // need to be listed in an NgModule. Why: the whole frontend is built on standalone
  // components; without this flag Angular would refuse to use the component unless some
  // module declared it, and every page importing the bar would break.
  standalone: true,
  // Imports are declared per component here. CommonModule brings the basic Angular
  // directives and pipes; TranslocoModule brings the `transloco` translation pipe used in
  // the template below. Why: a standalone component only sees what it imports. Without
  // TranslocoModule the template would fail to compile with "the pipe 'transloco' could not
  // be found", so the bar would show nothing instead of "Page 1 of 7".
  imports: [CommonModule, TranslocoModule],
  // The template is written inline (inside backticks) instead of in a separate .html file.
  // Inside this HTML only <!-- --> comments are valid.
  template: `
    <!-- Show nothing at all when the list is empty. Why: a bar reading "0-0 of 0" with two
         dead arrows is noise, and the empty-state message of the parent page should be the
         only thing the user sees. Without this test, an empty Roles table would still
         display "Page 1 of 1" under it. -->
    @if (total() > 0) {
      <div class="pg-bar">
        <!-- "1-20 of 137". The numbers come from the computed values further down, and the
             sentence itself comes from the translation file so French and English can put
             the words in their own order. The object after the colon fills the placeholders
             of the translation key. -->
        <div class="pg-info">{{ 'pagination.range' | transloco: { from: from(), to: to(), total: total() } }}</div>
        <div class="pg-controls">
          <!-- How many rows per page. A plain select, not a two-way bound form control: the
               component never stores the chosen size, it only reports it upward. -->
          <select class="form-select form-select-sm pg-size"
                  (change)="onSize($event)"
                  [attr.aria-label]="'pagination.itemsPerPage' | transloco">
            <!-- track s tells Angular to identify each option by its own value (10, 20, 50).
                 Why: with a stable identity Angular reuses the existing option elements
                 instead of destroying and rebuilding them whenever the list is re-rendered,
                 which would close the open dropdown under the user's mouse. -->
            @for (s of pageSizeOptions(); track s) {
              <option [value]="s" [selected]="s === pageSize()">{{ 'pagination.perPage' | transloco: { size: s } }}</option>
            }
          </select>
          <!-- Previous page. Disabled on the first page (page is 0-indexed, so 0 is page 1).
               Why disabled rather than hidden: the bar keeps the same width and the buttons
               do not jump sideways while the user clicks through the pages. -->
          <button class="btn btn-outline-secondary btn-sm btn-icon"
                  [disabled]="page() === 0" (click)="go(page() - 1)" [attr.aria-label]="'pagination.previous' | transloco">
            <i class="bi bi-chevron-left"></i>
          </button>
          <!-- "Page 3 of 7". page() is 0-indexed inside the code, so +1 is added only here,
               for the human reading it. -->
          <span class="pg-page">{{ 'pagination.pageOf' | transloco: { current: page() + 1, total: totalPages() } }}</span>
          <!-- Next page. Disabled on the last page. The test uses >= and not == as a safety
               net: if the parent ever passes a page number larger than the last page, the
               arrow stays off instead of letting the user walk further into an empty list. -->
          <button class="btn btn-outline-secondary btn-sm btn-icon"
                  [disabled]="page() >= totalPages() - 1" (click)="go(page() + 1)" [attr.aria-label]="'pagination.next' | transloco">
            <i class="bi bi-chevron-right"></i>
          </button>
        </div>
      </div>
    }
  `,
  /* Styles are scoped to this component by Angular, so these class names cannot leak into
     other pages. Inside this block only block comments are valid.
     Colours are read from the CSS variables of the global design system (--border,
     --text-2) instead of being written as fixed colours. Why: the app has a light and a
     dark theme; a hard-coded grey would stay grey and become unreadable in dark mode. */
  styles: [`
    .pg-bar { display: flex; align-items: center; justify-content: space-between; gap: 1rem;
      flex-wrap: wrap; padding: .625rem 1rem; border-top: 1px solid var(--border); }
    .pg-info { font-size: 12px; color: var(--text-2); }
    .pg-controls { display: flex; align-items: center; gap: .5rem; }
    .pg-size { width: auto; }
    /* tabular-nums gives every digit the same width, and min-width reserves the space.
       Why: without them the label would grow going from "Page 9 of 9" to "Page 10 of 10"
       and push the next-page arrow sideways right under the mouse. */
    .pg-page { font-size: 12px; color: var(--text-2); min-width: 92px; text-align: center;
      font-variant-numeric: tabular-nums; }
  `]
})
/**
 * The pagination bar component.
 *
 * It holds no state of its own. Everything it shows is derived from the inputs, and every
 * user action leaves through an output. Why this way rather than letting the bar keep its
 * own "current page": with two owners of the same number the two would disagree as soon as
 * the parent changes the data (a new filter, a deleted row), and the user would sit on
 * page 5 of a list that now has 2 pages. One owner, the parent, is the rule.
 */
export class PaginationComponent {
  // input.required<number>() is a signal input: the parent must bind it, and reading it as
  // page() gives the current value and also tells the template to redraw when it changes.
  // "required" makes the compiler complain when a page forgets to bind it. Why that matters:
  // an undefined page would turn every computation below into NaN and the bar would read
  // "Page NaN of NaN".
  // page is 0-indexed (0 = first page), which matches Spring Data's Pageable on the backend.
  page = input.required<number>();
  // How many rows one page shows.
  pageSize = input.required<number>();
  // How many rows exist in total, across all pages - not the number of rows drawn right now.
  total = input.required<number>();
  // The choices offered in the dropdown. Optional, with 10/20/50 as the default, so a simple
  // list can use <app-pagination> without thinking about it while a dense table can pass its
  // own values such as [25, 100].
  pageSizeOptions = input<number[]>([10, 20, 50]);

  // output<number>() declares an event the parent listens to with (pageChange)="...".
  // It carries the new 0-indexed page number.
  pageChange = output<number>();
  // Carries the newly chosen page size. The parent decides what to do with it (usually
  // reload the rows and go back to the first page).
  pageSizeChange = output<number>();

  // computed() recalculates only when one of the values it reads changes, and gives back the
  // cached result otherwise. Why: these three values are read several times per render;
  // without computed they would be recalculated on every change detection pass.
  // Math.max(1, ...) keeps the count at least 1. Without it, 0 rows would give "Page 1 of 0"
  // and the next-arrow test (page >= totalPages - 1) would compare against -1.
  readonly totalPages = computed(() => Math.max(1, Math.ceil(this.total() / this.pageSize())));
  // First row number shown, counted from 1 for the human. The 0 special case avoids showing
  // "1-0 of 0" for an empty list.
  readonly from = computed(() => this.total() === 0 ? 0 : this.page() * this.pageSize() + 1);
  // Last row number shown. Math.min clamps it to the real total. Without it, 137 rows with
  // 20 per page would claim "121-140 of 137" on the last page.
  readonly to = computed(() => Math.min(this.total(), (this.page() + 1) * this.pageSize()));

  /**
   * Asks the parent to move to page p. Gives nothing back: the move really happens only if
   * the parent updates its own state in answer to the event.
   *
   * The guard drops a page outside the valid range instead of forcing it back inside. Why:
   * the two arrows are already disabled at both ends, so a value out of range here means
   * something unexpected happened; staying silent is safer than emitting a page the parent
   * would then have to correct.
   */
  go(p: number): void {
    if (p >= 0 && p < this.totalPages()) this.pageChange.emit(p);
  }

  /**
   * Handles the page-size dropdown and reports the chosen size to the parent.
   *
   * The DOM gives the value as text ("20"); the unary + turns it into the number 20.
   * Why that is needed: the output is typed as a number, and without the conversion a parent
   * computing page * pageSize would join two pieces of text ("0" + "20") and slice the list
   * at the wrong place. The cast to HTMLSelectElement is only for TypeScript, because
   * e.target is typed as the generic EventTarget, which has no value property.
   */
  onSize(e: Event): void {
    this.pageSizeChange.emit(+(e.target as HTMLSelectElement).value);
  }
}
