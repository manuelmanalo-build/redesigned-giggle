export type OrderSide = 'BUY' | 'SELL';
export type OrderType = 'MARKET' | 'LIMIT';
export type OrderStatus = 'NEW' | 'ACCEPTED' | 'REJECTED' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELLED';
export type ExecutionType = 'ACCEPTED' | 'REJECTED' | 'PARTIAL_FILL' | 'FILL' | 'REPLACED' | 'CANCELLED';
export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
export type InstrumentStatus = 'ACTIVE' | 'HALTED' | 'DELISTED';
export type AssetClass = 'EQUITY' | 'ETF' | 'OPTION' | 'FUTURE' | 'CRYPTO';

export interface SubmitOrderRequest {
  clientOrderId: string;
  accountId: string;
  symbol: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
  limitPrice?: number | null;
}

export interface CancelOrderRequest {
  reason?: string | null;
}

export interface ReplaceOrderRequest {
  newQuantity: number;
  newLimitPrice?: number | null;
  reason?: string | null;
}

export interface OrderResponse {
  orderId: string;
  clientOrderId: string;
  accountId: string;
  symbol: string;
  side: OrderSide;
  type: OrderType;
  status: OrderStatus;
  quantity: number;
  limitPrice?: number | null;
  filledQuantity: number;
  createdAt: string;
  updatedAt: string;
}

export interface ExecutionReportResponse {
  executionReportId: string;
  orderId: string;
  executionType: ExecutionType;
  orderStatus: OrderStatus;
  executedQuantity?: number | null;
  executionPrice?: number | null;
  message?: string | null;
  createdAt: string;
}

export interface TradeResponse {
  tradeId: string;
  orderId: string;
  executionReportId: string;
  accountId: string;
  symbol: string;
  side: OrderSide;
  quantity: number;
  price: number;
  createdAt: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AccountResponse {
  accountId: string;
  displayName: string;
  status: AccountStatus;
  createdAt: string;
  updatedAt: string;
}

export interface InstrumentResponse {
  symbol: string;
  name: string;
  assetClass: AssetClass;
  status: InstrumentStatus;
  tickSize?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface ApiErrorResponse {
  timestamp: string;
  status: number;
  errorCode: string;
  message: string;
  path: string;
  correlationId: string;
}

export interface ApiResult<T> {
  body: T;
  correlationId: string | null;
}

export interface OrderSearchFilters {
  accountId?: string;
  symbol?: string;
  status?: OrderStatus | '';
  side?: OrderSide | '';
  type?: OrderType | '';
  clientOrderId?: string;
  createdFrom?: string;
  createdTo?: string;
  page?: number;
  size?: number;
}

export interface ExecutionReportSearchFilters {
  orderId?: string;
  executionType?: ExecutionType | '';
  orderStatus?: OrderStatus | '';
  page?: number;
  size?: number;
}

export interface TradeSearchFilters {
  orderId?: string;
  accountId?: string;
  symbol?: string;
  side?: OrderSide | '';
  page?: number;
  size?: number;
}
