import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Resource } from '../../core/models/user.model';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-resources',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="topbar">
      <h5 class="mb-0 fw-semibold"><i class="bi bi-people me-2"></i>Ressources humaines</h5>
    </div>
    <div class="p-4">
      <div class="card">
        <div class="card-header bg-white fw-semibold py-3">
          Tarifs journaliers des collaborateurs
        </div>
        <div class="table-responsive">
          <table class="table table-hover mb-0 align-middle">
            <thead class="table-light">
              <tr>
                <th>Collaborateur</th>
                <th class="text-end">Tarif journalier (TND)</th>
                <th class="text-end">Taux TCC</th>
                <th class="text-end">Coût annuel chargé</th>
                <th>Période</th>
              </tr>
            </thead>
            <tbody>
              @for (r of resources(); track r.id) {
                <tr>
                  <td class="fw-semibold">{{ r.userFullName }}</td>
                  <td class="text-end">{{ r.dailyRate | number:'1.2-2' }}</td>
                  <td class="text-end">{{ r.tccRate | number:'1.2-2' }}</td>
                  <td class="text-end">{{ r.annualCost ? (r.annualCost | number:'1.0-0') : '—' }}</td>
                  <td class="text-muted small">
                    {{ r.staffingStart ?? '—' }}
                    @if (r.staffingEnd) { → {{ r.staffingEnd }} }
                    @else { → en cours }
                  </td>
                </tr>
              }
              @empty {
                <tr><td colspan="5" class="text-center py-4 text-muted">Aucune ressource</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `
})
export class ResourcesComponent implements OnInit {
  private readonly http = inject(HttpClient);
  resources = signal<Resource[]>([]);

  ngOnInit(): void {
    this.http.get<Resource[]>(`${environment.apiUrl}/resources`)
      .subscribe(list => this.resources.set(list));
  }
}
