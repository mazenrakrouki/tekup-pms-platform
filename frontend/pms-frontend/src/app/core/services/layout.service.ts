import { Injectable, signal } from '@angular/core';

// Shared "is the sidebar collapsed" flag. Lives here (not in the sidebar or shell component)
// because both siblings need it and neither is the other's parent. Not persisted to
// localStorage on purpose: unlike ThemeService, this is a momentary UI gesture, not a preference.
@Injectable({ providedIn: 'root' })
export class LayoutService {
  // Signal so both the sidebar and shell components redraw automatically when it changes.
  readonly sidebarCollapsed = signal(false);

  // update() (read+write in one step) avoids a race where a fast double click could undo itself.
  toggle(): void { this.sidebarCollapsed.update(v => !v); }
}
