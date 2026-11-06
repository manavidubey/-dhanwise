-- V2: Adds user assets (cards, telecom tiers, memberships), bundled entitlements,
-- rich plan metadata (resolution, screens, ads, headroom), recommendation states,
-- and accurate savings lifecycle tracking.

-- User-held assets that grant bundled perks or memberships
create table if not exists user_asset (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  asset_type text not null check (asset_type in ('credit_card', 'telecom_plan', 'membership', 'bank_account')),
  name text not null,
  issuer text,
  tier text,
  billing_cycle text check (billing_cycle in ('monthly', 'annual', 'lifetime', 'free')),
  annual_fee_minor bigint not null default 0,
  currency text not null references currency(code),
  status text not null default 'active' check (status in ('active', 'cancelled', 'expired')),
  valid_until text,
  created_at text not null
);
create index if not exists ix_user_asset_profile on user_asset(profile_id);

-- Bundled benefits offered by plans or held assets
create table if not exists bundled_benefit (
  id text primary key,
  source_type text not null check (source_type in ('plan', 'asset')),
  source_id text not null, -- references catalogue_plan(id) or catalogue asset definition
  capability text not null, -- e.g. 'video', 'music', 'storage', 'lounge', 'delivery'
  target_provider_id text, -- e.g. 'disney-hotstar', 'amazon-prime', or external service
  target_plan_id text, -- e.g. 'hotstar/mobile'
  description text not null,
  activation_required integer not null default 0 check (activation_required in (0, 1)),
  status text not null default 'AVAILABLE_TO_CLAIM'
    check (status in ('AVAILABLE_TO_CLAIM', 'ACTIVATION_REQUIRED', 'ACTIVE', 'EXPIRED', 'ELIGIBILITY_UNCONFIRMED')),
  validity_days integer,
  terms_url text,
  created_at text not null
);
create index if not exists ix_benefit_capability on bundled_benefit(capability);

-- User-linked claims or active instances of bundled perks
create table if not exists user_entitlement (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  user_asset_id text references user_asset(id) on delete cascade,
  bundled_benefit_id text not null references bundled_benefit(id) on delete cascade,
  status text not null default 'AVAILABLE_TO_CLAIM'
    check (status in ('AVAILABLE_TO_CLAIM', 'ACTIVATION_REQUIRED', 'ACTIVE', 'EXPIRED', 'DISMISSED')),
  activated_on text,
  expires_on text,
  notes text,
  created_at text not null
);
create index if not exists ix_entitlement_profile on user_entitlement(profile_id);

-- Savings lifecycle state on subscriptions:
-- ACTIVE -> CANCELLATION_REQUESTED -> USER_CONFIRMED_CANCELLED -> CHARGE_STOPPED -> REALIZED_SAVINGS
alter table subscription add column cancellation_stage text not null default 'NONE';
alter table subscription add column cancellation_requested_at text;
alter table subscription add column confirmed_cancelled_at text;
alter table subscription add column stopped_verified_at text;

-- Recommendation action state enhancements:
-- status in ('open', 'accepted', 'dismissed', 'snoozed', 'irrelevant')
-- SQLite doesn't easily alter check constraints, so we track snooze_until and feedback in separate columns
alter table recommendation add column snoozed_until text;
alter table recommendation add column action_taken text;
alter table recommendation add column action_taken_at text;
alter table recommendation add column loss_summary text;

-- Catalogue plan feature attributes (for honest plan comparison)
alter table catalogue_plan add column max_resolution text; -- '4K', '1080p', '720p'
alter table catalogue_plan add column simultaneous_screens integer default 1;
alter table catalogue_plan add column has_ads integer default 0;
alter table catalogue_plan add column family_sharing integer default 0;
alter table catalogue_plan add column loss_tradeoffs text; -- JSON array of trade-offs if downgraded or dropped
