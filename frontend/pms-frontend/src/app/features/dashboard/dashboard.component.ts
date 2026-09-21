import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink, ActivatedRoute, Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';
import { AuthService } from '../../core/services/auth.service';
import { ProjectService } from '../../core/services/project.service';
import { Project } from '../../core/models/project.model';
import { LanguageService } from '../../core/i18n/language.service';
import { environment } from '../../../environments/environment';

// Home screen after sign-in: four layouts in one file (admin, director, chef de projet,
// developer fallback), each with KPI counters and a short project table. Display-only:
// every real check is server-side (@PreAuthorize + ADR-021), hiding a button is comfort only.
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, DecimalPipe, FormsModule, TranslocoModule],
  providers: [provideTranslocoScope('dashboard')],
  styles: [`
    tr.row-link { cursor: pointer; }
    .sk-line { height: 12px; border-radius: var(--r-xs); }
    .sk-w-40 { width: 40%; } .sk-w-55 { width: 55%; } .sk-w-70 { width: 70%; }
    .dash-module-title { font-size: 14px; font-weight: 600; color: var(--text-1); }
    .dash-module-sub   { font-size: 12px; color: var(--text-2); }
    .dash-module-cta   { font-size: 11px; font-weight: 600; color: var(--c-brand); margin-top: .875rem; display: flex; align-items: center; gap: .25rem; }
    .dash-link         { color: var(--c-brand); font-size: 12px; }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-house" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'dashboard.breadcrumb' | transloco }}</span>
      </div>
      <div class="tb-right">
        <div class="d-none d-md-flex align-items-center gap-2 me-2">
          <!-- ?. guards context not being loaded yet right after a page refresh. -->
          <span style="font-size:13px;color:var(--text-2);font-weight:500">{{ context()?.fullName }}</span>
          <span class="role-badge-light">{{ roleKey() ? (roleKey() | transloco) : role() }}</span>
        </div>
        <!-- Permission-gated, not role-gated; server still refuses the POST regardless. -->
        @if (auth.hasPermission('CREATE_PROJECT')) {
          <a routerLink="/projects/new" class="btn btn-primary btn-sm">
            <i class="bi bi-plus-lg"></i>{{ 'nav.newProject' | transloco }}
          </a>
        }
      </div>
    </div>

    <!-- ═══ PAGE BODY ═════════════════════════════════════════════════════════ -->
    <div class="page-body">

      <!-- Shown when a route guard bounced the user here via "/?forbidden=1" (ngOnInit). -->
      @if (forbidden()) {
        <div class="alert alert-warning d-flex align-items-center gap-2 mb-4">
          <i class="bi bi-shield-exclamation flex-shrink-0"></i>
          <span>{{ 'dashboard.accessDenied' | transloco }}</span>
          <button class="btn-close ms-auto" (click)="forbidden.set(false)"></button>
        </div>
      }

      <!-- One @if/@else-if chain picks exactly one of the four layouts below; the role test
           here only chooses a LAYOUT, not a security check. -->
      <!-- Skeleton blocks (not a spinner) keep page height stable while the calls run. -->
      @if (loading()) {
        <div class="page-header"><div class="skeleton sk-line" style="width:240px;height:22px"></div></div>
        <div class="row g-3 mb-4">
          @for (i of [1,2,3,4]; track i) {
            <div class="col-sm-6 col-xl-3">
              <div class="metric-card">
                <div class="skeleton mb-3" style="width:36px;height:36px;border-radius:9px"></div>
                <div class="skeleton sk-line sk-w-55 mb-2"></div>
                <div class="skeleton sk-line sk-w-40" style="height:22px"></div>
              </div>
            </div>
          }
        </div>
        <div class="card"><div class="p-3">
          @for (i of [1,2,3,4,5]; track i) { <div class="skeleton sk-line mb-2" style="height:28px"></div> }
        </div></div>
      }

      <!-- ADMIN: counts users/roles/permissions/TCC resources and links to admin modules.
           Each card is still permission-gated since two admins can hold different rights. -->
      @else if (role() === 'ADMIN') {
        <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
          <div>
            <h1 class="page-title">{{ 'dashboard.admin.title' | transloco }}</h1>
          </div>
          <span style="font-size:12px;color:var(--text-3);align-self:flex-start;padding-top:.375rem">{{ today() }}</span>
        </div>

        <!-- KPI row -->
        <div class="row g-3 mb-4">
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--brand">
                  <i class="bi bi-people-fill"></i>
                </div>
                <span class="text-caption">{{ 'dashboard.admin.metrics.total' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.users' | transloco }}</div>
              <!-- Dash instead of 0: avoids reading a confident "0 users" before/on failed load. -->
              <div class="metric-value">{{ userCount() || '—' }}</div>
            </div>
          </div>
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--success">
                  <i class="bi bi-person-check-fill"></i>
                </div>
                <span style="font-size:11px;color:var(--c-success)">{{ 'dashboard.admin.metrics.active' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.activeAccounts' | transloco }}</div>
              <div class="metric-value">{{ activeUserCount() || '—' }}</div>
            </div>
          </div>
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--purple">
                  <i class="bi bi-shield-fill-check"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.roles' | transloco }}</div>
              <div class="metric-value">{{ roleCount() || '—' }}</div>
            </div>
          </div>
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--teal">
                  <i class="bi bi-key-fill"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.permissions' | transloco }}</div>
              <div class="metric-value">{{ permissionCount() || '—' }}</div>
            </div>
          </div>
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--amber">
                  <i class="bi bi-currency-exchange"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.tccResources' | transloco }}</div>
              <div class="metric-value">{{ resourceCount() || '—' }}</div>
            </div>
          </div>
        </div>

        <!-- Modules section -->
        <div style="font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.07em;color:var(--text-2);margin-bottom:.75rem">
          {{ 'dashboard.admin.modulesTitle' | transloco }}
        </div>
        <div class="row g-3">
          <!-- Each card is gated by the permission its target page requires, else the route
               guard would bounce the user back here with ?forbidden=1. -->
          @if (auth.hasPermission('MANAGE_USERS')) {
            <div class="col-md-6 col-lg-4">
              <a routerLink="/admin/users" class="card p-3 d-block text-decoration-none module-card">
                <div class="d-flex align-items-center gap-3 mb-3">
                  <div class="metric-icon metric-icon--brand">
                    <i class="bi bi-people-fill"></i>
                  </div>
                  <div>
                    <div class="dash-module-title">{{ 'dashboard.admin.modules.users.title' | transloco }}</div>
                    <div class="dash-module-sub">{{ 'dashboard.admin.modules.users.sub' | transloco }}</div>
                  </div>
                </div>
                <div class="dash-module-cta">
                  {{ 'dashboard.admin.access' | transloco }} <i class="bi bi-arrow-right"></i>
                </div>
              </a>
            </div>
          }
          @if (auth.hasPermission('VIEW_RESOURCES')) {
            <div class="col-md-6 col-lg-4">
              <a routerLink="/resources" class="card p-3 d-block text-decoration-none module-card">
                <div class="d-flex align-items-center gap-3 mb-3">
                  <div class="metric-icon metric-icon--amber">
                    <i class="bi bi-currency-exchange"></i>
                  </div>
                  <div>
                    <div class="dash-module-title">{{ 'dashboard.admin.modules.tcc.title' | transloco }}</div>
                    <div class="dash-module-sub">{{ 'dashboard.admin.modules.tcc.sub' | transloco }}</div>
                  </div>
                </div>
                <div class="dash-module-cta">
                  {{ 'dashboard.admin.access' | transloco }} <i class="bi bi-arrow-right"></i>
                </div>
              </a>
            </div>
          }
          <!-- MANAGE_ROLES gates both cards: roles and permissions are one admin job. -->
          @if (auth.hasPermission('MANAGE_ROLES')) {
            <div class="col-md-6 col-lg-4">
              <a routerLink="/admin/roles" class="card p-3 d-block text-decoration-none module-card">
                <div class="d-flex align-items-center gap-3 mb-3">
                  <div class="metric-icon metric-icon--purple">
                    <i class="bi bi-shield-lock-fill"></i>
                  </div>
                  <div>
                    <div class="dash-module-title">{{ 'dashboard.admin.modules.roles.title' | transloco }}</div>
                    <div class="dash-module-sub">{{ 'dashboard.admin.modules.roles.sub' | transloco }}</div>
                  </div>
                </div>
                <div class="dash-module-cta">
                  {{ 'dashboard.admin.access' | transloco }} <i class="bi bi-arrow-right"></i>
                </div>
              </a>
            </div>
            <div class="col-md-6 col-lg-4">
              <a routerLink="/admin/permissions" class="card p-3 d-block text-decoration-none module-card">
                <div class="d-flex align-items-center gap-3 mb-3">
                  <div class="metric-icon metric-icon--teal">
                    <i class="bi bi-key-fill"></i>
                  </div>
                  <div>
                    <div class="dash-module-title">{{ 'dashboard.admin.modules.permissions.title' | transloco }}</div>
                    <div class="dash-module-sub">{{ 'dashboard.admin.modules.permissions.sub' | transloco }}</div>
                  </div>
                </div>
                <div class="dash-module-cta">
                  {{ 'dashboard.admin.access' | transloco }} <i class="bi bi-arrow-right"></i>
                </div>
              </a>
            </div>
          }
        </div>
      }

      <!-- DIRECTOR: whole-portfolio counters, a searchable project table, status breakdown.
           The list is exactly what the back end sends; this screen never widens it. -->
      @else if (role() === 'DIRECTEUR') {
        <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
          <div>
            <h1 class="page-title">{{ 'dashboard.director.title' | transloco }}</h1>
          </div>
          <a routerLink="/projects" class="btn btn-outline-secondary btn-sm">
            <i class="bi bi-folder2-open"></i>{{ 'dashboard.director.allProjects' | transloco }}
          </a>
        </div>

        <!-- Director KPIs -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--brand">
                  <i class="bi bi-briefcase-fill"></i>
                </div>
                <span class="text-caption">{{ 'dashboard.director.portfolio' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.director.totalProjects' | transloco }}</div>
              <div class="metric-value">{{ projects().length }}</div>
              <div class="metric-sub"><i class="bi bi-arrow-up-right" style="color:var(--c-success)"></i>{{ 'dashboard.director.globalView' | transloco }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--success">
                  <i class="bi bi-play-circle-fill"></i>
                </div>
                <span style="font-size:11px;color:var(--c-success)">{{ 'dashboard.director.inProgress' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.director.active' | transloco }}</div>
              <div class="metric-value">{{ activeCount() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--warning">
                  <i class="bi bi-pause-circle-fill"></i>
                </div>
                <span style="font-size:11px;color:var(--c-warning)">{{ 'dashboard.director.paused' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.director.onHold' | transloco }}</div>
              <div class="metric-value">{{ onHoldCount() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--teal">
                  <i class="bi bi-check2-circle"></i>
                </div>
                <span style="font-size:11px;color:var(--c-teal)">{{ 'dashboard.director.completed' | transloco | lowercase }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.director.completed' | transloco }}</div>
              <div class="metric-value">{{ completedCount() }}</div>
            </div>
          </div>
        </div>

        <!-- Portfolio table + breakdown -->
        <div class="row g-3">
          <div class="col-lg-8">
            <div class="card">
              <div class="card-header justify-content-between">
                <span>{{ 'dashboard.director.portfolioTitle' | transloco }}</span>
                <div style="display:flex;align-items:center;gap:.5rem">
                  <div class="input-wrap" style="width:200px">
                    <i class="bi bi-search input-icon"></i>
                    <!-- Manual two-way binding: [(ngModel)] can't call a signal's .set(). -->
                    <input type="search" class="form-control form-control-sm"
                           [placeholder]="'common.search' | transloco"
                           [ngModel]="dirSearch()" (ngModelChange)="dirSearch.set($event)">
                  </div>
                  <a routerLink="/projects" class="btn btn-ghost btn-sm dash-link">
                    {{ 'dashboard.director.viewAll' | transloco }} <i class="bi bi-arrow-right"></i>
                  </a>
                </div>
              </div>
              <div class="table-responsive">
                <table class="table">
                  <thead>
                    <tr>
                      <th>{{ 'dashboard.table.project' | transloco }}</th>
                      <th>{{ 'dashboard.table.projectManager' | transloco }}</th>
                      <th>{{ 'common.status' | transloco }}</th>
                      <th class="text-end">{{ 'dashboard.table.budgetTnd' | transloco }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <!-- track p.id: rows move on re-sort/filter instead of being rebuilt. -->
                    @for (p of dirFiltered(); track p.id) {
                      <tr class="row-link" (click)="open(p.id)">
                        <td>
                          <!-- stopPropagation: avoids a duplicate navigate from the row click. -->
                          <a [routerLink]="['/projects', p.id]" (click)="$event.stopPropagation()"
                             class="text-decoration-none" style="font-size:13px;font-weight:600;color:var(--text-1)">{{ p.name }}</a>
                          <div class="text-caption">{{ p.code }}</div>
                        </td>
                        <td style="color:var(--text-2)">{{ p.chefProjetName ?? '—' }}</td>
                        <td><span [class]="statusBadge(p.status)">{{ ('status.' + p.status) | transloco }}</span></td>
                        <td class="text-end" style="font-weight:600">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }}</td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="4">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-folder2-open"></i></div>
                          <div class="es-title">{{ 'dashboard.table.noProjects' | transloco }}</div>
                          <div class="es-desc">{{ 'dashboard.table.noProjectsDesc' | transloco }}</div>
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <div class="col-lg-4">
            <div class="card h-100">
              <div class="card-header">{{ 'dashboard.director.breakdownTitle' | transloco }}</div>
              <div class="p-3">
                @for (item of statusBreakdown(); track item.key) {
                  <div class="d-flex align-items-center gap-2 mb-3">
                    <span [class]="item.badgeClass" style="min-width:72px;justify-content:center">{{ item.labelKey | transloco }}</span>
                    <div class="flex-grow-1">
                      <div class="pms-progress">
                        <!-- pct computed in statusBreakdown(), not here: keep templates arithmetic-free. -->
                        <div class="pms-progress-bar" [style.width.%]="item.pct" [style.background]="item.color"></div>
                      </div>
                    </div>
                    <span style="font-size:12px;font-weight:700;color:var(--text-1);min-width:20px;text-align:right">{{ item.count }}</span>
                  </div>
                }
              </div>
            </div>
          </div>
        </div>
      }

      <!-- CHEF DE PROJET: counters all read myProjects() (chefProjetId === own user id). -->
      @else if (role() === 'CHEF_PROJET') {
        <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
          <div>
            <h1 class="page-title">{{ 'dashboard.chef.title' | transloco }}</h1>
          </div>
          @if (auth.hasPermission('CREATE_PROJECT')) {
            <a routerLink="/projects/new" class="btn btn-primary btn-sm">
              <i class="bi bi-plus-lg"></i>{{ 'nav.newProject' | transloco }}
            </a>
          }
        </div>

        <!-- Chef KPIs -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--brand">
                  <i class="bi bi-folder-fill"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.chef.myProjects' | transloco }}</div>
              <div class="metric-value">{{ myProjects().length }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--success">
                  <i class="bi bi-play-fill"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.chef.inProgress' | transloco }}</div>
              <div class="metric-value">{{ myActiveCount() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--teal">
                  <i class="bi bi-wallet2"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.chef.totalBudget' | transloco }}</div>
              <div class="metric-value" style="font-size:1.4rem">{{ myBudgetFormatted() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--warning">
                  <i class="bi bi-pencil-square"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.chef.drafts' | transloco }}</div>
              <div class="metric-value">{{ myDraftCount() }}</div>
            </div>
          </div>
        </div>

        <!-- My projects table -->
        <div class="card">
          <div class="card-header justify-content-between">
            <span>{{ 'dashboard.chef.assignedProjects' | transloco }}</span>
            <a routerLink="/projects" class="btn btn-ghost btn-sm dash-link">
              {{ 'dashboard.chef.manage' | transloco }} <i class="bi bi-arrow-right"></i>
            </a>
          </div>
          <div class="table-responsive">
            <table class="table">
              <thead>
                <tr>
                  <th>{{ 'dashboard.table.project' | transloco }}</th>
                  <th>{{ 'dashboard.table.client' | transloco }}</th>
                  <th class="d-none d-md-table-cell">{{ 'dashboard.table.period' | transloco }}</th>
                  <th>{{ 'common.status' | transloco }}</th>
                  <th class="text-end">{{ 'dashboard.table.budgetTnd' | transloco }}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <!-- First 10 only: this is a summary, the "manage" link leads to the full page. -->
                @for (p of myProjects().slice(0, 10); track p.id) {
                  <tr>
                    <td>
                      <a [routerLink]="['/projects', p.id]" class="text-decoration-none fw-semibold" style="color:var(--text-1)">{{ p.name }}</a>
                      <div class="text-caption">{{ p.code }}</div>
                    </td>
                    <td style="color:var(--text-2)">{{ p.client ?? '—' }}</td>
                    <td class="d-none d-md-table-cell" style="font-size:12px;color:var(--text-2);white-space:nowrap">
                      {{ p.startDate ?? '—' }} → {{ p.endDate ?? '—' }}
                    </td>
                    <td><span [class]="statusBadge(p.status)">{{ ('status.' + p.status) | transloco }}</span></td>
                    <td class="text-end" style="font-weight:600">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }}</td>
                    <td class="text-end">
                      <!-- Icon-only button: aria-label gives it a spoken name incl. the code. -->
                      <a [routerLink]="['/projects', p.id]" class="btn btn-ghost btn-icon btn-sm"
                         [title]="'common.viewProject' | transloco"
                         [attr.aria-label]="'common.viewProjectAria' | transloco: { code: p.code }">
                        <i class="bi bi-arrow-right"></i>
                      </a>
                    </td>
                  </tr>
                }
                @empty {
                  <tr><td colspan="6">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-folder2-open"></i></div>
                      <div class="es-title">{{ 'dashboard.chef.noAssigned' | transloco }}</div>
                      <div class="es-desc">{{ 'dashboard.chef.noAssignedDesc' | transloco }}</div>
                    </div>
                  </td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- DEVELOPER (default branch): any role not handled above lands here, so an
           unrecognized role still gets a usable page. Money column hidden without VIEW_KPI. -->
      @else {
        <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
          <div>
            <h1 class="page-title">{{ 'dashboard.developer.title' | transloco }}</h1>
          </div>
          <span style="font-size:12px;color:var(--text-3);padding-top:.375rem">{{ today() }}</span>
        </div>

        <!-- Developer KPIs -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-4">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--brand">
                  <i class="bi bi-folder2-open"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.developer.projects' | transloco }}</div>
              <div class="metric-value">{{ projects().length }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-4">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--success">
                  <i class="bi bi-play-fill"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.developer.active' | transloco }}</div>
              <div class="metric-value">{{ activeCount() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-4">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--teal">
                  <i class="bi bi-check2-circle"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.developer.completed' | transloco }}</div>
              <div class="metric-value">{{ completedCount() }}</div>
            </div>
          </div>
        </div>

        <!-- Recent projects -->
        <div class="card">
          <div class="card-header justify-content-between">
            <span>{{ 'dashboard.developer.recentProjects' | transloco }}</span>
            <a routerLink="/projects" class="btn btn-ghost btn-sm dash-link">
              {{ 'dashboard.director.viewAll' | transloco }} <i class="bi bi-arrow-right"></i>
            </a>
          </div>
          <div class="table-responsive">
            <table class="table">
              <thead>
                <tr>
                  <th>{{ 'dashboard.table.project' | transloco }}</th>
                  <th>{{ 'dashboard.table.projectManager' | transloco }}</th>
                  <th>{{ 'common.status' | transloco }}</th>
                  <!-- BR-050: test repeated on header/cells/empty-colspan to keep column count
                       consistent; real protection is server-side, this just avoids the figure. -->
                  @if (auth.hasPermission('VIEW_KPI')) { <th class="text-end">{{ 'dashboard.table.budgetTnd' | transloco }}</th> }
                </tr>
              </thead>
              <tbody>
                <!-- 8 most recent only; this is a preview, full list lives on /projects. -->
                @for (p of projects().slice(0, 8); track p.id) {
                  <tr class="row-link" (click)="open(p.id)">
                    <td>
                      <a [routerLink]="['/projects', p.id]" (click)="$event.stopPropagation()"
                         class="text-decoration-none" style="font-size:13px;font-weight:600;color:var(--text-1)">{{ p.name }}</a>
                      <div class="text-caption">{{ p.code }}</div>
                    </td>
                    <td style="color:var(--text-2)">{{ p.chefProjetName ?? '—' }}</td>
                    <td><span [class]="statusBadge(p.status)">{{ ('status.' + p.status) | transloco }}</span></td>
                    @if (auth.hasPermission('VIEW_KPI')) {
                      <td class="text-end" style="font-weight:600">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }}</td>
                    }
                  </tr>
                }
                @empty {
                  <!-- Colspan follows VIEW_KPI so the empty message still spans every column. -->
                  <tr><td [attr.colspan]="auth.hasPermission('VIEW_KPI') ? 4 : 3">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-folder2-open"></i></div>
                      <div class="es-title">{{ 'dashboard.table.noProjects' | transloco }}</div>
                      <div class="es-desc">{{ 'dashboard.developer.notAssigned' | transloco }}</div>
                    </div>
                  </td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

    </div>
  `
})
// implements OnInit rather than loading in the constructor: route params aren't guaranteed
// readable at construction time.
export class DashboardComponent implements OnInit {
  // Public: template calls auth.hasPermission(...) directly.
  readonly auth           = inject(AuthService);
  private readonly projSvc = inject(ProjectService);
  // No dedicated service yet for the five small admin counters.
  private readonly http    = inject(HttpClient);
  private readonly route   = inject(ActivatedRoute);
  private readonly router  = inject(Router);

  // Starts true so the page never flashes an empty "0 projects" state first.
  loading = signal(true);

  // A <tr> can't be a router link; row links inside call stopPropagation() to avoid double-nav.
  open(id: number): void { this.router.navigate(['/projects', id]); }

  private readonly lang = inject(LanguageService);
  readonly context = this.auth.context;

  // computed() so the date re-formats itself when the user switches language mid-session.
  readonly today = computed(() => {
    const locale = this.lang.current() === 'en' ? 'en-GB' : 'fr-FR';
    return new Date().toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  });

  // Chooses only the LAYOUT, never an authorization decision (that's always a permission
  // check). ?./?? cover the moment right after a refresh, before context is restored.
  readonly role    = computed(() => this.auth.context()?.roles?.[0] ?? '');
  // Only these four have a translated "roles.<NAME>" entry (fr.json/en.json); a role an
  // administrator creates through dynamic RBAC has none — roleKey stays '' for those, and
  // the template shows role() directly instead of an untranslated "roles.xxx" key.
  private readonly builtInRoles = ['ADMIN', 'DIRECTEUR', 'CHEF_PROJET', 'DEVELOPPEUR'];
  readonly roleKey = computed(() => this.builtInRoles.includes(this.role()) ? `roles.${this.role()}` : '');

  // ---- Raw state, filled by the HTTP answers in ngOnInit() ---------------
  projects        = signal<Project[]>([]);
  userCount       = signal<number>(0);
  activeUserCount = signal<number>(0);
  roleCount       = signal<number>(0);
  permissionCount = signal<number>(0);
  resourceCount   = signal<number>(0);
  forbidden       = signal(false);

  dirSearch = signal('');

  // ---- Derived values -----------------------------------------------------
  // computed() caches between changes; recomputing these directly in the template would
  // re-run them on every change-detection pass while typing.
  readonly activeCount    = computed(() => this.projects().filter(p => p.status === 'ACTIVE').length);
  readonly onHoldCount    = computed(() => this.projects().filter(p => p.status === 'ON_HOLD').length);
  readonly completedCount = computed(() => this.projects().filter(p => p.status === 'COMPLETED').length);
  readonly draftCount     = computed(() => this.projects().filter(p => p.status === 'DRAFT').length);

  // Client-side search on the already-loaded list, capped at 10 (real paginated search
  // lives on /projects). ?? '' guards optional client/chefProjetName from throwing on
  // .toLowerCase().
  readonly dirFiltered = computed(() => {
    const s = this.dirSearch().toLowerCase().trim();
    const list = this.projects();
    if (!s) return list.slice(0, 10);
    return list.filter(p =>
      p.code.toLowerCase().includes(s) ||
      p.name.toLowerCase().includes(s) ||
      (p.client ?? '').toLowerCase().includes(s) ||
      (p.chefProjetName ?? '').toLowerCase().includes(s)
    ).slice(0, 10);
  });

  // One object per status line so the template just loops; keeps percentage math out of HTML.
  readonly statusBreakdown = computed(() => {
    // || 1: avoids NaN percentages (and stale bar widths) on an empty portfolio.
    const total = this.projects().length || 1;
    return [
      { key: 'ACTIVE',    labelKey: 'status.ACTIVE',    count: this.activeCount(),    pct: this.activeCount()    / total * 100, color: 'var(--c-success)', badgeClass: 'badge-active'    },
      { key: 'COMPLETED', labelKey: 'status.COMPLETED', count: this.completedCount(), pct: this.completedCount() / total * 100, color: 'var(--c-brand)',   badgeClass: 'badge-completed' },
      { key: 'ON_HOLD',   labelKey: 'status.ON_HOLD',   count: this.onHoldCount(),    pct: this.onHoldCount()    / total * 100, color: 'var(--c-warning)', badgeClass: 'badge-on-hold'   },
      { key: 'DRAFT',     labelKey: 'status.DRAFT',     count: this.draftCount(),     pct: this.draftCount()     / total * 100, color: 'var(--text-3)',    badgeClass: 'badge-draft'     },
    ];
  });

  // By numeric user id, not name (names can collide). Null currentUserId simply matches
  // nothing, so the table shows its empty state instead of crashing.
  readonly myProjects    = computed(() => {
    const myId = this.auth.currentUserId;
    return this.projects().filter(p => p.chefProjetId === myId);
  });
  readonly myActiveCount = computed(() => this.myProjects().filter(p => p.status === 'ACTIVE').length);
  readonly myDraftCount  = computed(() => this.myProjects().filter(p => p.status === 'DRAFT').length);
  // Shortened for the small KPI card (e.g. "2.4M"/"850K"); exact amounts stay in the table.
  // ?? 0 guards optional effectiveBudget from turning the sum into NaN.
  readonly myBudgetFormatted = computed(() => {
    const t = this.myProjects().reduce((s, p) => s + (p.effectiveBudget ?? 0), 0);
    if (t >= 1_000_000) return (t / 1_000_000).toFixed(1) + 'M';
    if (t >= 1_000)     return (t / 1_000).toFixed(0) + 'K';
    return t.toString();
  });

  ngOnInit(): void {
    // snapshot is enough: the component is recreated fresh on each such redirect.
    if (this.route.snapshot.queryParamMap.get('forbidden') === '1') this.forbidden.set(true);

    // Admin page never shows projects, so it gets its own load (and avoids a 403 for an
    // admin without project permissions).
    if (this.role() === 'ADMIN') {
      this.loadAdminData();
    } else {
      this.projSvc.listAll().subscribe({
        next: list => { this.projects.set(list); this.loading.set(false); },
        // Stops the skeleton on error too, else the page looks frozen forever.
        error: () => this.loading.set(false)
      });
    }
  }

  // Five parallel, independent calls: each error callback is empty except the first, which
  // alone turns off the skeleton so the page appears as soon as the main figure is known.
  private loadAdminData(): void {
    // page=0&size=1: only need Spring Data's totalElements, not the actual rows.
    this.http.get<{ totalElements: number }>(`${environment.apiUrl}/users?page=0&size=1`)
      .subscribe({ next: p => { this.userCount.set(p.totalElements); this.loading.set(false); }, error: () => this.loading.set(false) });
    // enabled=true: a departed user is disabled, not deleted, so this must be filtered.
    this.http.get<{ totalElements: number }>(`${environment.apiUrl}/users?page=0&size=1&enabled=true`)
      .subscribe({ next: p => this.activeUserCount.set(p.totalElements), error: () => {} });
    // Roles/permissions are short, unpaged lists; any[] since only .length is used.
    this.http.get<any[]>(`${environment.apiUrl}/admin/roles`)
      .subscribe({ next: r => this.roleCount.set(r.length), error: () => {} });
    this.http.get<any[]>(`${environment.apiUrl}/admin/permissions`)
      .subscribe({ next: p => this.permissionCount.set(p.length), error: () => {} });
    this.http.get<{ totalElements: number }>(`${environment.apiUrl}/resources?page=0&size=1`)
      .subscribe({ next: r => this.resourceCount.set(r.totalElements), error: () => {} });
  }

  // Lookup table (not if/else) so a new status is one line; unmapped statuses fall back to
  // a neutral badge instead of an unstyled label.
  statusBadge(s: string): string {
    const map: Record<string, string> = {
      ACTIVE:    'badge-active',
      COMPLETED: 'badge-completed',
      DRAFT:     'badge-draft',
      ON_HOLD:   'badge-on-hold',
      CANCELLED: 'badge-cancelled',
    };
    return map[s] ?? 'badge-draft';
  }
}
