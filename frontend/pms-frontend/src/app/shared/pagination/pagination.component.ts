import { Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslocoModule } from '@jsverse/transloco';

/**
 * Reusable pagination bar (<app-pagination>): "1-20 of 137", page-size dropdown, prev/next.
 * Presentational only — the parent owns page/pageSize state and reacts to the outputs, so the
 * same bar works for client-side and server-paged lists alike. Part of the shared "no giant
 * dropdowns" UX pattern, alongside <app-project-picker>.
 */
@Component({
  selector: 'app-pagination',
  standalone: true,
  imports: [CommonModule, TranslocoModule],
  template: `
    <!-- Empty list shows nothing, so the parent's own empty-state message is the only thing seen. -->
    @if (total() > 0) {
      <div class="pg-bar">
        <div class="pg-info">{{ 'pagination.range' | transloco: { from: from(), to: to(), total: total() } }}</div>
        <div class="pg-controls">
          <select class="form-select form-select-sm pg-size"
                  (change)="onSize($event)"
                  [attr.aria-label]="'pagination.itemsPerPage' | transloco">
            <!-- track s keeps option identity stable so an open dropdown doesn't get rebuilt under the mouse. -->
            @for (s of pageSizeOptions(); track s) {
              <option [value]="s" [selected]="s === pageSize()">{{ 'pagination.perPage' | transloco: { size: s } }}</option>
            }
          </select>
          <!-- page is 0-indexed; disabled (not hidden) so the bar keeps a constant width. -->
          <button class="btn btn-outline-secondary btn-sm btn-icon"
                  [disabled]="page() === 0" (click)="go(page() - 1)" [attr.aria-label]="'pagination.previous' | transloco">
            <i class="bi bi-chevron-left"></i>
          </button>
          <span class="pg-page">{{ 'pagination.pageOf' | transloco: { current: page() + 1, total: totalPages() } }}</span>
          <!-- >= rather than == so an out-of-range page from the parent still disables the arrow. -->
          <button class="btn btn-outline-secondary btn-sm btn-icon"
                  [disabled]="page() >= totalPages() - 1" (click)="go(page() + 1)" [attr.aria-label]="'pagination.next' | transloco">
            <i class="bi bi-chevron-right"></i>
          </button>
        </div>
      </div>
    }
  `,
  styles: [`
    .pg-bar { display: flex; align-items: center; justify-content: space-between; gap: 1rem;
      flex-wrap: wrap; padding: .625rem 1rem; border-top: 1px solid var(--border); }
    .pg-info { font-size: 12px; color: var(--text-2); }
    .pg-controls { display: flex; align-items: center; gap: .5rem; }
    .pg-size { width: auto; }
    /* tabular-nums + min-width keep "Page 9 of 9" -> "Page 10 of 10" from shifting the next arrow. */
    .pg-page { font-size: 12px; color: var(--text-2); min-width: 92px; text-align: center;
      font-variant-numeric: tabular-nums; }
  `]
})
/** Holds no state of its own: page/pageSize/total are the single owner's inputs, so the bar can't drift out of sync with the parent. */
export class PaginationComponent {
  // 0-indexed, matching Spring Data's Pageable on the backend.
  page = input.required<number>();
  pageSize = input.required<number>();
  // Total rows across all pages, not just what's currently rendered.
  total = input.required<number>();
  pageSizeOptions = input<number[]>([10, 20, 50]);

  pageChange = output<number>();
  pageSizeChange = output<number>();

  // Math.max(1, ...) avoids "Page 1 of 0" and a -1 comparison in the next-arrow guard.
  readonly totalPages = computed(() => Math.max(1, Math.ceil(this.total() / this.pageSize())));
  readonly from = computed(() => this.total() === 0 ? 0 : this.page() * this.pageSize() + 1);
  // Math.min clamps to the real total so the last page doesn't overshoot (e.g. "121-140 of 137").
  readonly to = computed(() => Math.min(this.total(), (this.page() + 1) * this.pageSize()));

  /** Requests a page change; stays silent on an out-of-range value instead of correcting it. */
  go(p: number): void {
    if (p >= 0 && p < this.totalPages()) this.pageChange.emit(p);
  }

  /** Reports the chosen page size upward; + converts the select's string value to a number. */
  onSize(e: Event): void {
    this.pageSizeChange.emit(+(e.target as HTMLSelectElement).value);
  }
}
