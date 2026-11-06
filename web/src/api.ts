export type MoneyView = { minor: number; currency: string; exponent: number; display: string };

export type SubscriptionView = {
  id: string;
  seriesId: string;
  name: string;
  state: string;
  cancellationStage?: string;
  cadence: string;
  typical: MoneyView;
  annualized: MoneyView;
  nextExpected: string | null;
  confidence: number;
  variableAmount: boolean;
  capability: string | null;
};

export type SeriesView = {
  id: string;
  merchantName: string;
  cadence: string;
  typical: MoneyView;
  nextExpected: string | null;
  confidence: number;
  variableAmount: boolean;
  priceChange: string | null;
};

export type RenewalView = { subscriptionId: string; name: string; on: string; amount: MoneyView; daysRemaining: number };

export type RecommendationView = {
  id: string;
  kind: string;
  title: string;
  detail: string;
  savingsClass: string;
  amount: MoneyView | null;
  confidence: number;
  evidence: string;
  assumptions: string;
};

export type SavingsView = {
  potential: MoneyView;
  estimated: MoneyView;
  confirmed: MoneyView;
  realized: MoneyView;
};

export type Overview = {
  profileName: string;
  currency: string;
  catalogueUnverified: boolean;
  counts: { transactions: number; subscriptions: number; candidates: number };
  subscriptions: SubscriptionView[];
  candidates: SeriesView[];
  renewals: RenewalView[];
  recommendations: RecommendationView[];
  savings: SavingsView;
};

export type ImportResult = {
  importId: string;
  totalRows: number;
  imported: number;
  duplicates: number;
  duplicateFile: boolean;
  errors: string[];
};

export type OfferView = {
  region: string;
  price: MoneyView;
  billingPeriod: string;
  taxInclusive: boolean;
  sourceUrl: string;
  retrievedAt: string;
  reviewStatus: string;
};

export type PlanView = { id: string; name: string; capability: string; offers: OfferView[] };
export type ProviderView = {
  id: string;
  name: string;
  category: string;
  homepage: string;
  plans: PlanView[];
};
export type PerkView = {
  id: string;
  sourcePlanId: string;
  sourceName: string;
  capability: string;
  targetProviderId: string;
  targetPlanId: string;
  description: string;
  activationRequired: boolean;
  status: string;
  termsUrl: string | null;
  heldByUser: boolean;
};

export type Catalogue = { note: string; providers: ProviderView[] };

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const body = (await response.json()) as { error?: string };
      if (body?.error) message = body.error;
    } catch {
      /* keep the status message */
    }
    throw new Error(message);
  }
  return (await response.json()) as T;
}

export const getOverview = () => request<Overview>("/api/overview");
export const getCatalogue = () => request<Catalogue>("/api/catalogue");
export const getPerks = () => request<PerkView[]>("/api/perks");
export const loadDemo = () => request<Overview>("/api/demo", { method: "POST" });
export const resetData = () => request<Overview>("/api/reset", { method: "POST" });
export const updateProfile = (data: { displayName: string; currency: string }) =>
  request<Overview>("/api/profile", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });

export const cancelSubscription = (id: string) =>
  request<Overview>(`/api/subscriptions/${encodeURIComponent(id)}/cancel`, { method: "POST" });

export const updateSubscriptionLifecycle = (id: string, stage: string) =>
  request<Overview>(`/api/subscriptions/${encodeURIComponent(id)}/lifecycle?stage=${encodeURIComponent(stage)}`, { method: "POST" });

export const createManualSubscription = (data: {
  name: string;
  cadence: string;
  amount: string;
  currency?: string;
  nextExpected?: string;
  capability?: string;
}) =>
  request<Overview>("/api/subscriptions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });

export const deleteSubscription = (id: string) =>
  request<Overview>(`/api/subscriptions/${encodeURIComponent(id)}`, { method: "DELETE" });

export const triageRecommendation = (id: string, status: string, actionTaken?: string, snoozeDays?: number) =>
  request<Overview>(`/api/recommendations/${encodeURIComponent(id)}/triage`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ status, actionTaken, snoozeDays }),
  });

export const triageSeries = (id: string, status: "confirmed" | "rejected") =>
  request<Overview>(`/api/series/${encodeURIComponent(id)}/triage?status=${status}`, { method: "POST" });

export type ReconciledItem = {
  id: string;
  txnId: string;
  subscriptionId: string | null;
  subscriptionName: string | null;
  postedOn: string;
  descriptorRaw: string;
  matchType: "EXACT_MATCH" | "AMOUNT_VARIANCE" | "DATE_VARIANCE" | "DUPLICATE_CHARGE" | "UNEXPECTED_CHARGE";
  expectedAmount: MoneyView | null;
  actualAmount: MoneyView;
  variance: MoneyView;
  notes: string;
};

export type ForecastEvent = {
  subscriptionId: string;
  name: string;
  scheduledOn: string;
  billingFrequency: string;
  amount: MoneyView;
  eventType: string;
  inclusionReason: string;
  daysFromNow: number;
};

export type CashflowForecast = {
  horizon30Days: MoneyView;
  horizon60Days: MoneyView;
  horizon90Days: MoneyView;
  eventCount30Days: number;
  eventCount60Days: number;
  eventCount90Days: number;
  upcomingEvents: ForecastEvent[];
};

export const getReconciliation = () => request<ReconciledItem[]>("/api/reconciliation");
export const runReconciliation = () => request<ReconciledItem[]>("/api/reconciliation/run", { method: "POST" });
export const getCashflowForecast = () => request<CashflowForecast>("/api/forecasting");

export async function uploadStatement(file: File): Promise<ImportResult> {
  const form = new FormData();
  form.append("file", file);
  return request<ImportResult>("/api/imports", { method: "POST", body: form });
}

export type DealView = {
  id: string;
  merchantId: string;
  merchantName: string;
  title: string;
  description: string;
  category: string;
  offerType: string;
  discountPct: number | null;
  discountAmount: MoneyView | null;
  maxDiscount: MoneyView | null;
  minSpend: MoneyView | null;
  requiredPaymentMethod: string | null;
  requiredMembershipId: string | null;
  promoCode: string | null;
  validFrom: string | null;
  validTo: string | null;
  sourceUrl: string | null;
  terms: string | null;
  verificationStatus: string;
  relevanceScore: number;
  relevanceReason: string;
  isEligible: boolean;
  eligibilityReason: string;
  estimatedSavings: MoneyView | null;
  expiringSoon: boolean;
  daysRemaining: number;
  userStatus: "available" | "saved" | "redeemed" | "dismissed";
};

export type DealsSummaryView = {
  totalActiveDeals: number;
  eligibleDealsCount: number;
  savedDealsCount: number;
  redeemedDealsCount: number;
  totalPotentialSavings: MoneyView;
  totalRealizedSavings: MoneyView;
  deals: DealView[];
};

export const getDeals = () => request<DealsSummaryView>("/api/deals");

export const performDealAction = (dealId: string, action: "save" | "unsave" | "redeem" | "dismiss", realizedSavingsMinor?: number, note?: string) =>
  request<DealsSummaryView>(`/api/deals/${encodeURIComponent(dealId)}/action`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ action, realizedSavingsMinor, note }),
  });

