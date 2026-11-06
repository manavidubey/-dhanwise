-- SubScout v0.1 schema. Money is always stored as minor units with its ISO-4217 exponent.
-- The ledger is append-only: corrections are reversal rows, never updates.

create table if not exists profile (
  id text primary key,
  display_name text not null,
  created_at text not null
);

create table if not exists currency (
  code text primary key,
  exponent integer not null check (exponent between 0 and 6),
  name text not null
);

insert or ignore into currency (code, exponent, name) values
  ('INR', 2, 'Indian Rupee'), ('USD', 2, 'US Dollar'), ('EUR', 2, 'Euro'), ('GBP', 2, 'Pound Sterling'),
  ('AUD', 2, 'Australian Dollar'), ('CAD', 2, 'Canadian Dollar'), ('SGD', 2, 'Singapore Dollar'),
  ('AED', 2, 'UAE Dirham'), ('CHF', 2, 'Swiss Franc'), ('HKD', 2, 'Hong Kong Dollar'),
  ('NZD', 2, 'New Zealand Dollar'), ('ZAR', 2, 'South African Rand'), ('BRL', 2, 'Brazilian Real'),
  ('MXN', 2, 'Mexican Peso'), ('THB', 2, 'Thai Baht'), ('MYR', 2, 'Malaysian Ringgit'),
  ('PHP', 2, 'Philippine Peso'), ('PLN', 2, 'Polish Zloty'), ('TRY', 2, 'Turkish Lira'),
  ('SEK', 2, 'Swedish Krona'), ('NOK', 2, 'Norwegian Krone'), ('DKK', 2, 'Danish Krone'),
  ('CNY', 2, 'Yuan Renminbi'),
  ('JPY', 0, 'Yen'), ('KRW', 0, 'Won'), ('VND', 0, 'Dong'), ('IDR', 0, 'Rupiah'),
  ('CLP', 0, 'Chilean Peso'), ('ISK', 0, 'Iceland Krona'),
  ('KWD', 3, 'Kuwaiti Dinar'), ('BHD', 3, 'Bahraini Dinar'), ('OMR', 3, 'Rial Omani'),
  ('JOD', 3, 'Jordanian Dinar'), ('TND', 3, 'Tunisian Dinar'), ('LYD', 3, 'Libyan Dinar'),
  ('IQD', 3, 'Iraqi Dinar'),
  ('CLF', 4, 'Unidad de Fomento'), ('UYW', 4, 'Unidad Previsional');

create table if not exists account (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  kind text not null check (kind in ('bank','card','wallet')),
  name text not null,
  institution text,
  currency text not null references currency(code),
  last4 text,
  fx_markup_bps integer not null default 0 check (fx_markup_bps between 0 and 2000),
  created_at text not null
);
create index if not exists ix_account_profile on account(profile_id);

create table if not exists merchant (
  id text primary key,
  canonical_name text not null,
  category text,
  created_at text not null
);

create table if not exists merchant_alias (
  alias text primary key,
  merchant_id text not null references merchant(id) on delete cascade,
  source text not null check (source in ('seed','auto','user','reviewed')),
  confidence real not null default 1.0 check (confidence between 0 and 1),
  created_at text not null
);
create index if not exists ix_alias_merchant on merchant_alias(merchant_id);

create table if not exists statement_import (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  account_id text not null references account(id) on delete cascade,
  file_name text not null,
  file_sha256 text not null,
  parser text not null,
  status text not null check (status in ('parsed','partial','failed')),
  total_rows integer not null default 0,
  imported_rows integer not null default 0,
  duplicate_rows integer not null default 0,
  error_count integer not null default 0,
  created_at text not null
);
create unique index if not exists ux_import_hash on statement_import(profile_id, account_id, file_sha256);

create table if not exists txn (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  account_id text not null references account(id) on delete cascade,
  import_id text references statement_import(id) on delete set null,
  posted_on text not null,
  descriptor_raw text not null,
  descriptor_normalized text not null,
  merchant_id text references merchant(id),
  direction text not null check (direction in ('debit','credit')),
  amount_minor bigint not null,
  currency text not null references currency(code),
  exponent integer not null check (exponent between 0 and 6),
  settled_minor bigint,
  settled_currency text references currency(code),
  settled_exponent integer,
  fx_rate text,
  fx_rate_date text,
  fx_markup_bps integer,
  fee_minor bigint not null default 0,
  tax_minor bigint not null default 0,
  kind text not null default 'purchase'
    check (kind in ('purchase','credit','refund','fee','emi_installment','transfer','auth_hold')),
  external_ref text,
  dedupe_hash text not null,
  created_at text not null
);
create unique index if not exists ux_txn_dedupe on txn(account_id, dedupe_hash);
create index if not exists ix_txn_profile_date on txn(profile_id, posted_on);
create index if not exists ix_txn_merchant on txn(merchant_id, posted_on);

create table if not exists recurring_series (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  merchant_id text not null references merchant(id) on delete cascade,
  cadence text not null,
  currency text not null references currency(code),
  exponent integer not null,
  typical_amount_minor bigint not null,
  low_amount_minor bigint not null,
  high_amount_minor bigint not null,
  variable_amount integer not null default 0 check (variable_amount in (0,1)),
  occurrences integer not null,
  first_seen_on text not null,
  last_seen_on text not null,
  next_expected_on text,
  confidence real not null check (confidence between 0 and 1),
  status text not null default 'candidate' check (status in ('candidate','confirmed','rejected')),
  created_at text not null,
  unique (profile_id, merchant_id, cadence, currency)
);

create table if not exists source_citation (
  id text primary key,
  url text not null,
  retrieved_at text not null,
  review_status text not null check (review_status in ('proposed','reviewed')),
  reviewer text not null,
  content_sha256 text
);

create table if not exists provider (
  id text primary key,
  name text not null,
  category text not null,
  homepage text
);

create table if not exists catalogue_plan (
  id text primary key,
  provider_id text not null references provider(id) on delete cascade,
  name text not null,
  capability text not null,
  storage_gb integer,
  seats integer
);

create table if not exists plan_offer (
  id text primary key,
  plan_id text not null references catalogue_plan(id) on delete cascade,
  region text not null,
  currency text not null references currency(code),
  exponent integer not null,
  billing_period text not null check (billing_period in ('monthly','annual','quarterly')),
  price_minor bigint not null check (price_minor >= 0),
  tax_inclusive integer not null check (tax_inclusive in (0,1)),
  valid_from text not null,
  valid_to text,
  citation_id text not null references source_citation(id),
  unique (plan_id, region, currency, billing_period, valid_from)
);
create index if not exists ix_offer_plan on plan_offer(plan_id, region);

create table if not exists subscription (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  series_id text references recurring_series(id) on delete set null,
  plan_offer_id text references plan_offer(id) on delete set null,
  display_name text not null,
  state text not null check (state in ('trial','active','paused','cancelled')),
  started_on text,
  trial_ends_on text,
  current_period_end text,
  cancel_at text,
  seats integer not null default 1,
  created_at text not null,
  unique (series_id)
);

create table if not exists recommendation (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  kind text not null,
  title text not null,
  detail text not null,
  savings_class text not null check (savings_class in ('potential','estimated','confirmed','realized')),
  amount_minor bigint,
  currency text,
  exponent integer,
  confidence real not null check (confidence between 0 and 1),
  evidence_json text not null,
  assumptions_json text not null,
  catalogue_snapshot text,
  status text not null default 'open' check (status in ('open','accepted','dismissed')),
  created_at text not null
);
create index if not exists ix_recommendation_profile on recommendation(profile_id, created_at);

create table if not exists savings_event (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  kind text not null check (kind in ('potential','estimated','confirmed','realized')),
  amount_minor bigint not null,
  currency text not null references currency(code),
  exponent integer not null,
  period_start text,
  period_end text,
  evidence_json text not null,
  created_at text not null
);
create index if not exists ix_savings_profile on savings_event(profile_id, created_at);

create table if not exists rule_trace (
  id text primary key,
  subject_kind text not null,
  subject_id text not null,
  payload_json text not null,
  created_at text not null
);

-- Records every byte that left the machine. Populated by the optional AI module only.
create table if not exists egress_log (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  destination text not null,
  model text,
  fields text not null,
  byte_count bigint not null,
  created_at text not null
);

create table if not exists audit_log (
  id text primary key,
  profile_id text,
  action text not null,
  subject text,
  created_at text not null
);
