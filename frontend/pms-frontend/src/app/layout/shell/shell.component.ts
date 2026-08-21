import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { ConfirmModalComponent } from '../../shared/confirm-modal/confirm-modal.component';
import { ToastContainerComponent } from '../../shared/toast-container/toast-container.component';
import { LayoutService } from '../../core/services/layout.service';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, SidebarComponent, ConfirmModalComponent, ToastContainerComponent],
  template: `
    <div class="app-layout" [class.sidebar-collapsed]="layout.sidebarCollapsed()">
      <app-sidebar></app-sidebar>
      <div class="main-content">
        <router-outlet></router-outlet>
      </div>
    </div>
    <app-confirm-modal></app-confirm-modal>
    <app-toast-container></app-toast-container>
  `
})
export class ShellComponent {
  readonly layout = inject(LayoutService);
}
