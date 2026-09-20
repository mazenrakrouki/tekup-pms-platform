import { Injectable, signal } from '@angular/core';
import { Params } from '@angular/router';

// Remembers the Projects list's last query (search/filter/page) so project-detail and
// project-form can rebuild the "back to list" link. providedIn:'root' because the three
// components are siblings (no @Input path). Not localStorage: the URL stays source of truth.

@Injectable({ providedIn: 'root' })
export class ProjectsListStateService {

  // Signal, not a plain field: the back-link template reads it reactively and stays in sync.
  readonly query = signal<Params>({});

  /** Stores the list's query. Overwrites rather than merges: callers always send full state. */
  set(params: Params): void {
    this.query.set(params);
  }
}
