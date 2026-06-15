import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ApiClient, toInstant } from '../shared/api-client';
import { ApiErrorResponse, OrderResponse, OrderSearchFilters, PageResponse } from '../shared/models';

@Component({
  selector: 'app-orders-search',
  imports: [DatePipe, ReactiveFormsModule, RouterLink],
  template: `
    <section class="page-header">
      <div>
        <p class="eyebrow">Operational Search</p>
        <h1>Orders</h1>
      </div>
      <button type="button" class="secondary" (click)="clear()">Clear</button>
    </section>

    <form class="panel search-grid" [formGroup]="form" (ngSubmit)="search(0)">
      <label>Account <input formControlName="accountId" placeholder="ACC-001" /></label>
      <label>Symbol <input formControlName="symbol" placeholder="AAPL" /></label>
      <label>
        Status
        <select formControlName="status">
          <option value="">Any</option>
          <option value="ACCEPTED">ACCEPTED</option>
          <option value="PARTIALLY_FILLED">PARTIALLY_FILLED</option>
          <option value="FILLED">FILLED</option>
          <option value="CANCELLED">CANCELLED</option>
          <option value="REJECTED">REJECTED</option>
        </select>
      </label>
      <label>
        Side
        <select formControlName="side">
          <option value="">Any</option>
          <option value="BUY">BUY</option>
          <option value="SELL">SELL</option>
        </select>
      </label>
      <label>
        Type
        <select formControlName="type">
          <option value="">Any</option>
          <option value="MARKET">MARKET</option>
          <option value="LIMIT">LIMIT</option>
        </select>
      </label>
      <label>Created from <input type="datetime-local" formControlName="createdFrom" /></label>
      <label>Created to <input type="datetime-local" formControlName="createdTo" /></label>
      <label>Client order ID <input formControlName="clientOrderId" /></label>
      <label>Page size <input type="number" formControlName="size" min="1" max="100" /></label>
      <div class="actions">
        <button type="submit" [disabled]="loading()">Search</button>
      </div>
    </form>

    @if (error()) {
      <div class="alert error">{{ error()?.message }}</div>
    }

    <section class="panel">
      <div class="panel-header">
        <h2>Results</h2>
        @if (page()) {
          <span>{{ page()?.totalElements }} total</span>
        }
      </div>

      @if (loading()) {
        <div class="empty">Loading orders...</div>
      } @else if (!page()?.items?.length) {
        <div class="empty">No orders match the current filters.</div>
      } @else {
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Created</th>
                <th>Order ID</th>
                <th>Client ID</th>
                <th>Account</th>
                <th>Symbol</th>
                <th>Side</th>
                <th>Type</th>
                <th>Status</th>
                <th>Qty</th>
                <th>Filled</th>
              </tr>
            </thead>
            <tbody>
              @for (order of page()?.items; track order.orderId) {
                <tr>
                  <td>{{ order.createdAt | date:'short' }}</td>
                  <td><a [routerLink]="['/orders', order.orderId]">{{ shortId(order.orderId) }}</a></td>
                  <td>{{ order.clientOrderId }}</td>
                  <td>{{ order.accountId }}</td>
                  <td>{{ order.symbol }}</td>
                  <td>{{ order.side }}</td>
                  <td>{{ order.type }}</td>
                  <td><span class="status">{{ order.status }}</span></td>
                  <td>{{ order.quantity }}</td>
                  <td>{{ order.filledQuantity }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      @if (page()) {
        <div class="pagination">
          <button type="button" class="secondary" [disabled]="page()!.page === 0" (click)="search(page()!.page - 1)">Previous</button>
          <span>Page {{ page()!.page + 1 }} of {{ page()!.totalPages || 1 }}</span>
          <button type="button" class="secondary" [disabled]="page()!.page + 1 >= page()!.totalPages" (click)="search(page()!.page + 1)">Next</button>
        </div>
      }
    </section>
  `
})
export class OrdersSearchComponent {
  private readonly api = inject(ApiClient);
  private readonly fb = inject(FormBuilder);

  readonly loading = signal(false);
  readonly error = signal<ApiErrorResponse | null>(null);
  readonly page = signal<PageResponse<OrderResponse> | null>(null);

  readonly form = this.fb.nonNullable.group({
    accountId: [''],
    symbol: [''],
    status: [''],
    side: [''],
    type: [''],
    clientOrderId: [''],
    createdFrom: [''],
    createdTo: [''],
    size: [20]
  });

  constructor() {
    this.search(0);
  }

  search(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    const raw = this.form.getRawValue();
    const filters: OrderSearchFilters = {
      ...raw,
      createdFrom: toInstant(raw.createdFrom),
      createdTo: toInstant(raw.createdTo),
      page,
      size: Math.min(Math.max(Number(raw.size) || 20, 1), 100)
    } as OrderSearchFilters;

    this.api.searchOrders(filters).subscribe({
      next: (result) => {
        this.page.set(result);
        this.loading.set(false);
      },
      error: (error: ApiErrorResponse) => {
        this.error.set(error);
        this.loading.set(false);
      }
    });
  }

  clear(): void {
    this.form.reset({ accountId: '', symbol: '', status: '', side: '', type: '', clientOrderId: '', createdFrom: '', createdTo: '', size: 20 });
    this.search(0);
  }

  shortId(orderId: string): string {
    return orderId.slice(0, 8);
  }
}
