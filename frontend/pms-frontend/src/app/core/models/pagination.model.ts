/*
 * FILE: pagination.model.ts
 *
 * WHAT THIS FILE IS
 * One single generic shape, PagedResponse<T>, describing the JSON envelope a Spring Boot
 * endpoint sends back when it returns ONE PAGE of rows instead of a whole table.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Data JPA returns Page<X> -> Jackson writes it as JSON -> Angular HttpClient
 *     -> ProjectService.list() and listAll(), WorkloadService.listPlanCharges() and
 *        listChargesReelles() declare their Observable with this shape; the admin screen
 *        features/admin/user-list/user-list.component.ts also uses it directly, as
 *        PagedResponse<User>, because it calls HttpClient without going through a service
 *     -> the feature component reads 'content' for the rows and 'totalElements' for the
 *        count, and hands them to the shared <app-pagination> control together with the
 *        page number and page size it is holding itself.
 *   The other fields of the envelope are declared below but not read by any screen today;
 *   each one says so and says why it is still declared.
 *   Note ProjectService.listAll(): it asks for one very large page and then keeps only
 *   'content', which is how the project pickers get a full list out of a paged endpoint.
 *
 * WHY IT EXISTS
 * It is pure type information: TypeScript types are erased at compile time, so this file
 * adds not one byte to the JavaScript that ships to the browser. What it adds is checking
 * while the project is being built. Without it each paged service would either repeat the
 * same eight fields by hand or fall back to 'any', and 'any' switches the compiler off: a
 * typo such as 'res.totalElement' (missing the final s) would compile happily, arrive as
 * 'undefined' at runtime, and the pager would show "0 results" on a screen that really
 * holds hundreds of rows, with no error anywhere.
 *
 * WHY THE FIELD NAMES LOOK ODD (number, size, first, last)
 * They are not chosen here. They are the exact property names Jackson produces from a
 * Spring Data Page object. Renaming 'number' into something clearer like 'pageIndex'
 * would make this interface stop describing the real JSON, and the value read would be
 * undefined.
 */

// <T> is a type parameter: the envelope is described once and reused for every kind of
// row. PagedResponse<Project> and PagedResponse<PlanCharge> are then two separate,
// fully checked types produced from this one declaration.
// Why generic instead of one interface per screen: the envelope Spring sends is always
// identical, only the rows change. Without the generic the project would need
// ProjectPage, PlanChargePage, ChargeReellePage..., and the day a field of the envelope
// changes it would have to be fixed in every copy - and the one that was forgotten would
// only fail at runtime.
export interface PagedResponse<T> {
  // The rows of THIS page only, never the whole result set.
  // The classic mistake here is showing content.length as the number of results: with
  // size = 20, a table of 500 projects would proudly announce "20 projects" on every
  // single page. The figure to display is totalElements just below.
  content: T[];
  // How many rows match the query across ALL pages. This is what the screen shows as
  // "N results" and what tells the user there is more to see.
  totalElements: number;
  // How many pages exist in total, as Spring counts them.
  // Declared because it is in the JSON, but not read by the screens: <app-pagination>
  // computes its own page count from the total and the page size, and user-list.component
  // does the same when it has to pull the user back after the last row of a page was
  // deleted. Two sources for the same number is a real risk here - if a screen ever starts
  // reading this field, it must stop deriving it, not mix the two.
  totalPages: number;
  // Which page this is, counted FROM ZERO: the first page is 0, not 1.
  // That is Spring's convention, and it is also what the request expects back, so the
  // "next" button sends number + 1. The <app-pagination> control is the one place that
  // turns it into the "page 1 of 25" a human reads.
  // Why it matters: printing this value raw would show "page 0" on the first page, and
  // adding 1 before sending it back would skip a page on every click.
  number: number;
  // How many rows were ASKED for per page - not how many came back. The last page is
  // usually shorter; numberOfElements below is the one that says how many really arrived.
  size: number;
  // Flags computed by the server saying whether this is the first and/or the last page.
  // They are declared because Jackson really does put them in the JSON, and leaving a
  // field out of the interface while it exists in the answer is how a later developer
  // "discovers" it by guessing its name and getting undefined.
  // No screen reads them today: <app-pagination> works out the two ends itself, from
  // `page` and its own totalPages, which it derives from the total and the page size.
  // Keeping them here costs nothing at run time and documents the envelope in full.
  first: boolean;
  last: boolean;
  // How many rows this page really contains, which equals content.length. On the last page
  // it is smaller than `size`: with size = 20 and 507 rows, the last page carries 7.
  // Also declared for completeness and not read today - the "showing 1-20 of 507" line of
  // <app-pagination> is computed from the page number, the page size and totalElements.
  numberOfElements: number;
}
