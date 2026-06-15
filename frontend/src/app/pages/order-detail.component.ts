import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { ApiClient, newIdempotencyKey } from '../shared/api-client';
import { ApiErrorResponse, ExecutionReportResponse, OrderResponse, TradeResponse } from '../shared/models';

@Component({
  selector: 'app-order-detail',
  imports: [DatePipe, ReactiveFormsModule, RouterLink],
  template: `
    <section class="page-header">
      <div>
        <p class="eyebrow">Order Detail</p>
        <h1>{{ order()?.clientOrderId || shortId(orderId()) }}</h1>
      </div>
      <a class="button secondary" routerLink="/orders">Back to search</a>
    </section>

    @if (error()) {
      <div class="alert error">{{ error()?.message }}</div>
    }

    @if (loading()) {
      <div class="panel empty">Loading order detail...</div>
    } @else if (order()) {
      <section class="panel">
        <div class="panel-header">
          <h2>Summary</h2>
          <span class="status">{{ order()?.status }}</span>
        </div>
        <dl class="summary-list">
          <div><dt>Order ID</dt><dd>{{ order()?.orderId }}</dd></div>
          <div><dt>Account</dt><dd>{{ order()?.accountId }}</dd></div>
          <div><dt>Symbol</dt><dd>{{ order()?.symbol }}</dd></div>
          <div><dt>Side / Type</dt><dd>{{ order()?.side }} / {{ order()?.type }}</dd></div>
          <div><dt>Quantity</dt><dd>{{ order()?.quantity }}</dd></div>
          <div><dt>Limit price</dt><dd>{{ order()?.limitPrice ?? '-' }}</dd></div>
          <div><dt>Filled quantity</dt><dd>{{ order()?.filledQuantity }}</dd></div>
          <div><dt>Updated</dt><dd>{{ order()?.updatedAt | date:'medium' }}</dd></div>
        </dl>
      </section>

      <section class="two-column">
        <form class="panel" [formGroup]="cancelForm" (ngSubmit)="cancel()">
          <div class="panel-header">
            <h2>Cancel</h2>
            <span>{{ cancelKey() }}</span>
          </div>
          <label>
            Reason
            <input formControlName="reason" />
          </label>
          <div class="actions">
            <button type="submit" [disabled]="!canCancel() || actionLoading()">Cancel order</button>
            <button type="button" class="secondary" (click)="resetCancelKey()">New key</button>
          </div>
          @if (!canCancel()) {
            <p class="hint">Cancel is only available for ACCEPTED or PARTIALLY_FILLED orders.</p>
          }
        </form>

        <form class="panel" [formGroup]="replaceForm" (ngSubmit)="replace()">
          <div class="panel-header">
            <h2>Replace</h2>
            <span>{{ replaceKey() }}</span>
          </div>
          <label>
            New quantity
            <input type="number" min="1" formControlName="newQuantity" />
          </label>
          <label>
            New limit price
            <input type="number" min="0.01" step="0.01" formControlName="newLimitPrice" />
          </label>
          <label>
            Reason
            <input formControlName="reason" />
          </label>
          <div class="actions">
            <button type="submit" [disabled]="!canReplace() || actionLoading()">Replace order</button>
            <button type="button" class="secondary" (click)="resetReplaceKey()">New key</button>
          </div>
          @if (!canReplace()) {
            <p class="hint">Replace is available for open LIMIT orders only in this demo.</p>
          }
        </form>
      </section>

      <section class="panel">
        <div class="panel-header">
          <h2>Execution Reports</h2>
          <span>{{ executionReports().length }}</span>
        </div>
        @if (!executionReports().length) {
          <div class="empty">No execution reports yet.</div>
        } @else {
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Created</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Qty</th>
                  <th>Price</th>
                  <th>Message</th>
                </tr>
              </thead>
              <tbody>
                @for (report of executionReports(); track report.executionReportId) {
                  <tr>
                    <td>{{ report.createdAt | date:'short' }}</td>
                    <td>{{ report.executionType }}</td>
                    <td>{{ report.orderStatus }}</td>
                    <td>{{ report.executedQuantity ?? '-' }}</td>
                    <td>{{ report.executionPrice ?? '-' }}</td>
                    <td>{{ report.message || '-' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>

      <section class="panel">
        <div class="panel-header">
          <h2>Trades</h2>
          <span>{{ trades().length }}</span>
        </div>
        @if (!trades().length) {
          <div class="empty">No trades booked for this order.</div>
        } @else {
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Created</th>
                  <th>Trade ID</th>
                  <th>Qty</th>
                  <th>Price</th>
                </tr>
              </thead>
              <tbody>
                @for (trade of trades(); track trade.tradeId) {
                  <tr>
                    <td>{{ trade.createdAt | date:'short' }}</td>
                    <td>{{ shortId(trade.tradeId) }}</td>
                    <td>{{ trade.quantity }}</td>
                    <td>{{ trade.price }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    }
  `
})
export class OrderDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ApiClient);
  private readonly fb = inject(FormBuilder);

  readonly orderId = signal('');
  readonly order = signal<OrderResponse | null>(null);
  readonly executionReports = signal<ExecutionReportResponse[]>([]);
  readonly trades = signal<TradeResponse[]>([]);
  readonly loading = signal(false);
  readonly actionLoading = signal(false);
  readonly error = signal<ApiErrorResponse | null>(null);
  readonly cancelKey = signal(newIdempotencyKey('cancel'));
  readonly replaceKey = signal(newIdempotencyKey('replace'));

  readonly cancelForm = this.fb.nonNullable.group({
    reason: ['Client requested cancel']
  });

  readonly replaceForm = this.fb.nonNullable.group({
    newQuantity: [100, [Validators.required, Validators.min(1)]],
    newLimitPrice: [185.5, [Validators.min(0.01)]],
    reason: ['Client amended order']
  });

  readonly canCancel = computed(() => ['ACCEPTED', 'PARTIALLY_FILLED'].includes(this.order()?.status ?? ''));
  readonly canReplace = computed(() => {
    const order = this.order();
    return !!order && order.type === 'LIMIT' && ['ACCEPTED', 'PARTIALLY_FILLED'].includes(order.status);
  });

  constructor() {
    this.route.paramMap.subscribe((params) => {
      this.orderId.set(params.get('orderId') ?? '');
      this.load();
    });
  }

  load(): void {
    if (!this.orderId()) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      order: this.api.getOrder(this.orderId()),
      executionReports: this.api.getExecutionReports(this.orderId()),
      trades: this.api.getTrades(this.orderId())
    }).subscribe({
      next: ({ order, executionReports, trades }) => {
        this.order.set(order);
        this.executionReports.set(executionReports);
        this.trades.set(trades);
        this.replaceForm.patchValue({
          newQuantity: order.quantity,
          newLimitPrice: order.limitPrice ?? 185.5
        });
        this.loading.set(false);
      },
      error: (error: ApiErrorResponse) => {
        this.error.set(error);
        this.loading.set(false);
      }
    });
  }

  cancel(): void {
    if (!this.canCancel()) {
      return;
    }
    this.actionLoading.set(true);
    this.error.set(null);
    this.api.cancelOrder(this.orderId(), this.cancelForm.getRawValue(), this.cancelKey()).subscribe({
      next: (result) => {
        this.order.set(result.body);
        this.cancelKey.set(newIdempotencyKey('cancel'));
        this.actionLoading.set(false);
        this.load();
      },
      error: (error: ApiErrorResponse) => {
        this.error.set(error);
        this.actionLoading.set(false);
      }
    });
  }

  resetCancelKey(): void {
    this.cancelKey.set(newIdempotencyKey('cancel'));
  }

  resetReplaceKey(): void {
    this.replaceKey.set(newIdempotencyKey('replace'));
  }

  replace(): void {
    this.replaceForm.markAllAsTouched();
    if (!this.canReplace() || this.replaceForm.invalid) {
      return;
    }
    this.actionLoading.set(true);
    this.error.set(null);
    const raw = this.replaceForm.getRawValue();
    this.api.replaceOrder(this.orderId(), {
      newQuantity: Number(raw.newQuantity),
      newLimitPrice: raw.newLimitPrice ? Number(raw.newLimitPrice) : null,
      reason: raw.reason
    }, this.replaceKey()).subscribe({
      next: (result) => {
        this.order.set(result.body);
        this.replaceKey.set(newIdempotencyKey('replace'));
        this.actionLoading.set(false);
        this.load();
      },
      error: (error: ApiErrorResponse) => {
        this.error.set(error);
        this.actionLoading.set(false);
      }
    });
  }

  shortId(value: string | undefined): string {
    return value ? value.slice(0, 8) : '-';
  }
}
