import { Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslocoModule } from '@jsverse/transloco';

/**
 * Reusable pagination bar: "from–to of total", page-size selector, page number, prev/next.
 * Client-side friendly (bind to 0-indexed `page`). Emits `pageChange` / `pageSizeChange`.
 */
@Component({
  selector: 'app-pagination',
  standalone: true,
  imports: [CommonModule, TranslocoModule],
  template: `
    @if (total() > 0) {
      <div class="pg-bar">
        <div class="pg-info">{{ 'pagination.range' | transloco: { from: from(), to: to(), total: total() } }}</div>
        <div class="pg-controls">
          <select class="form-select form-select-sm pg-size"
                  (change)="onSize($event)"
                  [attr.aria-label]="'pagination.itemsPerPage' | transloco">
            @for (s of pageSizeOptions(); track s) {
              <option [value]="s" [selected]="s === pageSize()">{{ 'pagination.perPage' | transloco: { size: s } }}</option>
            }
          </select>
          <button class="btn btn-outline-secondary btn-sm btn-icon"
                  [disabled]="page() === 0" (click)="go(page() - 1)" [attr.aria-label]="'pagination.previous' | transloco">
            <i class="bi bi-chevron-left"></i>
          </button>
          <span class="pg-page">{{ 'pagination.pageOf' | transloco: { current: page() + 1, total: totalPages() } }}</span>
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
    .pg-page { font-size: 12px; color: var(--text-2); min-width: 92px; text-align: center;
      font-variant-numeric: tabular-nums; }
  `]
})
export class PaginationComponent {
  page = input.required<number>();
  pageSize = input.required<number>();
  total = input.required<number>();
  pageSizeOptions = input<number[]>([10, 20, 50]);

  pageChange = output<number>();
  pageSizeChange = output<number>();

  readonly totalPages = computed(() => Math.max(1, Math.ceil(this.total() / this.pageSize())));
  readonly from = computed(() => this.total() === 0 ? 0 : this.page() * this.pageSize() + 1);
  readonly to = computed(() => Math.min(this.total(), (this.page() + 1) * this.pageSize()));

  go(p: number): void {
    if (p >= 0 && p < this.totalPages()) this.pageChange.emit(p);
  }

  onSize(e: Event): void {
    this.pageSizeChange.emit(+(e.target as HTMLSelectElement).value);
  }
}
