import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class LayoutService {
  readonly sidebarCollapsed = signal(false);
  toggle(): void { this.sidebarCollapsed.update(v => !v); }
}
