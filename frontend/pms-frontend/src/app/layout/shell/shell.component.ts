import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { ConfirmModalComponent } from '../../shared/confirm-modal/confirm-modal.component';
import { ToastContainerComponent } from '../../shared/toast-container/toast-container.component';
import { LayoutService } from '../../core/services/layout.service';

/**
 * The post-login app frame: sidebar, router outlet, plus the always-on confirm dialog and
 * toasts. Lives above <router-outlet> so it survives navigation instead of being rebuilt.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, SidebarComponent, ConfirmModalComponent, ToastContainerComponent],
  template: `
    <!-- Class (not a sidebar style) so the main area can widen too when the menu folds. -->
    <div class="app-layout" [class.sidebar-collapsed]="layout.sidebarCollapsed()">
      <app-sidebar></app-sidebar>
      <div class="main-content">
        <router-outlet></router-outlet>
      </div>
    </div>
    <!-- Outside .app-layout so these overlays aren't clipped by its flex/stacking context. -->
    <app-confirm-modal></app-confirm-modal>
    <app-toast-container></app-toast-container>
  `
})
export class ShellComponent {
  // public: the template reads this directly; private would fail strict template type-check.
  readonly layout = inject(LayoutService);
}