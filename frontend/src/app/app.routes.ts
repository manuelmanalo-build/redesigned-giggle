import { Routes } from '@angular/router';

import { DashboardComponent } from './pages/dashboard.component';
import { DiagnosticsComponent } from './pages/diagnostics.component';
import { OrderDetailComponent } from './pages/order-detail.component';
import { OrdersSearchComponent } from './pages/orders-search.component';
import { SubmitOrderComponent } from './pages/submit-order.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'dashboard', component: DashboardComponent },
  { path: 'submit-order', component: SubmitOrderComponent },
  { path: 'orders', component: OrdersSearchComponent },
  { path: 'orders/:orderId', component: OrderDetailComponent },
  { path: 'diagnostics', component: DiagnosticsComponent },
  { path: '**', redirectTo: 'dashboard' }
];
