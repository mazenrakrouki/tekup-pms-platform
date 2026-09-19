/*
 * FILE: agile.model.ts
 *
 * WHAT THIS FILE IS
 * The shapes used by the Agile module on the browser side: a sprint, a backlog item, and
 * the two bodies sent when one of them is created or changed. Almost everything here is
 * TYPE information only; the only things that really exist while the program runs are the
 * three small constant arrays at the top.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot SprintController / BacklogItemController answer with the Java records
 *   SprintResponse and BacklogItemResponse
 *     -> Jackson turns them into JSON
 *     -> core/services/agile.service.ts declares its Observables with the shapes below
 *     -> features/agile/agile.component.ts (the Scrum board screen) reads them, and sends
 *        SprintPayload / BacklogItemPayload back when the user saves.
 *
 * WHY IT EXISTS
 * A "model" file is the written contract between the Angular screens and the Java API.
 * TypeScript types are erased when the project is compiled, so this file adds zero bytes
 * to what the browser downloads; what it adds is checking while the project is being
 * built. Delete it and the board component would have to type its data as 'any', and 'any'
 * switches the compiler off: writing 'item.titre' instead of 'item.title' would compile
 * happily, arrive as undefined at run time, and the board would show a column of empty
 * cards with no error anywhere.
 *
 * WHY THE FIELD NAMES ARE NOT CHOSEN HERE
 * They are copied one for one from the Java records. Renaming 'estimateDays' into
 * something that reads better in French would make this interface stop describing the real
 * JSON, and every read of that field would give undefined.
 *
 * SECURITY REMINDER
 * Nothing in this file protects anything; it only describes data. The refusal happens on
 * the server: @PreAuthorize("hasAuthority('VIEW_AGILE')") for reading and
 * @PreAuthorize("hasAuthority('MANAGE_AGILE')") for writing, placed on the SERVICE
 * methods, plus ProjectScopeInterceptor, which also checks that this user may touch THAT
 * project because the URLs look like /api/projects/{id}/** (ADR-021).
 */

// A "union type" lists the only texts a field may hold. SprintStatus is therefore not
// "any string", it is exactly one of these three.
// Why this rather than `status: string`: the three values must stay the same as the Java
// enum SprintStatus. With a plain string a typo such as 'ACTIVED' would compile, travel to
// the server, and come back as a 400 error the user cannot understand. Here the mistake is
// refused in the editor, before the file is even saved.
export type SprintStatus = 'PLANNED' | 'ACTIVE' | 'CLOSED';
// The three states of one backlog item. They are also the three columns of the board, in
// this order, so the order below is not decorative.
export type BacklogItemStatus = 'TODO' | 'IN_PROGRESS' | 'DONE';
// How urgent one backlog item is. Same idea: the four values match the Java enum
// BacklogPriority, so the coloured badge on a card can never meet a value it does not know
// and fall back to a blank label.
export type BacklogPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

// The three arrays below are real VALUES (they still exist while the program runs), unlike
// the three types above. They are here so the screen can build its dropdowns and its
// columns with a loop, instead of repeating the same three or four options in the HTML.
// The `: SprintStatus[]` annotation is what makes them safe: adding 'PAUSED' to the array
// without adding it to the union type above is refused by the compiler. Without the
// annotation the array would simply be string[], the compiler would stay silent, and the
// board would try to draw a column the server never sends anything for.
export const SPRINT_STATUSES: SprintStatus[] = ['PLANNED', 'ACTIVE', 'CLOSED'];
// Read by agile.component.ts as `columns`: the board draws one column per entry, in this
// order. Reversing this array would reverse the board.
export const BACKLOG_STATUSES: BacklogItemStatus[] = ['TODO', 'IN_PROGRESS', 'DONE'];
// Read by agile.component.ts as `priorities`, to fill the priority dropdown of the item
// form. Listed from the least to the most urgent, which is the order a user expects.
export const BACKLOG_PRIORITIES: BacklogPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

/**
 * One sprint as the server SENDS it (mirror of the Java record SprintResponse).
 * A sprint is a fixed period of work, usually two or three weeks, holding a set of
 * backlog items.
 *
 * Why this is a separate shape from SprintPayload below: what comes back is not what is
 * sent. The answer carries the id and the project, which the browser must never choose.
 * With one single shape for both directions, a screen could put an id or a projectId in
 * the body and try to move a sprint into another project.
 */
export interface Sprint {
  id: number;
  // The project this sprint belongs to. On the server it comes from the URL, not from the
  // body, and here it lets the screen check it is showing the board of the right project.
  projectId: number;
  // The readable project code, for example "S2I-2026-014". The server sends it next to the
  // id so the screen can print something a person recognises, without a second request
  // just to read the project name.
  projectCode: string;
  name: string;
  // "?" means the field may be missing from the JSON; "| null" means it may be there and
  // empty. Both are possible because Jackson writes null for an empty Java field.
  // Why both and not only "?": with only "?" the compiler would refuse `goal = null`, and
  // the screen could not clear a goal that had been typed by mistake.
  goal?: string | null;
  // Dates travel as plain text in the ISO form "2026-07-14". JSON has no date type, so a
  // Java LocalDate always arrives as a string. Typing it as Date would be a lie: the value
  // really is a string, and calling startDate.getFullYear() on it would crash the board.
  startDate?: string | null;
  endDate?: string | null;
  status: SprintStatus;
}

/**
 * One backlog item as the server SENDS it (mirror of the Java record
 * BacklogItemResponse). A backlog item is one unit of work: a user story, a task or a bug.
 *
 * Note the pairs id + name (sprintId / sprintName, assigneeId / assigneeName). The id is
 * what the screen sends back when it saves; the name is what it prints. With the ids
 * alone, the board would have to fire one extra request per card just to show who is in
 * charge of it.
 */
export interface BacklogItem {
  id: number;
  projectId: number;
  projectCode: string;
  /**
   * null = product backlog: the item is not committed to any sprint yet.
   * This is why the field can be null and is not simply left out: the board must tell
   * "not planned" apart from "planned", and it draws the unplanned items in the backlog
   * panel instead of inside a sprint column.
   */
  sprintId?: number | null;
  sprintName?: string | null;
  title: string;
  description?: string | null;
  priority: BacklogPriority;
  /** Estimate in man-days (JH, "jours-homme"), so 0.5 means half a day of work. */
  estimateDays?: number | null;
  status: BacklogItemStatus;
  /** null = nobody has taken the item yet; the card is then drawn with no name on it. */
  assigneeId?: number | null;
  assigneeName?: string | null;
}

/**
 * The body SENT when a sprint is created or updated (mirror of the Java record
 * SprintRequest).
 *
 * Why it carries no id and no projectId: both live in the URL
 * (/api/projects/{projectId}/sprints/{id}), which is exactly where ProjectScopeInterceptor
 * reads the project id to apply ADR-021. If they sat in the body instead, a caller could
 * keep the URL of his own project and put someone else's id in the JSON.
 */
export interface SprintPayload {
  name: string;
  goal?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  status: SprintStatus;
}

/**
 * The body SENT when a backlog item is created or updated (mirror of the Java record
 * BacklogItemRequest).
 *
 * sprintId is how an item moves between the backlog and a sprint: setting it attaches the
 * item, setting it back to null returns it to the product backlog. There is no separate
 * "detach" call, so one single save can move a card and change its status at the same
 * time - which is what the board needs when a card is dragged from one column to another.
 */
export interface BacklogItemPayload {
  title: string;
  description?: string | null;
  priority: BacklogPriority;
  estimateDays?: number | null;
  status: BacklogItemStatus;
  sprintId?: number | null;
  assigneeId?: number | null;
}
