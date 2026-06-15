import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { MonoTypeOperatorFunction, Observable, catchError, map, throwError } from 'rxjs';

import { environment } from '../../environments/environment';
import {
  AccountResponse,
  ApiErrorResponse,
  ApiResult,
  CancelOrderRequest,
  ExecutionReportResponse,
  ExecutionReportSearchFilters,
  InstrumentResponse,
  OrderResponse,
  OrderSearchFilters,
  PageResponse,
  ReplaceOrderRequest,
  SubmitOrderRequest,
  TradeResponse,
  TradeSearchFilters
} from './models';

@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly storedBaseUrl = localStorage.getItem('tradeDemo.backendBaseUrl');
  readonly backendBaseUrl = signal(this.storedBaseUrl ?? environment.backendBaseUrl);

  constructor(private readonly http: HttpClient) {}

  setBackendBaseUrl(value: string): void {
    const normalized = value.trim().replace(/\/$/, '');
    this.backendBaseUrl.set(normalized);
    if (normalized) {
      localStorage.setItem('tradeDemo.backendBaseUrl', normalized);
    } else {
      localStorage.removeItem('tradeDemo.backendBaseUrl');
    }
  }

  getAccounts(): Observable<AccountResponse[]> {
    return this.http.get<AccountResponse[]>(this.url('/api/v1/accounts')).pipe(this.handleError());
  }

  getInstruments(): Observable<InstrumentResponse[]> {
    return this.http.get<InstrumentResponse[]>(this.url('/api/v1/instruments')).pipe(this.handleError());
  }

  submitOrder(request: SubmitOrderRequest, idempotencyKey: string): Observable<ApiResult<OrderResponse>> {
    return this.http.post<OrderResponse>(this.url('/api/v1/orders'), request, {
      headers: this.writeHeaders(idempotencyKey),
      observe: 'response'
    }).pipe(this.toApiResult(), this.handleError());
  }

  getOrder(orderId: string): Observable<OrderResponse> {
    return this.http.get<OrderResponse>(this.url(`/api/v1/orders/${orderId}`)).pipe(this.handleError());
  }

  searchOrders(filters: OrderSearchFilters = {}): Observable<PageResponse<OrderResponse>> {
    return this.http.get<PageResponse<OrderResponse>>(this.url('/api/v1/orders'), {
      params: this.params({
        ...filters,
        page: filters.page ?? 0,
        size: filters.size ?? 20,
        sortBy: 'createdAt',
        sortDirection: 'desc'
      })
    }).pipe(this.handleError());
  }

  getExecutionReports(orderId: string): Observable<ExecutionReportResponse[]> {
    return this.http.get<ExecutionReportResponse[]>(this.url(`/api/v1/orders/${orderId}/execution-reports`))
      .pipe(this.handleError());
  }

  searchExecutionReports(filters: ExecutionReportSearchFilters = {}): Observable<PageResponse<ExecutionReportResponse>> {
    return this.http.get<PageResponse<ExecutionReportResponse>>(this.url('/api/v1/execution-reports'), {
      params: this.params({
        ...filters,
        page: filters.page ?? 0,
        size: filters.size ?? 20,
        sortBy: 'createdAt',
        sortDirection: 'desc'
      })
    }).pipe(this.handleError());
  }

  getTrades(orderId: string): Observable<TradeResponse[]> {
    return this.http.get<TradeResponse[]>(this.url(`/api/v1/orders/${orderId}/trades`)).pipe(this.handleError());
  }

  searchTrades(filters: TradeSearchFilters = {}): Observable<PageResponse<TradeResponse>> {
    return this.http.get<PageResponse<TradeResponse>>(this.url('/api/v1/trades'), {
      params: this.params({
        ...filters,
        page: filters.page ?? 0,
        size: filters.size ?? 20,
        sortBy: 'createdAt',
        sortDirection: 'desc'
      })
    }).pipe(this.handleError());
  }

  cancelOrder(orderId: string, request: CancelOrderRequest, idempotencyKey: string): Observable<ApiResult<OrderResponse>> {
    return this.http.post<OrderResponse>(this.url(`/api/v1/orders/${orderId}/cancel`), request, {
      headers: this.writeHeaders(idempotencyKey),
      observe: 'response'
    }).pipe(this.toApiResult(), this.handleError());
  }

  replaceOrder(orderId: string, request: ReplaceOrderRequest, idempotencyKey: string): Observable<ApiResult<OrderResponse>> {
    return this.http.post<OrderResponse>(this.url(`/api/v1/orders/${orderId}/replace`), request, {
      headers: this.writeHeaders(idempotencyKey),
      observe: 'response'
    }).pipe(this.toApiResult(), this.handleError());
  }

  getHealth(): Observable<unknown> {
    return this.http.get<unknown>(this.url('/actuator/health')).pipe(this.handleError());
  }

  private url(path: string): string {
    return `${this.backendBaseUrl()}${path}`;
  }

  private writeHeaders(idempotencyKey: string): HttpHeaders {
    return new HttpHeaders({
      'Idempotency-Key': idempotencyKey,
      'X-Correlation-Id': `ui-${crypto.randomUUID()}`
    });
  }

  private params(values: Record<string, unknown>): HttpParams {
    let params = new HttpParams();
    Object.entries(values).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    });
    return params;
  }

  private toApiResult<T>() {
    return map((response: HttpResponse<T>): ApiResult<T> => ({
      body: response.body as T,
      correlationId: response.headers.get('X-Correlation-Id')
    }));
  }

  private handleError<T>(): MonoTypeOperatorFunction<T> {
    return catchError((error: HttpErrorResponse) => {
      if (error.error && typeof error.error === 'object' && 'message' in error.error) {
        return throwError(() => error.error as ApiErrorResponse);
      }

      const fallback: ApiErrorResponse = {
        timestamp: new Date().toISOString(),
        status: error.status || 0,
        errorCode: error.status ? 'HTTP_ERROR' : 'NETWORK_ERROR',
        message: error.message || 'Request failed',
        path: error.url ?? '',
        correlationId: error.headers?.get('X-Correlation-Id') ?? ''
      };
      return throwError(() => fallback);
    });
  }
}

export function newIdempotencyKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`;
}

export function toInstant(value: string | null | undefined): string | undefined {
  if (!value) {
    return undefined;
  }
  return new Date(value).toISOString();
}
