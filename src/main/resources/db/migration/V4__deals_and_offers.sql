-- V4: Deals & Offers Intelligence and auditable user lifecycle

create table if not exists deal_offer (
  id text primary key,
  merchant_id text,
  merchant_name text not null,
  title text not null,
  description text not null,
  category text not null check (category in (
    'food_and_dining', 'shopping_electronics', 'groceries', 
    'travel_commute', 'software_tech', 'telecom_utilities', 'memberships'
  )),
  offer_type text not null check (offer_type in (
    'percentage_discount', 'flat_discount', 'cashback', 
    'bogo', 'membership_benefit', 'free_trial'
  )),
  discount_pct real,
  discount_amount_minor bigint default 0,
  max_discount_minor bigint,
  min_spend_minor bigint default 0,
  currency text not null references currency(code),
  required_payment_method text,
  required_membership_id text,
  promo_code text,
  valid_from text not null,
  valid_to text,
  source_url text not null,
  terms text,
  verification_status text not null check (verification_status in ('verified', 'unverified', 'partner')),
  created_at text not null
);
create index if not exists ix_deal_category on deal_offer(category);
create index if not exists ix_deal_merchant on deal_offer(merchant_id);

create table if not exists user_deal_interaction (
  id text primary key,
  profile_id text not null references profile(id) on delete cascade,
  deal_id text not null references deal_offer(id) on delete cascade,
  status text not null check (status in ('saved', 'dismissed', 'redeemed', 'reported')),
  saved_at text,
  redeemed_at text,
  realized_savings_minor bigint default 0,
  user_note text,
  created_at text not null,
  unique (profile_id, deal_id)
);
create index if not exists ix_user_deal on user_deal_interaction(profile_id, status);
