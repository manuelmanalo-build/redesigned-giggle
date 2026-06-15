import { Component, computed, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { ApiClient } from '../shared/api-client';
import { ApiErrorResponse } from '../shared/models';

type MetricState = 'loading' | 'ready' | 'error';

interface Metric {
  label: string;
  value: number | string;
  state?: MetricState;
}

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink],
  template: `
    <section class="page-header">
      <div>
        <p class="eyebrow">Operational View</p>
        <h1>Dashboard</h1>
      </div>
      <button type="button" class="secondary" (click)="load()">Refresh</button>
    </section>

    @if (error()) {
      <div class="alert error">{{ error()?.message }}</div>
    }

    <section class="metric-grid">
      @for (metric of metrics(); track metric.label) {
        <article class="metric-card">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
        </article>
      }
    </section>

    <section class="panel">
      <div class="panel-header">
        <h2>Demo Entry Points</h2>
      </div>
      <div class="actions">
        <a class="button" routerLink="/submit-order">Submit order</a>
        <a class="button secondary" routerLink="/orders">Search orders</a>
        <a class="button secondary" routerLink="/diagnostics">View diagnostics</a>
      </div>
    </section>
  `
})
export class DashboardComponent {
  private readonly totals = signal<Record<string, number>>({});
  readonly loading = signal(false);
  readonly error = signal<ApiErrorResponse | null>(null);

  readonly metrics = computed<Metric[]>(() => {
    const totals = this.totals();
    return [
      { label: 'Total orders', value: totals['ALL'] ?? '-' },
      { label: 'Accepted orders', value: totals['ACCEPTED'] ?? '-' },
      { label: 'Filled orders', value: totals['FILLED'] ?? '-' },
      { label: 'Partially filled orders', value: totals['PARTIALLY_FILLED'] ?? '-' },
      { label: 'Cancelled orders', value: totals['CANCELLED'] ?? '-' },
      { label: 'Rejected orders', value: totals['REJECTED'] ?? '-' },
      { label: 'Trades created', value: totals['TRADES'] ?? '-' },
      { label: 'Pending outbox events', value: 'DB only' },
      { label: 'Failed messages', value: 'DB only' }
    ];
  });

  constructor(private readonly api: ApiClient) {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);

    forkJoin({
      ALL: this.api.searchOrders({ size: 1 }),
      ACCEPTED: this.api.searchOrders({ status: 'ACCEPTED', size: 1 }),
      FILLED: this.api.searchOrders({ status: 'FILLED', size: 1 }),
      PARTIALLY_FILLED: this.api.searchOrders({ status: 'PARTIALLY_FILLED', size: 1 }),
      CANCELLED: this.api.searchOrders({ status: 'CANCELLED', size: 1 }),
      REJECTED: this.api.searchOrders({ status: 'REJECTED', size: 1 }),
      TRADES: this.api.searchTrades({ size: 1 })
    }).subscribe({
      next: (result) => {
        this.totals.set(Object.fromEntries(
          Object.entries(result).map(([key, page]) => [key, page.totalElements])
        ));
        this.loading.set(false);
      },
      error: (error: ApiErrorResponse) => {
        this.error.set(error);
        this.loading.set(false);
      }
    });
  }
}
