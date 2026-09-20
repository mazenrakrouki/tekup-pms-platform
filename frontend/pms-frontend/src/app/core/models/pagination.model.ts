// The JSON envelope a Spring Data Page<X> becomes: one page of rows plus paging metadata.
// Field names (number, size, first, last) match Jackson's output from Page exactly — don't
// rename them, or the interface stops describing the real JSON and reads come back undefined.

// Generic so every paged endpoint (ProjectService.list/listAll, WorkloadService.list*, the
// admin user list) reuses one declaration instead of a Page-per-entity copy that can drift.
export interface PagedResponse<T> {
  // Rows of THIS page only — use totalElements below for the overall count, not content.length.
  content: T[];
  // Total rows matching the query across all pages; what the UI shows as "N results".
  totalElements: number;
  // How many pages exist in total (declared for completeness; <app-pagination> derives its
  // own page count instead, to avoid two sources of truth).
  totalPages: number;
  // Zero-based page index (Spring's convention); <app-pagination> turns it into "page 1 of N".
  number: number;
  // Rows requested per page, not rows returned — see numberOfElements for the real count.
  size: number;
  // Whether this is the first/last page. Not read today (<app-pagination> derives it itself)
  // but kept since Jackson does send it.
  first: boolean;
  last: boolean;
  // Rows actually on this page (== content.length); smaller than `size` on the last page.
  numberOfElements: number;
}
