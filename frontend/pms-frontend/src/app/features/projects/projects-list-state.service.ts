import { Injectable, signal } from '@angular/core';
import { Params } from '@angular/router';

/**
 * Remembers the Projects list's last query (search / filters / sort / page) so that
 * detail and edit pages can offer a breadcrumb that returns to the *exact* list state
 * the user left — not a reset list. Deep-linking straight to a detail leaves this empty,
 * so the breadcrumb falls back to a plain `/projects`.
 *
 * Session-scoped, in-memory only (the URL remains the source of truth for the list itself).
 */
@Injectable({ providedIn: 'root' })
export class ProjectsListStateService {
  /** Compact query params (no null/empty values) mirroring the list's current URL. */
  readonly query = signal<Params>({});

  set(params: Params): void {
    this.query.set(params);
  }
}
