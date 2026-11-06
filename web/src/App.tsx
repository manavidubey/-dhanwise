import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  cancelSubscription,
  createManualSubscription,
  deleteSubscription,
  getCatalogue,
  getCashflowForecast,
  getOverview,
  getPerks,
  getReconciliation,
  loadDemo,
  resetData,
  runReconciliation,
  triageSeries,
  updateProfile,
  updateSubscriptionLifecycle,
  uploadStatement,
  type CashflowForecast,
  type Catalogue,
  type Overview,
  type PerkView,
  type PlanView,
  type ProviderView,
  type ReconciledItem,
} from "./api";

type Tab = "overview" | "reconciliation" | "forecasting" | "perks" | "catalogue";

function formatCadence(cadence: string) {
  const map: Record<string, string> = {
    monthly: "Monthly",
    annual: "Annual",
    annually: "Annual",
    weekly: "Weekly",
    quarterly: "Quarterly",
    every_28_days: "28-Day",
  };
  return map[cadence] ?? cadence.replace(/_/g, " ");
}

function ServiceSymbol({ name }: { name: string }) {
  const abbr = name.replace(/[^a-zA-Z0-9]/g, "").slice(0, 2).toUpperCase() || "SW";
  return <div className="service-symbol">{abbr}</div>;
}

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>("overview");
  const [overview, setOverview] = useState<Overview | null>(null);
  const [catalogue, setCatalogue] = useState<Catalogue | null>(null);
  const [perks, setPerks] = useState<PerkView[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [showAddModal, setShowAddModal] = useState(false);
  const [showProfileModal, setShowProfileModal] = useState(false);
  const [showGuide, setShowGuide] = useState(true);

  const [reconciliations, setReconciliations] = useState<ReconciledItem[]>([]);
  const [forecast, setForecast] = useState<CashflowForecast | null>(null);

  // Profile Customization State
  const [profileNameInput, setProfileNameInput] = useState("");
  const [profileCurrencyInput, setProfileCurrencyInput] = useState("INR");

  // Subscription Form State
  const [selectedProvider, setSelectedProvider] = useState<ProviderView | null>(null);
  const [selectedPlan, setSelectedPlan] = useState<PlanView | null>(null);
  const [customName, setCustomName] = useState("");
  const [subCadence, setSubCadence] = useState("monthly");
  const [subAmount, setSubAmount] = useState("");
  const [subCurrency, setSubCurrency] = useState("INR");
  const [subNextDate, setSubNextDate] = useState("");
  const [subCapability, setSubCapability] = useState("software");

  const fileInputRef = useRef<HTMLInputElement>(null);

  const refreshAll = useCallback(async () => {
    setError(null);
    try {
      const [ov, cat, pk, rec, fc] = await Promise.all([
        getOverview(),
        getCatalogue().catch(() => null),
        getPerks().catch(() => []),
        getReconciliation().catch(() => []),
        getCashflowForecast().catch(() => null),
      ]);
      setOverview(ov);
      if (ov) {
        setProfileNameInput(ov.profileName);
        setProfileCurrencyInput(ov.currency);
        setSubCurrency(ov.currency);
      }
      if (cat) setCatalogue(cat);
      setPerks(pk);
      setReconciliations(rec);
      setForecast(fc);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load intelligence data");
    }
  }, []);

  useEffect(() => {
    void refreshAll();
  }, [refreshAll]);

  async function handleAsyncAction(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : "An error occurred");
    } finally {
      setBusy(false);
    }
  }

  const handleProviderSelect = (providerId: string) => {
    if (providerId === "custom") {
      setSelectedProvider(null);
      setSelectedPlan(null);
      setCustomName("");
      setSubAmount("");
      return;
    }
    const found = catalogue?.providers.find((p) => p.id === providerId);
    if (!found) return;
    setSelectedProvider(found);
    setCustomName(found.name);
    if (found.plans.length > 0) {
      handlePlanSelect(found.plans[0]);
    }
  };

  const handlePlanSelect = (plan: PlanView) => {
    setSelectedPlan(plan);
    setSubCapability(plan.capability);
    const prefCurrency = overview?.currency ?? "INR";
    const matchedOffer =
      plan.offers.find((o) => o.price.currency === prefCurrency) ?? plan.offers[0];
    if (matchedOffer) {
      setSubAmount(
        (matchedOffer.price.minor / Math.pow(10, matchedOffer.price.exponent)).toFixed(2)
      );
      setSubCurrency(matchedOffer.price.currency);
      setSubCadence(matchedOffer.billingPeriod);
    }
  };

  const onAddSubscriptionSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const name = customName || selectedProvider?.name;
    if (!name || !subAmount) {
      setError("Please provide a name and amount for the subscription.");
      return;
    }
    void handleAsyncAction(async () => {
      const updated = await createManualSubscription({
        name,
        cadence: subCadence,
        amount: subAmount,
        currency: subCurrency,
        nextExpected: subNextDate || undefined,
        capability: subCapability,
      });
      setOverview(updated);
      setShowAddModal(false);
      setSelectedProvider(null);
      setSelectedPlan(null);
      setCustomName("");
      setSubAmount("");
      setNotice(`Added ${name} to your active tracker.`);
      const pk = await getPerks().catch(() => []);
      setPerks(pk);
    });
  };

  const onProfileSave = (e: React.FormEvent) => {
    e.preventDefault();
    void handleAsyncAction(async () => {
      const updated = await updateProfile({
        displayName: profileNameInput,
        currency: profileCurrencyInput,
      });
      setOverview(updated);
      setShowProfileModal(false);
      setNotice(`Profile updated: ${updated.profileName} (${updated.currency}).`);
    });
  };

  const onResetAccount = () => {
    if (
      window.confirm(
        "Reset all tracked subscriptions, transactions, and audit findings to an empty personal state?"
      )
    ) {
      void handleAsyncAction(async () => {
        const updated = await resetData();
        setOverview(updated);
        const pk = await getPerks().catch(() => []);
        setPerks(pk);
        setNotice("Account reset. You have a fresh, clean slate ready for your personalized data.");
      });
    }
  };

  // Calculations
  const activeSubs = overview?.subscriptions.filter((s) => s.state !== "cancelled") ?? [];

  const currencySymbol =
    overview?.currency === "USD"
      ? "$"
      : overview?.currency === "EUR"
      ? "€"
      : overview?.currency === "GBP"
      ? "£"
      : "₹";

  const totalAnnualSpend = activeSubs.reduce(
    (acc, s) => acc + s.annualized.minor / Math.pow(10, s.annualized.exponent),
    0
  );

  return (
    <div className="app-container">
      {/* ── Left Sidebar Navigation ── */}
      <aside className="app-sidebar">
        <div className="brand-section">
          <div className="brand-details">
            <span className="brand-title">DhanWise</span>
            <span className="brand-subtitle">Spend Intelligence</span>
          </div>
        </div>

        {/* Personalized Profile Card */}
        <div className="user-profile-widget">
          <div className="profile-meta">
            <span className="profile-name">{overview?.profileName || "Personal"}</span>
            <span className="profile-currency">{overview?.currency || "INR"} Base</span>
          </div>
          <button
            className="profile-edit-btn"
            title="Personalize Profile"
            onClick={() => {
              setProfileNameInput(overview?.profileName || "");
              setProfileCurrencyInput(overview?.currency || "INR");
              setShowProfileModal(true);
            }}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 20h9" />
              <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
            </svg>
          </button>
        </div>

        <nav className="nav-section">
          <button
            className={`nav-button ${activeTab === "overview" ? "active" : ""}`}
            onClick={() => setActiveTab("overview")}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <rect x="3" y="3" width="7" height="7" rx="1" />
              <rect x="14" y="3" width="7" height="7" rx="1" />
              <rect x="3" y="14" width="7" height="7" rx="1" />
              <rect x="14" y="14" width="7" height="7" rx="1" />
            </svg>
            <span>Subscriptions</span>
            {activeSubs.length > 0 && <span className="nav-pill-count">{activeSubs.length}</span>}
          </button>

          <button
            className={`nav-button ${activeTab === "reconciliation" ? "active" : ""}`}
            onClick={() => setActiveTab("reconciliation")}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
              <line x1="16" y1="13" x2="8" y2="13" />
              <line x1="16" y1="17" x2="8" y2="17" />
              <polyline points="10 9 9 9 8 9" />
            </svg>
            <span>Reconciliation</span>
            {reconciliations.length > 0 && <span className="nav-pill-count">{reconciliations.length}</span>}
          </button>

          <button
            className={`nav-button ${activeTab === "forecasting" ? "active" : ""}`}
            onClick={() => setActiveTab("forecasting")}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="18" y1="20" x2="18" y2="10" />
              <line x1="12" y1="20" x2="12" y2="4" />
              <line x1="6" y1="20" x2="6" y2="14" />
            </svg>
            <span>Cashflow Forecast</span>
          </button>

          <button
            className={`nav-button ${activeTab === "perks" ? "active" : ""}`}
            onClick={() => setActiveTab("perks")}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M20 12v10H4V12" />
              <rect x="2" y="7" width="20" height="5" rx="1" />
              <line x1="12" y1="22" x2="12" y2="7" />
              <path d="M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7z" />
              <path d="M12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z" />
            </svg>
            <span>Included Perks</span>
            {perks.length > 0 && <span className="nav-pill-count">{perks.length}</span>}
          </button>

          <button
            className={`nav-button ${activeTab === "catalogue" ? "active" : ""}`}
            onClick={() => setActiveTab("catalogue")}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
            </svg>
            <span>Plan Directory</span>
            {catalogue && <span className="nav-pill-count">{catalogue.providers.length}</span>}
          </button>
        </nav>

        <div className="sidebar-actions-section">
          <label className="action-link-btn">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" />
              <line x1="12" y1="3" x2="12" y2="15" />
            </svg>
            <span>Import Bank CSV</span>
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv,text/csv"
              disabled={busy}
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) {
                  void handleAsyncAction(async () => {
                    const res = await uploadStatement(file);
                    await refreshAll();
                    setNotice(
                      res.duplicateFile
                        ? "This statement file was already processed."
                        : `Successfully imported ${res.imported} transactions.`
                    );
                  });
                }
              }}
            />
          </label>

          <button
            className="action-link-btn"
            disabled={busy}
            onClick={() =>
              void handleAsyncAction(async () => {
                await loadDemo();
                await refreshAll();
                setNotice("Loaded demonstration dataset with 36 real-world recurring transactions.");
              })
            }
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polygon points="5 3 19 12 5 21 5 3" />
            </svg>
            <span>Load Demo Data</span>
          </button>

          <a className="action-link-btn" href="/api/export/transactions.csv">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span>Export CSV</span>
          </a>

          <button
            className="action-link-btn btn-reset"
            disabled={busy}
            onClick={onResetAccount}
            title="Clear all data for clean personalized setup"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="3 6 5 6 21 6" />
              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
            </svg>
            <span>Reset All Data</span>
          </button>
        </div>

        <div className="sidebar-footer-info">
          <div className="privacy-badge">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
            <span>Local & Private Database</span>
          </div>
        </div>
      </aside>

      {/* ── Main Viewport ── */}
      <main className="app-main">
        <header className="app-topbar">
          <div className="topbar-meta">
            <h1 className="topbar-heading">
              {activeTab === "overview" && "Active Subscriptions"}
              {activeTab === "reconciliation" && "Transaction Reconciliation & Audit"}
              {activeTab === "forecasting" && "Cashflow Outflow Forecast"}
              {activeTab === "perks" && "Included Benefits & Perks"}
              {activeTab === "catalogue" && "Verified Plan Directory"}
            </h1>
            <p className="topbar-desc">
              {activeTab === "overview" && `Automated recurring charge auditing for ${overview?.profileName || "you"}.`}
              {activeTab === "reconciliation" && "Bank transaction matching against known subscriptions, detecting stealth price hikes & anomalies."}
              {activeTab === "forecasting" && "Predictive 30, 60, and 90-day cash requirements based on verified subscription cadence."}
              {activeTab === "perks" && "Entitlements and perks bundled inside your existing telecom and software plans."}
              {activeTab === "catalogue" && "Standard verified subscription rates across Indian and global providers."}
            </p>
          </div>

          {activeTab === "overview" && (
            <button className="topbar-cta" onClick={() => setShowAddModal(true)} disabled={busy}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                <line x1="12" y1="5" x2="12" y2="19" />
                <line x1="5" y1="12" x2="19" y2="12" />
              </svg>
              <span>Add Subscription</span>
            </button>
          )}
        </header>

        {/* ── Alerts ── */}
        {error && (
          <div className="system-notification notification-error">
            <span>{error}</span>
            <button className="notification-close" onClick={() => setError(null)}>×</button>
          </div>
        )}
        {notice && (
          <div className="system-notification notification-success">
            <span>{notice}</span>
            <button className="notification-close" onClick={() => setNotice(null)}>×</button>
          </div>
        )}

        <div className="view-content">
          {/* ── Tab: Subscriptions (Overview) ── */}
          {activeTab === "overview" && (
            <>
              {/* Onboarding Guide / How it Works */}
              {showGuide && (
                <div className="guide-banner">
                  <div className="guide-header">
                    <span className="guide-title">
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <circle cx="12" cy="12" r="10" />
                        <line x1="12" y1="16" x2="12" y2="12" />
                        <line x1="12" y1="8" x2="12.01" y2="8" />
                      </svg>
                      Personalizing Your DhanWise Experience
                    </span>
                    <button className="guide-dismiss" onClick={() => setShowGuide(false)}>Dismiss</button>
                  </div>
                  <div className="guide-steps">
                    <div className="guide-step">
                      <div className="step-num">01</div>
                      <div className="step-content">
                        <span className="step-title">Add Your Actual Subscriptions</span>
                        <p className="step-desc">
                          Click "+ Add Subscription" to select from verified plans or enter your custom recurring software and telecom tools.
                        </p>
                      </div>
                    </div>
                    <div className="guide-step">
                      <div className="step-num">02</div>
                      <div className="step-content">
                        <span className="step-title">Import Statements for Detection</span>
                        <p className="step-desc">
                          Upload any bank or credit card CSV. The recurring detection engine spots price increases and potential overlaps.
                        </p>
                      </div>
                    </div>
                    <div className="guide-step">
                      <div className="step-num">03</div>
                      <div className="step-content">
                        <span className="step-title">Personalize Currency & Profile</span>
                        <p className="step-desc">
                          Click the pencil next to your name in the sidebar to configure your default currency and account name.
                        </p>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* Financial Metrics Strip */}
              <div className="metrics-strip">
                <div className="metric-cell primary-metric">
                  <span className="metric-tag">Total Annual Commitment</span>
                  <div className="metric-value">
                    {currencySymbol}{totalAnnualSpend.toLocaleString("en-IN", { maximumFractionDigits: 0 })}
                  </div>
                  <span className="metric-sub">
                    Across {overview?.counts.subscriptions ?? 0} active subscriptions
                  </span>
                </div>

                <div className="metric-cell">
                  <span className="metric-tag">Active Services</span>
                  <div className="metric-value">{overview?.counts.subscriptions ?? 0}</div>
                  <span className="metric-sub">Tracked recurring</span>
                </div>

                <div className="metric-cell">
                  <span className="metric-tag">Transactions</span>
                  <div className="metric-value">{overview?.counts.transactions ?? 0}</div>
                  <span className="metric-sub">Processed in ledger</span>
                </div>

                <div className="metric-cell">
                  <span className="metric-tag">Identified Savings</span>
                  <div className="metric-value highlight-green">
                    {overview?.savings.potential.display ?? `${currencySymbol}0`}
                  </div>
                  <span className="metric-sub">Potential annual cuts</span>
                </div>
              </div>

              {/* Renewal Reminders & Upcoming Charges */}
              {overview && overview.renewals.length > 0 && (
                <section className="content-section">
                  <div className="section-headline">
                    <h2 className="section-title">Upcoming Renewal Reminders</h2>
                    <span className="section-counter">{overview.renewals.length} scheduled</span>
                  </div>
                  <div className="renewals-deck">
                    {overview.renewals.map((r) => (
                      <div
                        key={r.subscriptionId}
                        className={`renewal-card ${r.daysRemaining <= 7 && r.daysRemaining >= 0 ? "urgent" : ""}`}
                      >
                        <div className="renewal-card-top">
                          <span className="renewal-service">{r.name}</span>
                          <span
                            className={`renewal-days-tag ${
                              r.daysRemaining <= 7 && r.daysRemaining >= 0 ? "urgent-tag" : ""
                            }`}
                          >
                            {r.daysRemaining === 0
                              ? "Today"
                              : r.daysRemaining === 1
                              ? "Tomorrow"
                              : r.daysRemaining > 1
                              ? `In ${r.daysRemaining} days`
                              : "Recent"}
                          </span>
                        </div>
                        <div className="renewal-card-bottom">
                          <span className="renewal-exact-date">{r.on}</span>
                          <span className="renewal-cost">{r.amount.display}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                </section>
              )}



              {/* Subscriptions Table */}
              <section className="content-section">
                <div className="section-headline">
                  <h2 className="section-title">All Subscriptions</h2>
                  <span className="section-counter">
                    {overview?.subscriptions.length ?? 0} total
                  </span>
                </div>

                {!overview || overview.subscriptions.length === 0 ? (
                  <div className="table-card">
                    <div className="empty-box">
                      <div className="empty-box-title">No subscriptions tracked yet</div>
                      <p className="empty-box-desc">
                        Your account is currently clean. Click <strong>+ Add Subscription</strong> at the top right to log your first subscription, or import a bank statement CSV.
                      </p>
                    </div>
                  </div>
                ) : (
                  <div className="table-card">
                    <div className="table-header">
                      <div>Service</div>
                      <div>Cycle</div>
                      <div>Amount</div>
                      <div>Next Renewal</div>
                      <div style={{ textAlign: "right" }}>Status / Actions</div>
                    </div>
                    {overview.subscriptions.map((sub) => (
                      <div
                        key={sub.id}
                        className={`table-row ${sub.state === "cancelled" ? "row-cancelled" : ""}`}
                      >
                        <div className="col-service">
                          <ServiceSymbol name={sub.name} />
                          <div className="service-text">
                            <span className="service-name">{sub.name}</span>
                            <span className="service-badge">{sub.capability || "Software"}</span>
                          </div>
                        </div>

                        <div className="col-billing">{formatCadence(sub.cadence)}</div>

                        <div className="col-cost">
                          <span className="cost-typical">
                            {sub.typical.display}
                            <span style={{ fontSize: "11px", color: "var(--text-dim)" }}>/mo</span>
                          </span>
                          <span className="cost-annual">{sub.annualized.display}/yr</span>
                        </div>

                        <div className="col-renewal">
                          {sub.nextExpected ? (
                            <>
                              <span className="renewal-date">{sub.nextExpected}</span>
                              <span className="renewal-label">Scheduled</span>
                            </>
                          ) : (
                            <span className="renewal-label">—</span>
                          )}
                        </div>

                        <div className="col-actions">
                          <select
                            className="status-select"
                            value={
                              sub.cancellationStage ??
                              (sub.state === "cancelled" ? "USER_CONFIRMED_CANCELLED" : "NONE")
                            }
                            onChange={(e) =>
                              void handleAsyncAction(async () => {
                                const updated = await updateSubscriptionLifecycle(sub.id, e.target.value);
                                setOverview(updated);
                                setNotice(`Updated ${sub.name} status.`);
                              })
                            }
                          >
                            <option value="NONE">Active Paying</option>
                            <option value="CANCELLATION_REQUESTED">Cancelling</option>
                            <option value="USER_CONFIRMED_CANCELLED">Cancelled</option>
                            <option value="REALIZED_SAVINGS">Charge Stopped</option>
                          </select>

                          {sub.state !== "cancelled" && (
                            <button
                              className="table-btn"
                              disabled={busy}
                              onClick={() =>
                                void handleAsyncAction(async () => {
                                  const updated = await cancelSubscription(sub.id);
                                  setOverview(updated);
                                  setNotice(`Marked ${sub.name} as cancelled.`);
                                })
                              }
                            >
                              Cancel
                            </button>
                          )}

                          <button
                            className="table-btn btn-remove"
                            disabled={busy}
                            onClick={() => {
                              if (window.confirm(`Permanently remove ${sub.name}?`)) {
                                void handleAsyncAction(async () => {
                                  const updated = await deleteSubscription(sub.id);
                                  setOverview(updated);
                                  setNotice(`Deleted ${sub.name}.`);
                                });
                              }
                            }}
                          >
                            Delete
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </section>

              {/* Detected Candidate Series from CSV Statements */}
              {overview && overview.candidates.length > 0 && (
                <section className="content-section">
                  <div className="section-headline">
                    <h2 className="section-title">Detected Candidates for Review</h2>
                    <span className="section-counter">{overview.candidates.length} detected</span>
                  </div>
                  <div className="table-card">
                    <div className="table-header">
                      <div>Merchant</div>
                      <div>Cadence</div>
                      <div>Observed Amount</div>
                      <div>Confidence</div>
                      <div style={{ textAlign: "right" }}>Confirmation</div>
                    </div>
                    {overview.candidates.map((c) => (
                      <div key={c.id} className="table-row">
                        <div className="col-service">
                          <ServiceSymbol name={c.merchantName} />
                          <div className="service-text">
                            <span className="service-name">{c.merchantName}</span>
                            {c.priceChange && (
                              <span className="price-change-pill">Price Changed: {c.priceChange}</span>
                            )}
                          </div>
                        </div>
                        <div className="col-billing">{formatCadence(c.cadence)}</div>
                        <div className="col-cost">
                          <span className="cost-typical">{c.typical.display}</span>
                        </div>
                        <div className="col-renewal">
                          <span className="renewal-date">{Math.round(c.confidence * 100)}%</span>
                          <span className="renewal-label">Detection Confidence</span>
                        </div>
                        <div className="col-actions">
                          <button
                            className="table-btn btn-confirm"
                            disabled={busy}
                            onClick={() =>
                              void handleAsyncAction(async () => {
                                const updated = await triageSeries(c.id, "confirmed");
                                setOverview(updated);
                                setNotice(`Confirmed ${c.merchantName}.`);
                              })
                            }
                          >
                            Confirm
                          </button>
                          <button
                            className="table-btn btn-remove"
                            disabled={busy}
                            onClick={() =>
                              void handleAsyncAction(async () => {
                                const updated = await triageSeries(c.id, "rejected");
                                setOverview(updated);
                                setNotice(`Rejected ${c.merchantName}.`);
                              })
                            }
                          >
                            Reject
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                </section>
              )}
            </>
          )}

          {/* ── Tab: Reconciliation & Audit ── */}
          {activeTab === "reconciliation" && (
            <div style={{ display: "flex", flexDirection: "column", gap: "24px" }}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                <div>
                  <h3 style={{ fontSize: "16px", fontWeight: "600", color: "var(--text-main)", fontFamily: "var(--font-serif)" }}>
                    Bank Statement Matches vs Tracked Subscriptions
                  </h3>
                  <p style={{ fontSize: "12.5px", color: "var(--text-muted)", marginTop: "2px" }}>
                    Deterministic reconciliation matching imported transactions with your active commitments.
                  </p>
                </div>
                <button
                  className="table-btn btn-confirm"
                  disabled={busy}
                  onClick={() =>
                    void handleAsyncAction(async () => {
                      const res = await runReconciliation();
                      setReconciliations(res);
                      setNotice("Reconciliation completed across all transactions.");
                    })
                  }
                >
                  Re-run Reconciliation Audit
                </button>
              </div>

              {reconciliations.length === 0 ? (
                <div className="table-card">
                  <div className="empty-box">
                    <div className="empty-box-title">No transactions reconciled yet</div>
                    <p className="empty-box-desc">
                      Import a bank statement or load sample statement data to view automated matching, variance detection, and audit notes.
                    </p>
                  </div>
                </div>
              ) : (
                <div className="table-card">
                  <div className="table-header" style={{ gridTemplateColumns: "1.2fr 2.5fr 1.5fr 1.2fr 2fr" }}>
                    <div>Date</div>
                    <div>Bank Raw Narration</div>
                    <div>Matched Service</div>
                    <div>Amount / Variance</div>
                    <div style={{ textAlign: "right" }}>Reconciliation Status</div>
                  </div>
                  {reconciliations.map((item) => (
                    <div key={item.id} className="table-row" style={{ gridTemplateColumns: "1.2fr 2.5fr 1.5fr 1.2fr 2fr" }}>
                      <div style={{ fontFamily: "var(--font-mono)", fontSize: "12px", color: "var(--text-main)" }}>
                        {item.postedOn}
                      </div>

                      <div style={{ display: "flex", flexDirection: "column", gap: "2px", minWidth: 0 }}>
                        <span style={{ fontSize: "12.5px", fontWeight: "600", color: "var(--text-main)" }}>
                          {item.descriptorRaw}
                        </span>
                        <span style={{ fontSize: "11px", color: "var(--text-dim)" }}>
                          Txn ID: {item.txnId.slice(0, 16)}...
                        </span>
                      </div>

                      <div style={{ display: "flex", flexDirection: "column", gap: "2px" }}>
                        <span style={{ fontSize: "13px", fontWeight: "600", color: "var(--text-main)" }}>
                          {item.subscriptionName || "Unlinked Purchase"}
                        </span>
                        {item.expectedAmount && (
                          <span style={{ fontSize: "11px", color: "var(--text-dim)", fontFamily: "var(--font-mono)" }}>
                            Baseline: {item.expectedAmount.display}
                          </span>
                        )}
                      </div>

                      <div style={{ display: "flex", flexDirection: "column", gap: "2px" }}>
                        <span style={{ fontFamily: "var(--font-mono)", fontSize: "13px", fontWeight: "600", color: "var(--text-main)" }}>
                          {item.actualAmount.display}
                        </span>
                        {item.variance.minor !== 0 && (
                          <span className={`recon-variance-tag ${item.variance.minor > 0 ? "positive" : "neutral"}`}>
                            {item.variance.minor > 0 ? "+" : ""}{item.variance.display}
                          </span>
                        )}
                      </div>

                      <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: "4px" }}>
                        <span
                          className={`recon-badge ${
                            item.matchType === "EXACT_MATCH"
                              ? "match-exact"
                              : item.matchType === "AMOUNT_VARIANCE"
                              ? "match-variance"
                              : "match-unexpected"
                          }`}
                        >
                          {item.matchType.replace(/_/g, " ")}
                        </span>
                        <span style={{ fontSize: "11px", color: "var(--text-muted)", textAlign: "right" }}>
                          {item.notes}
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* ── Tab: Cashflow Forecast ── */}
          {activeTab === "forecasting" && (
            <div style={{ display: "flex", flexDirection: "column", gap: "24px" }}>
              <div className="forecast-strip">
                <div className="forecast-card">
                  <span className="forecast-period">30-Day Projected Outflow</span>
                  <div className="forecast-amount">
                    {forecast?.horizon30Days.display ?? "₹0.00"}
                  </div>
                  <span className="forecast-sub">
                    {forecast?.eventCount30Days ?? 0} expected renewals in next 30 days
                  </span>
                </div>

                <div className="forecast-card">
                  <span className="forecast-period">60-Day Projected Outflow</span>
                  <div className="forecast-amount">
                    {forecast?.horizon60Days.display ?? "₹0.00"}
                  </div>
                  <span className="forecast-sub">
                    {forecast?.eventCount60Days ?? 0} expected renewals across 60 days
                  </span>
                </div>

                <div className="forecast-card">
                  <span className="forecast-period">90-Day Projected Outflow</span>
                  <div className="forecast-amount">
                    {forecast?.horizon90Days.display ?? "₹0.00"}
                  </div>
                  <span className="forecast-sub">
                    {forecast?.eventCount90Days ?? 0} expected renewals across 90 days
                  </span>
                </div>
              </div>

              <section className="content-section">
                <div className="section-headline">
                  <h2 className="section-title">Schedule of Projected Renewals (Next 90 Days)</h2>
                  <span className="section-counter">
                    {forecast?.upcomingEvents.length ?? 0} events scheduled
                  </span>
                </div>

                {!forecast || forecast.upcomingEvents.length === 0 ? (
                  <div className="table-card">
                    <div className="empty-box">
                      <div className="empty-box-title">No renewals scheduled</div>
                      <p className="empty-box-desc">
                        Add active subscriptions to see deterministic 30, 60, and 90-day cash projections.
                      </p>
                    </div>
                  </div>
                ) : (
                  <div className="table-card">
                    <div className="table-header" style={{ gridTemplateColumns: "1.2fr 2fr 2fr 1.2fr 1.5fr" }}>
                      <div>Scheduled Date</div>
                      <div>Subscription & Cycle</div>
                      <div>Audit & Horizon Reason</div>
                      <div>Timeline</div>
                      <div style={{ textAlign: "right" }}>Projected Amount</div>
                    </div>
                    {forecast.upcomingEvents.map((evt, idx) => (
                      <div key={idx} className="table-row" style={{ gridTemplateColumns: "1.2fr 2fr 2fr 1.2fr 1.5fr" }}>
                        <div style={{ fontFamily: "var(--font-mono)", fontSize: "12.5px", color: "var(--text-main)" }}>
                          {evt.scheduledOn}
                        </div>
                        <div style={{ display: "flex", flexDirection: "column", gap: "2px" }}>
                          <span style={{ fontWeight: "600", fontSize: "13.5px", color: "var(--text-main)" }}>
                            {evt.name}
                          </span>
                          <span style={{ fontSize: "11px", color: "var(--text-dim)", textTransform: "capitalize" }}>
                            Frequency: {evt.billingFrequency.replace(/_/g, " ")} ({evt.eventType || "RENEWAL"})
                          </span>
                        </div>
                        <div style={{ display: "flex", flexDirection: "column", gap: "2px" }}>
                          <span style={{ fontSize: "12px", color: "var(--text-muted)" }}>
                            {evt.inclusionReason}
                          </span>
                          <span style={{ fontSize: "10.5px", color: "var(--text-dim)", fontFamily: "var(--font-mono)" }}>
                            ID: {evt.subscriptionId.slice(0, 8)}...
                          </span>
                        </div>
                        <div>
                          <span
                            className={`renewal-days-tag ${
                              evt.daysFromNow <= 7 ? "urgent-tag" : ""
                            }`}
                          >
                            {evt.daysFromNow === 0
                              ? "Today"
                              : evt.daysFromNow === 1
                              ? "Tomorrow"
                              : `In ${evt.daysFromNow} days`}
                          </span>
                        </div>
                        <div style={{ textAlign: "right", fontFamily: "var(--font-mono)", fontSize: "13.5px", fontWeight: "600", color: "var(--text-main)" }}>
                          {evt.amount.display}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            </div>
          )}

          {/* ── Tab: Included Perks ── */}
          {activeTab === "perks" && (
            <div className="perks-deck">
              {perks.length === 0 ? (
                <div className="empty-box" style={{ gridColumn: "1 / -1" }}>
                  <div className="empty-box-title">Loading Perks Catalogue</div>
                  <p className="empty-box-desc">Fetching verified bundled benefits...</p>
                </div>
              ) : (
                perks.map((p) => (
                  <div key={p.id} className={`perk-item ${p.heldByUser ? "held-active" : ""}`}>
                    <div className="perk-meta-top">
                      <span className="perk-source-tag">{p.sourceName}</span>
                      <span className={`perk-state-pill ${p.heldByUser ? "pill-active-held" : "pill-available"}`}>
                        {p.heldByUser ? "Covered by Your Plan" : "Available in Catalogue"}
                      </span>
                    </div>
                    <h3 className="perk-item-name">{p.description}</h3>
                    <p className="perk-item-desc">
                      Bundled with your {p.sourceName} subscription. If you are paying for this service separately, you can cancel it.
                    </p>
                    <div className="perk-instructions">
                      <span className="instr-label">Benefit Details</span>
                      <span className="instr-body">
                        {p.activationRequired
                          ? "Activation required via provider portal or app"
                          : "Automatically active with plan membership"}
                      </span>
                    </div>
                    {p.termsUrl && (
                      <a href={p.termsUrl} target="_blank" rel="noopener noreferrer" className="perk-link">
                        <span>View Official Provider Terms</span>
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
                          <polyline points="15 3 21 3 21 9" />
                          <line x1="10" y1="14" x2="21" y2="3" />
                        </svg>
                      </a>
                    )}
                  </div>
                ))
              )}
            </div>
          )}



          {/* ── Tab: Plan Directory ── */}
          {activeTab === "catalogue" && (
            <div className="directory-grid">
              {catalogue?.providers.map((p) => (
                <div key={p.id} className="directory-card">
                  <div className="dir-card-header">
                    <ServiceSymbol name={p.name} />
                    <div className="dir-title-block">
                      <span className="dir-title">{p.name}</span>
                      <span className="dir-category">{p.category}</span>
                    </div>
                  </div>

                  <div className="dir-plans-list">
                    {p.plans.map((pl) => (
                      <div key={pl.id} className="dir-plan-item">
                        <span className="dir-plan-name">{pl.name}</span>
                        <div className="dir-offers-row">
                          {pl.offers.map((o, idx) => (
                            <span key={idx} className="dir-offer-badge">
                              {o.price.display}/{o.billingPeriod}
                              <span className="offer-region">{o.region}</span>
                            </span>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </main>

      {/* ── Modal: Personalize Profile ── */}
      {showProfileModal && (
        <div className="modal-overlay" onClick={() => setShowProfileModal(false)}>
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h2 className="modal-heading">Personalize Profile</h2>
                <p className="modal-subheading">Update your household name and default currency</p>
              </div>
              <button className="modal-close-icon" onClick={() => setShowProfileModal(false)}>×</button>
            </div>

            <form className="modal-form" onSubmit={onProfileSave}>
              <div className="form-group">
                <label className="form-label">Display Name / Profile</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder="e.g. Manavi, Personal, Home"
                  value={profileNameInput}
                  onChange={(e) => setProfileNameInput(e.target.value)}
                  required
                />
              </div>

              <div className="form-group">
                <label className="form-label">Primary Currency</label>
                <select
                  className="form-input"
                  value={profileCurrencyInput}
                  onChange={(e) => setProfileCurrencyInput(e.target.value)}
                >
                  <option value="INR">INR (₹)</option>
                  <option value="USD">USD ($)</option>
                  <option value="EUR">EUR (€)</option>
                  <option value="GBP">GBP (£)</option>
                  <option value="SGD">SGD (S$)</option>
                </select>
              </div>

              <div className="modal-footer-btns">
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => setShowProfileModal(false)}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary-action"
                  disabled={busy}
                >
                  {busy ? "Saving..." : "Save Preferences"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ── Modal: Add Subscription ── */}
      {showAddModal && (
        <div className="modal-overlay" onClick={() => setShowAddModal(false)}>
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h2 className="modal-heading">Add Subscription</h2>
                <p className="modal-subheading">Select from standard catalogue or enter custom plan</p>
              </div>
              <button className="modal-close-icon" onClick={() => setShowAddModal(false)}>×</button>
            </div>

            <form className="modal-form" onSubmit={onAddSubscriptionSubmit}>
              <div className="form-group">
                <label className="form-label">Service Provider</label>
                <select
                  className="form-input"
                  value={selectedProvider?.id ?? "custom"}
                  onChange={(e) => handleProviderSelect(e.target.value)}
                >
                  <option value="custom">Custom Service</option>
                  {catalogue &&
                    Object.entries(
                      catalogue.providers.reduce((acc, p) => {
                        const cat = p.category || "other";
                        if (!acc[cat]) acc[cat] = [];
                        acc[cat].push(p);
                        return acc;
                      }, {} as Record<string, ProviderView[]>)
                    ).map(([cat, provs]) => (
                      <optgroup key={cat} label={cat.toUpperCase()}>
                        {provs.map((pr) => (
                          <option key={pr.id} value={pr.id}>
                            {pr.name}
                          </option>
                        ))}
                      </optgroup>
                    ))}
                </select>
              </div>

              {selectedProvider && selectedProvider.plans.length > 1 && (
                <div className="form-group">
                  <label className="form-label">Select Plan Tier</label>
                  <select
                    className="form-input"
                    value={selectedPlan?.id ?? ""}
                    onChange={(e) => {
                      const pl = selectedProvider.plans.find((p) => p.id === e.target.value);
                      if (pl) handlePlanSelect(pl);
                    }}
                  >
                    {selectedProvider.plans.map((pl) => (
                      <option key={pl.id} value={pl.id}>
                        {pl.name}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              <div className="form-group">
                <label className="form-label">Subscription Name</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder="e.g. Netflix, Spotify, Claude Pro"
                  value={customName}
                  onChange={(e) => setCustomName(e.target.value)}
                  required
                />
              </div>

              <div className="form-grid-2">
                <div className="form-group">
                  <label className="form-label">Billing Cycle</label>
                  <select
                    className="form-input"
                    value={subCadence}
                    onChange={(e) => setSubCadence(e.target.value)}
                  >
                    <option value="monthly">Monthly</option>
                    <option value="annual">Annual</option>
                    <option value="every_28_days">28-Day Telecom</option>
                    <option value="quarterly">Quarterly</option>
                    <option value="weekly">Weekly</option>
                  </select>
                </div>

                <div className="form-group">
                  <label className="form-label">Currency</label>
                  <select
                    className="form-input"
                    value={subCurrency}
                    onChange={(e) => setSubCurrency(e.target.value)}
                  >
                    <option value="INR">INR (₹)</option>
                    <option value="USD">USD ($)</option>
                    <option value="EUR">EUR (€)</option>
                    <option value="GBP">GBP (£)</option>
                    <option value="SGD">SGD (S$)</option>
                  </select>
                </div>
              </div>

              <div className="form-grid-2">
                <div className="form-group">
                  <label className="form-label">Amount</label>
                  <input
                    type="number"
                    step="0.01"
                    min="0"
                    placeholder="0.00"
                    className="form-input"
                    value={subAmount}
                    onChange={(e) => setSubAmount(e.target.value)}
                    required
                  />
                </div>

                <div className="form-group">
                  <label className="form-label">Category</label>
                  <select
                    className="form-input"
                    value={subCapability}
                    onChange={(e) => setSubCapability(e.target.value)}
                  >
                    <option value="ai">AI Assistant</option>
                    <option value="ai_coding">AI Development</option>
                    <option value="video">Video &amp; Streaming</option>
                    <option value="music">Music &amp; Audio</option>
                    <option value="storage">Cloud Storage</option>
                    <option value="telecom">Telecom</option>
                    <option value="productivity">Productivity</option>
                    <option value="software">Software / SaaS</option>
                  </select>
                </div>
              </div>

              <div className="form-group">
                <label className="form-label">Next Renewal Date (Optional)</label>
                <input
                  type="date"
                  className="form-input"
                  value={subNextDate}
                  onChange={(e) => setSubNextDate(e.target.value)}
                />
              </div>

              <div className="modal-footer-btns">
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => setShowAddModal(false)}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary-action"
                  disabled={busy}
                >
                  {busy ? "Saving..." : "Save Subscription"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
