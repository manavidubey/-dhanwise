-- V3: Transaction Reconciliation, Price Intelligence History, and Cashflow Forecasting

-- Track historical price observations per series or subscription
create table if not exists price_observation (
  id text primary key,
  series_id text not null,
  observed_on text not null,
  amount_minor bigint not null,
  currency text not null references currency(code),
  exponent integer not null,
  source_txn_id text references txn(id) on delete set null,
  is_step_change integer not null default 0,
  percentage_change real default 0.0,
  created_at text not null
);
create index if not exists ix_price_obs_series on price_observation(series_id, observed_on);

-- Track reconciled matches between imported bank transactions and active subscriptions
create table if not exists transaction_reconciliation (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  txn_id text not null references txn(id) on delete cascade,
  subscription_id text references subscription(id) on delete set null,
  match_type text not null check (match_type in ('EXACT_MATCH', 'AMOUNT_VARIANCE', 'DATE_VARIANCE', 'DUPLICATE_CHARGE', 'UNEXPECTED_CHARGE')),
  expected_amount_minor bigint,
  actual_amount_minor bigint not null,
  currency text not null references currency(code),
  variance_minor bigint not null default 0,
  notes text,
  reconciled_at text not null
);
create index if not exists ix_recon_profile on transaction_reconciliation(profile_id, reconciled_at);
create index if not exists ix_recon_txn on transaction_reconciliation(txn_id);
create index if not exists ix_recon_sub on transaction_reconciliation(subscription_id);

-- Price change alerts on recurring series
alter table recurring_series add column price_changed_on text;
alter table recurring_series add column previous_amount_minor bigint;
alter table recurring_series add column current_amount_minor bigint;
alter table recurring_series add column price_change_pct real;
