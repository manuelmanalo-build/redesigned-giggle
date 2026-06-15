import { JsonPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ApiClient } from '../shared/api-client';
import { ApiErrorResponse } from '../shared/models';

@Component({
  selector: 'app-diagnostics',
  imports: [FormsModule, JsonPipe],
  template: `
    <section class="page-header">
      <div>
        <p class="eyebrow">Operational Readiness</p>
        <h1>Diagnostics</h1>
      </div>
      <button type="button" class="secondary" (click)="loadHealth()">Refresh health</button>
    </section>

    <section class="panel form-grid">
      <label class="wide">
        Backend base URL
        <input [(ngModel)]="baseUrlDraft" placeholder="blank uses Angular proxy, or http://localhost:8080" />
      </label>
      <div class="actions wide">
        <button type="button" (click)="saveBaseUrl()">Save</button>
        <button type="button" class="secondary" (click)="clearBaseUrl()">Use proxy</button>
      </div>
    </section>

    @if (error()) {
      <div class="alert error">{{ error()?.message }}</div>
    }

    <section class="panel">
      <div class="panel-header">
        <h2>Health</h2>
      </div>
      @if (health()) {
        <pre>{{ health() | json }}</pre>
      } @else {
        <div class="empty">Health has not been loaded yet.</div>
      }
    </section>

    <section class="panel">
      <div class="panel-header">
        <h2>Outbox and Inbox Diagnostics</h2>
      </div>
      <div class="empty">
        The backend MVP persists outbox diagnostics in <code>outbox_events</code> and consumer diagnostics in
        <code>processed_messages</code>, but it does not expose business-facing REST diagnostics endpoints yet.
        Use PostgreSQL or Actuator metrics for this demo.
      </div>
      <div class="actions">
        <a class="button secondary" href="/actuator/metrics" target="_blank" rel="noreferrer">Actuator metrics</a>
        <a class="button secondary" href="/v3/api-docs" target="_blank" rel="noreferrer">OpenAPI JSON</a>
      </div>
    </section>
  `
})
export class DiagnosticsComponent {
  private readonly api = inject(ApiClient);

  readonly health = signal<unknown | null>(null);
  readonly error = signal<ApiErrorResponse | null>(null);
  baseUrlDraft = this.api.backendBaseUrl();

  constructor() {
    this.loadHealth();
  }

  saveBaseUrl(): void {
    this.api.setBackendBaseUrl(this.baseUrlDraft);
    this.loadHealth();
  }

  clearBaseUrl(): void {
    this.baseUrlDraft = '';
    this.saveBaseUrl();
  }

  loadHealth(): void {
    this.error.set(null);
    this.api.getHealth().subscribe({
      next: (health) => this.health.set(health),
      error: (error: ApiErrorResponse) => this.error.set(error)
    });
  }
}
