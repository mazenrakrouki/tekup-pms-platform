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
    <!-- ═══ TOPBAR ════════════════════════════════════════════════════════════ -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-house" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'dashboard.breadcrumb' | transloco }}</span>
      </div>
      <div class="tb-right">
        <div class="d-none d-md-flex align-items-center gap-2 me-2">
          <span style="font-size:13px;color:var(--text-2);font-weight:500">{{ context()?.fullName }}</span>
          <span class="role-badge-light">{{ roleKey() ? (roleKey() | transloco) : '' }}</span>
        </div>
        @if (auth.hasPermission('CREATE_PROJECT')) {
          <a routerLink="/projects/new" class="btn btn-primary btn-sm">
            <i class="bi bi-plus-lg"></i>{{ 'nav.newProject' | transloco }}
          </a>
        }
      </div>
    </div>

    <!-- ═══ PAGE BODY ═════════════════════════════════════════════════════════ -->
    <div class="page-body">

      @if (forbidden()) {
        <div class="alert alert-warning d-flex align-items-center gap-2 mb-4">
          <i class="bi bi-shield-exclamation flex-shrink-0"></i>
          <span>{{ 'dashboard.accessDenied' | transloco }}</span>
          <button class="btn-close ms-auto" (click)="forbidden.set(false)"></button>
        </div>
      }

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- LOADING SKELETON                                                   -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
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

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- ADMIN DASHBOARD                                                    -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
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

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- DIRECTOR DASHBOARD                                                 -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
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
                    @for (p of dirFiltered(); track p.id) {
                      <tr class="row-link" (click)="open(p.id)">
                        <td>
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

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- CHEF DE PROJET DASHBOARD                                           -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
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
                      <a [routerLink]="['/projects', p.id]" class="btn btn-ghost btn-icon btn-sm" title="Voir le projet" [attr.aria-label]="'Voir le projet ' + p.code">
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

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- DEVELOPER DASHBOARD                                                -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
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
                  <!-- Budget : BR-050 — masqué sans VIEW_KPI (ex. développeur) -->
                  @if (auth.hasPermission('VIEW_KPI')) { <th class="text-end">{{ 'dashboard.table.budgetTnd' | transloco }}</th> }
                </tr>
              </thead>
              <tbody>
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
export class DashboardComponent implements OnInit {
  readonly auth           = inject(AuthService);
  private readonly projSvc = inject(ProjectService);
  private readonly http    = inject(HttpClient);
  private readonly route   = inject(ActivatedRoute);
  private readonly router  = inject(Router);

  loading = signal(true);

  open(id: number): void { this.router.navigate(['/projects', id]); }

  private readonly lang = inject(LanguageService);
  readonly context = this.auth.context;

  /** Date du jour, formatée selon la langue active (réactif au changement de langue). */
  readonly today = computed(() => {
    const locale = this.lang.current() === 'en' ? 'en-GB' : 'fr-FR';
    return new Date().toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  });

  readonly role    = computed(() => this.auth.context()?.roles?.[0] ?? '');
  /** Clé i18n du rôle courant (traduite dans le template) ; vide si aucun rôle. */
  readonly roleKey = computed(() => this.role() ? `roles.${this.role()}` : '');

  // Data signals
  projects        = signal<Project[]>([]);
  userCount       = signal<number>(0);
  activeUserCount = signal<number>(0);
  roleCount       = signal<number>(0);
  permissionCount = signal<number>(0);
  resourceCount   = signal<number>(0);
  forbidden       = signal(false);

  dirSearch = signal('');

  // Shared computed
  readonly activeCount    = computed(() => this.projects().filter(p => p.status === 'ACTIVE').length);
  readonly onHoldCount    = computed(() => this.projects().filter(p => p.status === 'ON_HOLD').length);
  readonly completedCount = computed(() => this.projects().filter(p => p.status === 'COMPLETED').length);
  readonly draftCount     = computed(() => this.projects().filter(p => p.status === 'DRAFT').length);

  // Director: searchable portfolio list
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

  // Director: status breakdown for progress bars
  readonly statusBreakdown = computed(() => {
    const total = this.projects().length || 1;
    return [
      { key: 'ACTIVE',    labelKey: 'status.ACTIVE',    count: this.activeCount(),    pct: this.activeCount()    / total * 100, color: 'var(--c-success)', badgeClass: 'badge-active'    },
      { key: 'COMPLETED', labelKey: 'status.COMPLETED', count: this.completedCount(), pct: this.completedCount() / total * 100, color: 'var(--c-brand)',   badgeClass: 'badge-completed' },
      { key: 'ON_HOLD',   labelKey: 'status.ON_HOLD',   count: this.onHoldCount(),    pct: this.onHoldCount()    / total * 100, color: 'var(--c-warning)', badgeClass: 'badge-on-hold'   },
      { key: 'DRAFT',     labelKey: 'status.DRAFT',     count: this.draftCount(),     pct: this.draftCount()     / total * 100, color: 'var(--text-3)',    badgeClass: 'badge-draft'     },
    ];
  });

  // Chef de projet: filter by current user
  readonly myProjects    = computed(() => {
    const myId = this.auth.currentUserId;
    return this.projects().filter(p => p.chefProjetId === myId);
  });
  readonly myActiveCount = computed(() => this.myProjects().filter(p => p.status === 'ACTIVE').length);
  readonly myDraftCount  = computed(() => this.myProjects().filter(p => p.status === 'DRAFT').length);
  readonly myBudgetFormatted = computed(() => {
    const t = this.myProjects().reduce((s, p) => s + (p.effectiveBudget ?? 0), 0);
    if (t >= 1_000_000) return (t / 1_000_000).toFixed(1) + 'M';
    if (t >= 1_000)     return (t / 1_000).toFixed(0) + 'K';
    return t.toString();
  });

  ngOnInit(): void {
    if (this.route.snapshot.queryParamMap.get('forbidden') === '1') this.forbidden.set(true);

    if (this.role() === 'ADMIN') {
      this.loadAdminData();
    } else {
      this.projSvc.listAll().subscribe({
        next: list => { this.projects.set(list); this.loading.set(false); },
        error: () => this.loading.set(false)
      });
    }
  }

  private loadAdminData(): void {
    this.http.get<{ totalElements: number }>(`${environment.apiUrl}/users?page=0&size=1`)
      .subscribe({ next: p => { this.userCount.set(p.totalElements); this.loading.set(false); }, error: () => this.loading.set(false) });
    this.http.get<{ totalElements: number }>(`${environment.apiUrl}/users?page=0&size=1&enabled=true`)
      .subscribe({ next: p => this.activeUserCount.set(p.totalElements), error: () => {} });
    this.http.get<any[]>(`${environment.apiUrl}/admin/roles`)
      .subscribe({ next: r => this.roleCount.set(r.length), error: () => {} });
    this.http.get<any[]>(`${environment.apiUrl}/admin/permissions`)
      .subscribe({ next: p => this.permissionCount.set(p.length), error: () => {} });
    this.http.get<{ totalElements: number }>(`${environment.apiUrl}/resources?page=0&size=1`)
      .subscribe({ next: r => this.resourceCount.set(r.totalElements), error: () => {} });
  }

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
