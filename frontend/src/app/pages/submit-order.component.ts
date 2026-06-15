import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { ApiClient, newIdempotencyKey } from '../shared/api-client';
import { AccountResponse, ApiErrorResponse, InstrumentResponse, OrderResponse, OrderSide, OrderType, SubmitOrderRequest } from '../shared/models';

@Component({
  selector: 'app-submit-order',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <section class="page-header">
      <div>
        <p class="eyebrow">Trade Lifecycle</p>
        <h1>Submit Order</h1>
      </div>
      <button type="button" class="secondary" (click)="resetKey()">New Idempotency Key</button>
    </section>

    <form class="panel form-grid" [formGroup]="form" (ngSubmit)="submit()">
      <label>
        Client order ID
        <input formControlName="clientOrderId" />
      </label>

      <label>
        Account
        <select formControlName="accountId">
          @for (account of activeAccounts(); track account.accountId) {
            <option [value]="account.accountId">{{ account.accountId }} - {{ account.displayName }}</option>
          }
        </select>
      </label>

      <label>
        Symbol
        <select formControlName="symbol">
          @for (instrument of activeInstruments(); track instrument.symbol) {
            <option [value]="instrument.symbol">{{ instrument.symbol }} - {{ instrument.name }}</option>
          }
        </select>
      </label>

      <label>
        Side
        <select formControlName="side">
          <option value="BUY">BUY</option>
          <option value="SELL">SELL</option>
        </select>
      </label>

      <label>
        Type
        <select formControlName="type">
          <option value="MARKET">MARKET</option>
          <option value="LIMIT">LIMIT</option>
        </select>
      </label>

      <label>
        Quantity
        <input type="number" formControlName="quantity" min="1" />
      </label>

      <label>
        Limit price
        <input type="number" formControlName="limitPrice" min="0.01" step="0.01" [disabled]="form.controls.type.value === 'MARKET'" />
      </label>

      <label class="wide">
        Idempotency-Key
        <input [value]="idempotencyKey()" readonly />
      </label>

      @if (validationMessage()) {
        <div class="alert error wide">{{ validationMessage() }}</div>
      }

      <div class="actions wide">
        <button type="submit" [disabled]="submitting()">Submit</button>
        <button type="button" class="secondary" (click)="loadReferenceData()">Reload reference data</button>
      </div>
    </form>

    @if (error()) {
      <div class="alert error">{{ error()?.message }} @if (error()?.correlationId) { <span>({{ error()?.correlationId }})</span> }</div>
    }

    @if (response()) {
      <section class="panel">
        <div class="panel-header">
          <h2>Submission Result</h2>
          <a [routerLink]="['/orders', response()?.orderId]">Open detail</a>
        </div>
        <dl class="summary-list">
          <div><dt>Order ID</dt><dd>{{ response()?.orderId }}</dd></div>
          <div><dt>Status</dt><dd><span class="status">{{ response()?.status }}</span></dd></div>
          <div><dt>Correlation ID</dt><dd>{{ correlationId() || '-' }}</dd></div>
          <div><dt>Filled quantity</dt><dd>{{ response()?.filledQuantity }}</dd></div>
        </dl>
      </section>
    }
  `
})
export class SubmitOrderComponent {
  private readonly api = inject(ApiClient);
  private readonly fb = inject(FormBuilder);

  readonly accounts = signal<AccountResponse[]>([]);
  readonly instruments = signal<InstrumentResponse[]>([]);
  readonly idempotencyKey = signal(newIdempotencyKey('submit'));
  readonly submitting = signal(false);
  readonly response = signal<OrderResponse | null>(null);
  readonly correlationId = signal<string | null>(null);
  readonly error = signal<ApiErrorResponse | null>(null);

  readonly activeAccounts = computed(() => this.accounts().filter((account) => account.status === 'ACTIVE'));
  readonly activeInstruments = computed(() => this.instruments().filter((instrument) => instrument.status === 'ACTIVE'));

  readonly form = this.fb.group({
    clientOrderId: [`CLIENT-${Date.now()}`, [Validators.required, Validators.maxLength(128)]],
    accountId: ['ACC-001', [Validators.required]],
    symbol: ['AAPL', [Validators.required]],
    side: ['BUY', [Validators.required]],
    type: ['LIMIT', [Validators.required]],
    quantity: [100, [Validators.required, Validators.min(1)]],
    limitPrice: [185.5 as number | null]
  });

  readonly validationMessage = computed(() => {
    if (!this.form.touched || this.form.valid) {
      return null;
    }
    return 'Enter a client order ID, active account, active symbol, side, type, and positive quantity.';
  });

  constructor() {
    this.form.controls.type.valueChanges.subscribe((type) => {
      if (type === 'MARKET') {
        this.form.controls.limitPrice.setValue(null);
      } else if (!this.form.controls.limitPrice.value) {
        this.form.controls.limitPrice.setValue(185.5);
      }
    });
    this.loadReferenceData();
  }

  loadReferenceData(): void {
    forkJoin({
      accounts: this.api.getAccounts(),
      instruments: this.api.getInstruments()
    }).subscribe({
      next: ({ accounts, instruments }) => {
        this.accounts.set(accounts);
        this.instruments.set(instruments);
      },
      error: (error: ApiErrorResponse) => this.error.set(error)
    });
  }

  resetKey(): void {
    this.idempotencyKey.set(newIdempotencyKey('submit'));
    this.response.set(null);
    this.correlationId.set(null);
  }

  submit(): void {
    this.form.markAllAsTouched();
    this.error.set(null);
    this.response.set(null);

    if (this.form.invalid) {
      return;
    }

    const raw = this.form.getRawValue();
    const request: SubmitOrderRequest = {
      clientOrderId: raw.clientOrderId ?? '',
      accountId: raw.accountId ?? '',
      symbol: raw.symbol ?? '',
      side: raw.side as OrderSide,
      type: raw.type as OrderType,
      quantity: Number(raw.quantity),
      limitPrice: raw.type === 'LIMIT' ? Number(raw.limitPrice) : null
    };

    this.submitting.set(true);
    this.api.submitOrder(request, this.idempotencyKey()).subscribe({
      next: (result) => {
        this.response.set(result.body);
        this.correlationId.set(result.correlationId);
        this.submitting.set(false);
      },
      error: (error: ApiErrorResponse) => {
        this.error.set(error);
        this.submitting.set(false);
      }
    });
  }
}
