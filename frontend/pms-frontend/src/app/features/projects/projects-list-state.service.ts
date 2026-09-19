import { Injectable, signal } from '@angular/core';
import { Params } from '@angular/router';

// =============================================================================
// FILE: projects-list-state.service.ts   ("memory of the projects list")
// =============================================================================
// WHAT THIS FILE IS
//   A very small shared memory. It holds ONE thing: the query the user had on the
//   Projects list (search text, filters, sort, page number) the last time that
//   list was shown. Nothing else in the app stores that.
//
// WHERE IT SITS IN THE FLOW (who writes, who reads)
//   WRITER  project-list.component.ts calls set(...) every time the list is drawn
//           or the user changes a filter.
//   READERS project-detail.component.ts and project-form.component.ts read query()
//           and put it in the "back to the list" link:
//               <a [routerLink]="['/projects']" [queryParams]="listState.query()">
//   So the flow is:  list  ->  set()  ->  [this service]  ->  query()  ->  back link.
//
// WHY IT EXISTS (what breaks if you delete it)
//   Without it the back link would be a plain /projects with no parameters.
//   Concrete example: the user searches "BAD", filters on "In progress", goes to
//   page 3, opens a project to read it, then clicks back. He would land on page 1
//   of the full unfiltered list and would have to redo his search every single
//   time. On a list of 80 projects that is the difference between a tool people
//   use and a tool people avoid.
//
// WHY A SERVICE AND NOT A COMPONENT FIELD
//   The three components are siblings: none of them contains the others, so they
//   cannot pass the value with @Input. A service marked providedIn: 'root' is the
//   normal Angular way for unrelated components to share one value.
//
// WHY MEMORY AND NOT localStorage
//   The URL stays the real source of truth for the list. This is only a shortcut
//   so the back link can rebuild it. Saving it on disk would bring a stale filter
//   back days later and confuse the user more than it helps.
// =============================================================================

// @Injectable marks this class as something Angular can create and hand out.
//   providedIn: 'root' means Angular builds exactly ONE instance for the whole
//   application (a "singleton") and gives that same instance to every component
//   that asks for it.
//   WITHOUT providedIn: 'root': each component would get its own copy, the list
//   would write into its copy, the detail page would read an empty copy, and the
//   back link would silently lose the filters — the exact bug this file prevents.
@Injectable({ providedIn: 'root' })
export class ProjectsListStateService {

  // A signal is Angular's reactive box for a value: read it with query(), and any
  //   template that reads it redraws by itself when the value changes.
  //   WHY a signal and not a plain field: the back link is written as
  //   [queryParams]="listState.query()". Because it is a signal, Angular knows the
  //   link depends on it and refreshes the link on its own. A plain field would be
  //   read once and the link could keep pointing at an old filter.
  // readonly protects the BOX, not the value: nobody outside can swap the signal
  //   for another one, but set() below can still change what is inside.
  // <Params> is the Angular type for a bag of URL query parameters, such as
  //   { search: 'BAD', status: 'EN_COURS', page: 3 }.
  // The starting value {} means "we have not seen the list yet". That is the case
  //   when somebody opens a project link directly, for example from an email. The
  //   back link then falls back to a plain /projects, which is the right answer.
  readonly query = signal<Params>({});

  /**
   * Stores the list's current query.
   *
   * <p>Called by project-list.component.ts with a COMPACT object. Its
   * buildCompact() method keeps only the values that are NOT the default one:
   * it writes 'page' only when the page is not 0, 'sort' only when sorting is not
   * on 'code', 'size' only when the page size is not 20, and it drops 'search'
   * and 'status' when they are empty.
   * That matters for the reader, because a link carrying /projects?search=&page=0
   * would be a long ugly URL that says nothing, and an empty 'search=' in the URL
   * is not the same thing as no search at all for the list that reads it back.
   *
   * <p>It overwrites rather than merges, on purpose: the list always sends its
   * complete current state, so merging would keep a filter the user has just
   * cleared.
   */
  set(params: Params): void {
    this.query.set(params);
  }
}
