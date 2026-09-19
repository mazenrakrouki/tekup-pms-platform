import { Component, OnInit, inject, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DiService } from '../../core/services/di.service';
import { ProjectService } from '../../core/services/project.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { DevisInterneResponse, LigneDiRequest, LigneDiResponse, SectionDi, SECTION_DI_LABELS } from '../../core/models/di.model';
import { Project } from '../../core/models/project.model';

/**
 * WHAT THIS FILE IS
 * The single screen of the "Devis Interne" (DI = internal quote): the sheet where the
 * company writes, for one project, what it sold to the client and what that work really
 * costs it internally. It shows four summary cards, then one table per section, and it
 * lets the user add, edit and delete the lines of that table.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : the Angular router. The route carries the project id in the URL
 *                (/projects/:id/devis-interne), which ngOnInit() reads below.
 * What it calls: core/services/di.service.ts for every read and write of the quote,
 *                core/services/project.service.ts only to show the project code in the
 *                breadcrumb, and core/services/confirm.service.ts to ask "are you sure?"
 *                before a delete.
 * DiService then calls the backend at /api/projects/{id}/devis-interne. That URL shape is
 * what makes ADR-021 apply: the ProjectScopeInterceptor on the server checks BOTH the
 * MANAGE_DI permission AND that this user is really attached to this project. So this
 * component never decides who may see the page; it only draws what the server agreed to
 * return, and shows an error message when the server says no.
 *
 * WHY IT EXISTS (what breaks if you delete it)
 * Without this file the DI would exist only in the database and in the REST API: nobody in
 * the company could read the margin of a project or correct a wrong day rate without a
 * developer. It is the only user interface of feature F-AFF-13 §3.
 *
 * THE ONE RULE TO REMEMBER ON THIS SCREEN (F-AFF-13 §3, BUSINESS_ANALYSIS.md §16)
 * Every money figure shown here - montantTnd, coutFinal, margeNette, margePct and all the
 * totals - is COMPUTED BY THE SERVER when the quote is read. It is never stored in a
 * column, and this component never computes it either. That is why every write method
 * below (add, update, delete) takes the whole fresh quote back from the server response
 * and puts it straight into the di signal: it is the only honest way to refresh the
 * amounts. If this component computed a total in the browser, two users editing at the
 * same time would each see a different margin for the same project.
 * Second half of the same rule: the screen starts EMPTY. No line, no default rate, no
 * example value is pre-filled, because a wrong pre-filled cost would silently become the
 * company's real cost the day somebody saves the line without looking at it.
 */
// The comment must stay above the decorator: putting it between @Component and the class
// would separate the decorator from the class it decorates.
@Component({
  selector: 'app-devis-interne',
  // standalone: true means this component declares its own dependencies below and does not
  // belong to any NgModule. Why: the whole project is built this way, so the router can
  // lazy-load this screen on its own. Without it, Angular would refuse to compile the
  // component unless some module declared it, and the DI screen would have to be bundled
  // with everything else - loaded even for a user who has no right to see it.
  standalone: true,
  // imports lists what the inline template below is allowed to use. Each one is needed:
  // CommonModule  -> the `number` pipe used on every amount (e.g. `| number:'1.0-0'`).
  // FormsModule   -> [(ngModel)] on the input boxes of the edit row.
  // RouterLink    -> the two [routerLink] links back to the project.
  // TranslocoModule -> the `transloco` pipe used for the page title.
  // Why list them: a standalone component sees nothing by default. Forget FormsModule and
  // the build fails with "Can't bind to ngModel", so no cell could be typed into.
  imports: [CommonModule, FormsModule, RouterLink, TranslocoModule],
  // The template is written inline, inside backticks, instead of in a separate .html file.
  // Why: it is the convention of this frontend. Careful when editing it: inside these
  // backticks only <!-- --> comments are valid HTML comments.
  template: `
    <!--
      Top bar: breadcrumb on the left, "back to project" button on the right.
      The DI is always opened from one project, so the user must always be able to walk
      back to it. Without this bar he would have to use the browser back button.
    -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <!--
          project() is a signal, so reading it here makes Angular redraw this link on its
          own the moment the project arrives from the server.
          ?. and ?? handle the first moments of the page, when project() is still null:
          ?. stops the read instead of crashing, ?? then shows the word "Projet".
          Without them the template would throw "cannot read code of null" on the very
          first paint and the whole screen would stay blank.
        -->
        <a [routerLink]="['/projects', projectId]" style="color:var(--text-2);text-decoration:none;font-size:12px">
          {{ project()?.code ?? 'Projet' }}
        </a>
        <span class="bc-sep">›</span>
        <span class="bc-curr"><i class="bi bi-file-earmark-lock2 me-1"></i>Devis Interne</span>
      </div>
      <div class="tb-right">
        <a [routerLink]="['/projects', projectId]" class="btn btn-outline-secondary btn-sm">
          <i class="bi bi-arrow-left"></i>Retour
        </a>
      </div>
    </div>

    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'nav.internalQuote' | transloco }}</h1>
      </div>
      <!--
        @if (di(); as d) does two things at once: it waits until the quote has really
        arrived, and it gives that value the short name d for the whole block.
        Why the guard: di() is null until the HTTP answer comes back, and the cards below
        read d.totalVenduTnd directly. Without the @if, the first paint would crash.
        Why "as d": the alias reads the signal ONE time for the block. Writing di()!.xxx
        on forty lines would read the signal forty times and force the non-null with "!",
        which hides exactly the bug the @if is there to prevent.
        The @else at the end of the block shows a spinner during that wait.
      -->
      @if (di(); as d) {
        <!-- ── Totals: the four summary cards. Every number here comes computed from the
             server; the browser only formats it. ── -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <!--
                The quote can be sold in a foreign currency, so the same total is shown
                twice: in the sale currency, then converted to TND.
                The number:'1.0-0' pipe is the Angular number pipe. '1.0-0' reads as: at least
                1 digit before the dot, between 0 and 0 digits after it - so the amount is
                rounded to the unit and gets thousands separators.
                Why: raw JavaScript would print 1234567.8899999 and nobody can read that.
              -->
              <div class="small text-muted">Total vendu ({{ d.currency }})</div>
              <div class="fs-5 fw-bold text-primary">{{ d.totalVenduDevise | number:'1.0-0' }}</div>
              <div class="small text-muted">{{ d.totalVenduTnd | number:'1.0-0' }} TND</div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <!--
                Internal cost: what the project really costs the company. It is always in
                TND, because the internal day cost (TCC) is expressed in TND.
                JH = "jour-homme", one man-day. '1.0-1' keeps one decimal, because half
                days are common (7.5 JH) and rounding them away would lose real work.
              -->
              <div class="small text-muted">Coût total interne</div>
              <div class="fs-5 fw-bold text-warning">{{ d.totalCoutFinal | number:'1.0-0' }} TND</div>
              <div class="small text-muted">{{ d.totalQuantiteInterneJh | number:'1.0-1' }} JH internes</div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <!--
                Net margin = what was sold minus what it costs. This is the number the
                jury and the management look at first, so it is coloured:
                [class.text-success] adds the green class only while the expression is
                true, [class.text-danger] adds the red one. Angular removes the class by
                itself when the value changes.
                Why colour it: a loss printed in the same grey as a profit is read as a
                profit. A project at -40 000 TND must be impossible to miss.
              -->
              <div class="small text-muted">Marge nette vendue</div>
              <div class="fs-5 fw-bold" [class.text-success]="d.margeNette >= 0" [class.text-danger]="d.margeNette < 0">
                {{ d.margeNette | number:'1.0-0' }} TND
              </div>
              <!--
                margePct is a ratio, not a percentage: the server sends 0.18 for 18 %, so
                the template multiplies by 100 and adds the % sign itself.
                margePct is optional (it has no meaning when nothing was sold yet), so
                the ?? 0 replaces a missing value by 0. Without it the card would print
                "NaN %" and the colour test would be false on both sides, leaving the
                figure grey.
              -->
              <div class="small fw-semibold" [class.text-success]="(d.margePct ?? 0) >= 0" [class.text-danger]="(d.margePct ?? 0) < 0">
                {{ ((d.margePct ?? 0) * 100) | number:'1.1-2' }} %
              </div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <!--
                Sold workload against internal workload, side by side. These two numbers
                explain the margin above: selling 100 days and planning 130 internal days
                is how a project loses money, and this card makes that visible without
                opening a single table row.
              -->
              <div class="small text-muted">Charge vendue</div>
              <div class="fs-5 fw-bold">{{ d.totalChargeVendueJh | number:'1.0-1' }} JH</div>
              <div class="small text-muted">vs {{ d.totalQuantiteInterneJh | number:'1.0-1' }} JH internes</div>
            </div></div>
          </div>
        </div>

        <!-- ── Sections: one card and one table for each of the three parts of the quote ── -->
        <!--
          @for walks the fixed list "sections" declared in the class (HONORAIRES, FRAIS,
          AUTRES_FRAIS). "track section" tells Angular how to recognise a row it has
          already drawn - here the string itself is the identity, because the three values
          are unique and never change.
          Why track at all: Angular requires it in @for. With a bad track key Angular
          would destroy and rebuild all three tables on every refresh, and a cell the user
          was typing into would lose focus in the middle of a word.
        -->
        @for (section of sections; track section) {
          <div class="card mb-4">
            <div class="card-header justify-content-between">
              <span class="fw-semibold">{{ sectionLabel(section) }}</span>
              <button class="btn btn-sm btn-outline-primary" (click)="startAdd(section)">
                <i class="bi bi-plus-lg me-1"></i>Ajouter une ligne
              </button>
            </div>
            <div class="table-responsive">
              <table class="table table-sm table-hover mb-0 align-middle small">
                <thead>
                  <tr>
                    <th>Profil / libellé</th>
                    <th>Ressource retenue</th>
                    <th class="text-end">JH vendus</th>
                    <th class="text-end">PU ({{ d.currency }})</th>
                    <th class="text-end">Vendu TND</th>
                    <th class="text-end">JH internes</th>
                    <th class="text-end">TCC (TND/j)</th>
                    <!--
                      The "Taux %" column exists only in the AUTRES_FRAIS section, because
                      only that section holds lines computed as a rate (a tax, a
                      provision) instead of a day count times a day price.
                      Why hide it elsewhere: an always-empty column in the Honoraires
                      table would invite somebody to fill it, and the server would ignore
                      the value - the user would believe he had saved a rate that does
                      nothing. The same @if is repeated on every row below so that the
                      body always has exactly as many cells as the head.
                    -->
                    @if (section === 'AUTRES_FRAIS') { <th class="text-end">Taux %</th> }
                    <th class="text-end">Coût final</th>
                    <th class="text-end">Marge TND</th>
                    <th class="text-end">Marge %</th>
                    <th style="width:90px"></th>
                  </tr>
                </thead>
                <tbody>
                  <!--
                    bySection(section) keeps only the lines of this section out of the one
                    flat list the server returned. "track l.id" uses the database id: that
                    id follows the line even when the list is re-sorted or reloaded, so
                    Angular moves the existing row instead of building a new one.
                  -->
                  @for (l of bySection(section); track l.id) {
                    <!--
                      Each line is drawn in one of two shapes: the edit form when this is
                      the line being edited, the read-only row otherwise. editingId() holds
                      at most one id, which is what stops two rows from being open at the
                      same time - two open rows would share the single "form" object below
                      and the second save would write the first row's values.
                    -->
                    @if (editingId() === l.id) {
                      <!--
                        The edit row. Every box is bound with [(ngModel)]="form.xxx":
                        two-way binding, so typing writes straight into the "form" object
                        that save() will send. Only the fields the user is allowed to
                        enter are here - the computed ones are shown as text, never as an
                        input, so nobody can type a margin by hand.
                        min="0" on the number boxes blocks a negative day count or price.
                        Without it a user could save -10 JH and the total workload of the
                        project would silently shrink. The server checks this again: the
                        browser check is only there to warn early, it is not the guard.
                      -->
                      <tr class="table-active">
                        <td><input class="form-control form-control-sm" [(ngModel)]="form.profilContractuel" placeholder="ex. PC-1 / TSR 5 %"></td>
                        <td><input class="form-control form-control-sm" [(ngModel)]="form.ressourceRetenue"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.chargeVendueJh" min="0"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.prixVenteUnitaire" min="0"></td>
                        <td class="text-end text-muted">—</td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.quantiteInterneJh" min="0"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.coutUnitaireTcc" min="0"></td>
                        @if (section === 'AUTRES_FRAIS') {
                          <!--
                            The rate is typed as a ratio, not as a percentage: min 0,
                            max 1, step 0.01, and the placeholder shows 0.05 to say "five
                            percent is written 0.05". The read-only row above multiplies
                            it by 100 for display.
                            Why keep the ratio in the field: the server stores and
                            computes with the ratio. If the box accepted 5 here, the line
                            would be saved as 500 % and one tax line would swallow the
                            whole margin of the project.
                          -->
                          <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.tauxPourcentage" min="0" max="1" step="0.01" placeholder="0.05"></td>
                        }
                        <!--
                          The three computed cells (cost, margin, margin %) are replaced
                          while editing by one grey cell spanning them, saying the value
                          will be computed after saving.
                          Why: those figures belong to the server. Showing a stale number
                          next to the new values the user is typing would look like a live
                          preview and would be wrong.
                        -->
                        <td class="text-end text-muted" colspan="3">calculé après enregistrement</td>
                        <td class="text-end text-nowrap">
                          <!--
                            title= gives the mouse tooltip, aria-label= gives the name a
                            screen reader announces. Both are needed because the button
                            holds only an icon: without aria-label a blind user hears
                            "button" and cannot tell save from cancel.
                          -->
                          <button class="btn btn-sm btn-success me-1" (click)="save()" title="Enregistrer" aria-label="Enregistrer la ligne"><i class="bi bi-check-lg"></i></button>
                          <button class="btn btn-sm btn-outline-secondary" (click)="cancelEdit()" title="Annuler" aria-label="Annuler l'édition"><i class="bi bi-x-lg"></i></button>
                        </td>
                      </tr>
                    } @else {
                      <!--
                        The read-only row.
                        Two different guards are used here on purpose:
                        - text fields use the "or" operator with a dash, which also
                          catches the empty string, because an empty text cell looks like
                          a broken page;
                        - number fields test "!= null" first, which lets the value 0
                          through. Using the "or" operator on a number would print a dash
                          instead of 0, and a line really sold at 0 TND would look like a
                          line nobody filled in.
                        "!= null" with one equals sign is deliberate: it is true for null AND for
                        undefined, and the server leaves optional fields undefined.
                      -->
                      <tr>
                        <td>{{ l.profilContractuel || '—' }}</td>
                        <td>{{ l.ressourceRetenue || '—' }}</td>
                        <td class="text-end">{{ l.chargeVendueJh != null ? (l.chargeVendueJh | number:'1.0-1') : '—' }}</td>
                        <td class="text-end">{{ l.prixVenteUnitaire != null ? (l.prixVenteUnitaire | number:'1.0-0') : '—' }}</td>
                        <td class="text-end">{{ l.montantTnd | number:'1.0-0' }}</td>
                        <td class="text-end">{{ l.quantiteInterneJh != null ? (l.quantiteInterneJh | number:'1.0-1') : '—' }}</td>
                        <td class="text-end">{{ l.coutUnitaireTcc != null ? (l.coutUnitaireTcc | number:'1.0-2') : '—' }}</td>
                        @if (section === 'AUTRES_FRAIS') {
                          <td class="text-end">{{ l.tauxPourcentage != null ? ((l.tauxPourcentage * 100) | number:'1.0-1') + ' %' : '—' }}</td>
                        }
                        <!--
                          coutFinal, margeNette and margePct are the three values the
                          server derives when it reads the quote; they exist in the
                          response object but in no database column (F-AFF-13 §3).
                          They are printed raw here, with no arithmetic in the template,
                          so the screen can never disagree with the server.
                        -->
                        <td class="text-end">{{ l.coutFinal | number:'1.0-0' }}</td>
                        <td class="text-end" [class.text-success]="(l.margeNette ?? 0) >= 0" [class.text-danger]="(l.margeNette ?? 0) < 0">
                          {{ l.margeNette | number:'1.0-0' }}
                        </td>
                        <td class="text-end">{{ l.margePct != null ? ((l.margePct * 100) | number:'1.1-1') + ' %' : '—' }}</td>
                        <td class="text-end text-nowrap">
                          <button class="btn btn-sm btn-outline-primary me-1" (click)="startEdit(l)" title="Modifier" aria-label="Modifier la ligne"><i class="bi bi-pencil"></i></button>
                          <!--
                            remove(l) does not delete straight away: it first opens the
                            confirmation modal (see the method below). Deleting a quote
                            line changes the margin of the project, and there is no undo.
                          -->
                          <button class="btn btn-sm btn-outline-danger" (click)="remove(l)" title="Supprimer" aria-label="Supprimer la ligne"><i class="bi bi-trash"></i></button>
                        </td>
                      </tr>
                    }
                  }
                  <!--
                    The new-line row. It is the same form as the edit row above, but it
                    sits after the last existing line of the section, and it only appears
                    in the section the user clicked "Ajouter une ligne" on - that is what
                    addingSection() holds.
                    Why compare to "section" instead of using a plain boolean: the three
                    tables share this template. A boolean would open an empty form in all
                    three sections at once, and the user would not know which one he is
                    filling.
                  -->
                  @if (addingSection() === section) {
                    <tr class="table-active">
                      <td><input class="form-control form-control-sm" [(ngModel)]="form.profilContractuel" placeholder="ex. PC-1 / TSR 5 %"></td>
                      <td><input class="form-control form-control-sm" [(ngModel)]="form.ressourceRetenue"></td>
                      <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.chargeVendueJh" min="0"></td>
                      <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.prixVenteUnitaire" min="0"></td>
                      <td class="text-end text-muted">—</td>
                      <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.quantiteInterneJh" min="0"></td>
                      <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.coutUnitaireTcc" min="0"></td>
                      @if (section === 'AUTRES_FRAIS') {
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.tauxPourcentage" min="0" max="1" step="0.01" placeholder="0.05"></td>
                      }
                      <td class="text-end text-muted" colspan="3">calculé après enregistrement</td>
                      <td class="text-end text-nowrap">
                        <button class="btn btn-sm btn-success me-1" (click)="save()"><i class="bi bi-check-lg"></i></button>
                        <button class="btn btn-sm btn-outline-secondary" (click)="cancelEdit()"><i class="bi bi-x-lg"></i></button>
                      </td>
                    </tr>
                  }
                  <!--
                    Empty state. The second test matters: while the new-line form is open
                    the section is still empty, and without "addingSection() !== section"
                    the message "Aucune ligne" would sit right under the form the user is
                    typing into, as if his work had not been taken into account.
                    This empty state is the normal first sight of the screen: a DI starts
                    with no line at all (F-AFF-13 §3, no seeded data).
                  -->
                  @if (bySection(section).length === 0 && addingSection() !== section) {
                    <tr><td colspan="12">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-list-ul"></i></div>
                        <div class="es-title">Aucune ligne</div>
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
      } @else {
        <!--
          Shown while di() is still null: either the request is flying, or it failed.
          Note that when the server refuses the quote (missing MANAGE_DI permission, or
          the project scope check of ADR-021), di() stays null, so the user sees this
          spinner plus the red message below. The screen never shows half a quote.
        -->
        <div class="text-center py-5"><span class="spinner-border text-primary"></span></div>
      }

      <!--
        One single place for errors, fed by the error signal. It is outside the @if above
        on purpose: a save can fail while the quote is already displayed, and the message
        must still be visible then.
      -->
      @if (error()) {
        <div class="alert alert-danger py-2 small">{{ error() }}</div>
      }
    </div>
  `
})
/**
 * The controller half of the screen. It holds no business rule of its own: it keeps the
 * small amount of state a table with an inline editor needs (which line is open, what is
 * being typed), and it forwards every real operation to DiService.
 *
 * Why it is written this way rather than with a reactive form and a full store:
 * the quote is a short list edited one line at a time, and the server always answers a
 * write with the whole recomputed quote. So one plain object for the row being typed, and
 * one signal holding the last quote the server sent, is enough - and it keeps the single
 * source of truth on the server, which is what the DI rules require.
 */
export class DevisInterneComponent implements OnInit {
  // inject() is the function form of dependency injection: Angular gives back the single
  // application-wide instance of each service. It is used instead of a constructor because
  // these fields are readonly and never passed in from outside.
  // diSvc is the only door to the quote: every read and write of DI data goes through it.
  private readonly diSvc = inject(DiService);
  // Used once, only to get the project code for the breadcrumb. The quote itself never
  // comes from here.
  private readonly projectSvc = inject(ProjectService);
  // The shared "are you sure?" modal, used before a delete.
  private readonly confirm = inject(ConfirmService);
  // Gives access to the URL, which is where the project id lives.
  private readonly route = inject(ActivatedRoute);

  // The project this quote belongs to. It is a plain number, not a signal, because it is
  // read once in ngOnInit and never changes while the screen is open.
  projectId = 0;
  // The three sections of the quote, in the order the Excel method puts them. The list is
  // fixed in code, not loaded from the server, because the structure of a DI is part of
  // the method (F-AFF-13 §3) and not data a user may invent.
  // `readonly` stops the array reference from being replaced by mistake.
  readonly sections: SectionDi[] = ['HONORAIRES', 'FRAIS', 'AUTRES_FRAIS'];

  // A signal is a value Angular watches: setting it redraws every part of the template
  // that reads it, with no manual change detection call.
  // di holds the last complete quote the server sent, totals included. It starts null,
  // which is what the template uses to tell "not loaded yet" from "loaded and empty".
  // The <...| null> type forces the template to deal with that null case.
  di = signal<DevisInterneResponse | null>(null);
  // Only for the breadcrumb label. Also null until the project arrives.
  project = signal<Project | null>(null);
  // Id of the line currently open in edit mode, or null when none is. One value only,
  // which is what guarantees a single open editor - see the template.
  editingId = signal<number | null>(null);
  // The section whose "new line" form is open, or null. Kept apart from editingId because
  // a new line has no id yet, and because the two must never be open together.
  addingSection = signal<SectionDi | null>(null);
  // The message shown in the red banner. Empty string means "no error", which is why the
  // template can simply test @if (error()).
  error = signal('');

  // The object the input boxes write into through [(ngModel)]. It is reused for both
  // "add" and "edit"; startAdd() and startEdit() fill it, save() sends it.
  // It is a plain object, not a signal, because ngModel writes into it directly and no
  // other part of the screen has to react to each keystroke.
  // Its type is LigneDiRequest, never LigneDiResponse: the computed fields (coutFinal,
  // margeNette, margePct) are simply not in that type, so the compiler makes it impossible
  // to send a margin to the server by accident.
  // `section` is the only required field, so it is given a starting value here.
  form: LigneDiRequest = { section: 'HONORAIRES' };

  /**
   * Runs once, just after Angular has created the component. It reads the project id from
   * the URL, then fires the two requests the screen needs.
   * Why here and not in the constructor: in the constructor the route data is not
   * guaranteed to be bound yet, and starting HTTP calls from a constructor makes the
   * component impossible to create in a test without a network stub.
   */
  ngOnInit(): void {
    // snapshot reads the URL once, as it is now. It is enough here because leaving this
    // project means leaving the screen, so the id can never change under our feet. A
    // subscription to paramMap would be needed only if the router reused this component
    // for another id.
    // The leading "+" turns the string "42" from the URL into the number 42. Without it
    // projectId would be a string and the request URL would still work by luck, but any
    // === comparison against a numeric id would be false.
    // The "!" says "this parameter is certainly there": the route is declared with :id,
    // so the page cannot be reached without one.
    this.projectId = +this.route.snapshot.paramMap.get('id')!;
    // Only for the breadcrumb. It is fired separately and its failure is not fatal: if the
    // project name never arrives the breadcrumb falls back to the word "Projet" and the
    // quote below is still usable.
    // .subscribe() is what actually sends the request - an Angular HttpClient observable
    // does nothing until someone subscribes to it.
    this.projectSvc.get(this.projectId).subscribe(p => this.project.set(p));
    this.load();
  }

  /**
   * Fetches the whole quote (lines + totals) and puts it into the di signal.
   * Kept as its own public method, and not inlined in ngOnInit, so the screen can be
   * reloaded from scratch after an error.
   */
  load(): void {
    this.diSvc.get(this.projectId).subscribe({
      // The server sends the complete quote, already computed. It is stored as it is.
      next: d => this.di.set(d),
      // Any failure - no MANAGE_DI permission, project scope refused by the
      // ProjectScopeInterceptor (ADR-021), or the server being down - ends here.
      // The message stays deliberately vague and says nothing about permissions: telling
      // a user "you are not allowed" on a page of internal costs already tells him the
      // quote exists and has a value.
      error: () => this.error.set('Impossible de charger le Devis Interne.')
    });
  }

  /**
   * Gives back the lines of one section, out of the single flat list the server sent.
   * Why filter in the browser instead of asking the server three times: the quote is one
   * document, it is read in one request, and splitting it into three requests would let
   * the three tables show three different versions of the same quote.
   */
  bySection(section: SectionDi): LigneDiResponse[] {
    // ?. stops the read when di() is still null, ?? then gives an empty array, so .filter
    // always has something to work on. Without the two guards this method would throw
    // during the first paint, before the quote has arrived.
    return (this.di()?.lignes ?? []).filter(l => l.section === section);
  }

  /**
   * Turns a section code into the label shown in the card header, for example
   * 'AUTRES_FRAIS' into 'Autres frais (taxes, provisions)'.
   * The map lives in the model file so that the code and its label stay together; the
   * Record<SectionDi, string> type there forces a label to exist for every section, so a
   * new section can never reach the screen as a raw upper-case code.
   */
  sectionLabel(s: SectionDi): string { return SECTION_DI_LABELS[s]; }

  /**
   * Opens the empty "new line" form at the bottom of one section.
   * Note the order: the edit mode is closed FIRST. The two modes share the single 'form'
   * object, so leaving an edit open while starting an add would let the add form inherit
   * the values of the line being edited, and save() would then create a copy of it.
   */
  startAdd(section: SectionDi): void {
    this.editingId.set(null);
    this.addingSection.set(section);
    // A brand new form holding only the section. Nothing else is pre-filled, on purpose:
    // a suggested day rate would become the real cost of the project the first time
    // somebody saves the line without reading it (F-AFF-13 §3).
    // `{ section }` is the shorthand for `{ section: section }`.
    this.form = { section };
  }

  /**
   * Opens one existing line in edit mode and loads its values into the form.
   * Closing the add mode first, for the same shared-form reason as above.
   */
  startEdit(l: LigneDiResponse): void {
    this.addingSection.set(null);
    this.editingId.set(l.id);
    // The fields are copied one by one instead of writing `this.form = { ...l }`.
    // Why: `l` is a LigneDiResponse and carries the server-computed amounts (montantTnd,
    // coutFinal, margeNette, margePct) and the id. A spread would copy them into the
    // object that save() sends back, so the browser would be posting margins to the
    // server. Listing the fields keeps the request to exactly what a user may change.
    this.form = {
      section: l.section, ordre: l.ordre,
      profilContractuel: l.profilContractuel, ressourceProposee: l.ressourceProposee,
      ressourceRetenue: l.ressourceRetenue, unite: l.unite,
      chargeVendueJh: l.chargeVendueJh, prixVenteUnitaire: l.prixVenteUnitaire,
      quantiteInterneJh: l.quantiteInterneJh, coutUnitaireTcc: l.coutUnitaireTcc,
      fraisDivers: l.fraisDivers, fraisGeneraux: l.fraisGeneraux,
      coutImpots: l.coutImpots, tauxPourcentage: l.tauxPourcentage
    };
  }

  /**
   * Closes whichever form is open. It clears both signals without asking which mode was
   * active, because clearing an already-null signal costs nothing and one method is
   * harder to get wrong than two.
   * It is used by the Cancel button and by save() once the server has answered.
   * 'form' is deliberately left as it is: nothing reads it while both signals are null,
   * and the next startAdd/startEdit replaces it completely.
   */
  cancelEdit(): void {
    this.editingId.set(null);
    this.addingSection.set(null);
  }

  /**
   * Sends the open form to the server: an update when a line was being edited, a creation
   * otherwise. One single method for both cases, because the two calls differ only by the
   * HTTP verb and by the presence of the line id - everything after the answer is the same.
   */
  save(): void {
    // Clear any older error first, so a message from a previous failed attempt does not
    // stay on screen next to a save that has just succeeded.
    this.error.set('');
    // The success handler, written once and reused by both branches below.
    // It stores the WHOLE quote the server sends back, not just the saved line: adding a
    // line changes the section totals, the global totals and the margin, and all of those
    // are computed server-side. Patching only the edited line in the local list would
    // leave the four cards at the top showing the totals from before the change.
    const done = (d: DevisInterneResponse) => { this.di.set(d); this.cancelEdit(); };
    // The failure handler. Note that the form stays open and still holds what the user
    // typed, so he can fix a value instead of typing the whole line again.
    const fail = () => this.error.set('Enregistrement impossible — vérifier les valeurs saisies.');

    const id = this.editingId();
    // `!= null` (one "=") is true for both null and undefined, and - unlike a plain
    // truthiness test - it lets the value 0 through. Ids start at 1 here, but the habit is
    // what stops this kind of test from silently treating a real 0 as "nothing".
    if (id != null) {
      this.diSvc.updateLigne(this.projectId, id, this.form).subscribe({ next: done, error: fail });
    } else {
      this.diSvc.addLigne(this.projectId, this.form).subscribe({ next: done, error: fail });
    }
  }

  /**
   * Deletes one line, after the user has confirmed.
   * It is 'async' because confirm.ask() gives back a Promise that settles only when the
   * user clicks a button in the modal - 'await' lets the code read top to bottom instead
   * of hiding the delete inside a callback.
   */
  async remove(l: LigneDiResponse): Promise<void> {
    // The question repeats the label of the line. Why: the buttons of all the rows look
    // the same, and a user who clicked one row lower would otherwise delete a line he
    // never meant to touch - and the margin of the project would change with it.
    const ok = await this.confirm.ask(
      `Supprimer la ligne « ${l.profilContractuel || 'sans libellé'} » du Devis Interne ?`,
      'Supprimer la ligne');
    // The user said no, or pressed Escape: leave without calling the server at all.
    if (!ok) return;
    // Like the two write calls in save(), the delete answers with the whole recomputed
    // quote, so the totals at the top of the page fall back in step with the table.
    this.diSvc.deleteLigne(this.projectId, l.id).subscribe(d => this.di.set(d));
  }
}
