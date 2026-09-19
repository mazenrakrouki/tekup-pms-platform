import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { ConfirmModalComponent } from '../../shared/confirm-modal/confirm-modal.component';
import { ToastContainerComponent } from '../../shared/toast-container/toast-container.component';
import { LayoutService } from '../../core/services/layout.service';

/**
 * WHAT THIS FILE IS
 * The frame of the application once the user is logged in: left side menu, main area where
 * every page is drawn, plus the two pieces that must always be on screen (the confirm
 * dialog and the toast messages).
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : the router. The protected routes are declared as children of a route whose
 *                component is this ShellComponent, so the router builds this frame once and
 *                then only swaps what is inside <router-outlet>.
 * What it calls: LayoutService (to know if the menu is folded), SidebarComponent,
 *                ConfirmModalComponent, ToastContainerComponent, and the router outlet.
 * It holds no data of its own and calls no HTTP.
 *
 * WHY IT EXISTS
 * Without this file every page would have to draw its own menu, its own confirm dialog and
 * its own toast area. Two problems follow. First, duplication: adding a menu entry would
 * mean editing every page. Second, and worse, the menu would be destroyed and rebuilt at
 * every navigation, so it would lose its state (which group is open, the fold) and it would
 * flicker. Here the frame lives above the outlet, so it survives navigation.
 */
@Component({
  selector: 'app-shell',
  // standalone: true means this component declares its own dependencies in `imports` below
  // instead of belonging to an NgModule. Why: the whole project is standalone; a component
  // that forgot this flag could not be imported by the others and the build would fail.
  standalone: true,
  // Each of these must be listed here or its tag in the template is not recognised.
  // Example of what goes wrong: remove RouterOutlet and <router-outlet> becomes an unknown
  // element, so no page is ever drawn inside the frame.
  imports: [RouterOutlet, SidebarComponent, ConfirmModalComponent, ToastContainerComponent],
  template: `
    <!-- [class.sidebar-collapsed] adds that CSS class only while the signal is true. -->
    <!-- Why a class on the wrapper and not a style on the sidebar: the main area must also -->
    <!-- get wider when the menu folds, and CSS can only reach it from a common parent. -->
    <!-- Without it the menu would shrink and leave an empty band next to the content. -->
    <!-- layout.sidebarCollapsed() is read as a function call: this is a signal, and calling -->
    <!-- it here is what tells Angular to redraw this div when the value changes. -->
    <div class="app-layout" [class.sidebar-collapsed]="layout.sidebarCollapsed()">
      <app-sidebar></app-sidebar>
      <div class="main-content">
        <!-- The router replaces what is here at every navigation. Everything written -->
        <!-- outside this outlet stays alive and is not rebuilt. -->
        <router-outlet></router-outlet>
      </div>
    </div>
    <!-- These two are placed OUTSIDE .app-layout on purpose. They are overlays: a dialog -->
    <!-- and the toast messages. Inside .app-layout they would inherit its flex layout and -->
    <!-- its stacking context, so the dialog could be cut by the main area or appear behind -->
    <!-- the sidebar. Both are empty until a service asks them to show something. -->
    <app-confirm-modal></app-confirm-modal>
    <app-toast-container></app-toast-container>
  `
})
export class ShellComponent {
  // inject() is the same dependency injection as a constructor parameter, written as a
  // field. LayoutService is provided in 'root', so this is the one shared instance: the
  // sidebar writes the fold state into it and the template above reads it back.
  // public (not private) because the template uses it; a private field would compile in the
  // IDE but fail the strict template type check of the production build.
  readonly layout = inject(LayoutService);
}