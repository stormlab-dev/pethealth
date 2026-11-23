-- CuteFamilyPet cloud schema (Supabase / Postgres).
-- Mirrors the Android Room schema. Every row carries updated_at + deleted_at so clients can
-- sync incrementally ("give me everything changed since T") and propagate deletes.
-- Access: a pet is visible to everyone in pet_members (owner, family, pet sitter).

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------- helpers

create or replace function set_updated_at() returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end $$;

-- ---------------------------------------------------------------- pets + sharing

create table pets (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references auth.users(id) on delete cascade default auth.uid(),
  name          text not null,
  species       text not null check (species in ('DOG','CAT','RABBIT','BIRD','REPTILE','OTHER')),
  breed         text,
  sex           text not null default 'UNKNOWN' check (sex in ('MALE','FEMALE','UNKNOWN')),
  neutered      boolean not null default false,
  birth_date    date,
  microchip_id  text,
  notes         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz
);

create table pet_members (
  pet_id   uuid not null references pets(id) on delete cascade,
  user_id  uuid not null references auth.users(id) on delete cascade,
  role     text not null default 'caregiver' check (role in ('owner','caregiver','viewer')),
  primary key (pet_id, user_id)
);

create or replace function add_owner_membership() returns trigger language plpgsql security definer as $$
begin
  insert into pet_members (pet_id, user_id, role) values (new.id, new.owner_id, 'owner') on conflict do nothing;
  return new;
end $$;

create trigger pets_owner_member after insert on pets for each row execute function add_owner_membership();

create or replace function is_pet_member(p uuid) returns boolean language sql stable security definer as $$
  select exists (select 1 from pet_members m where m.pet_id = p and m.user_id = auth.uid());
$$;

create or replace function can_edit_pet(p uuid) returns boolean language sql stable security definer as $$
  select exists (select 1 from pet_members m where m.pet_id = p and m.user_id = auth.uid() and m.role in ('owner','caregiver'));
$$;

-- ---------------------------------------------------------------- health logs

create table symptom_entries (
  id          uuid primary key default gen_random_uuid(),
  pet_id      uuid not null references pets(id) on delete cascade,
  symptoms    text[] not null,             -- Symptom.key values
  severity    text not null check (severity in ('MILD','MODERATE','SEVERE')),
  started_at  timestamptz not null,
  logged_at   timestamptz not null default now(),
  notes       text,
  urgency     text,                        -- Urgency given at the time
  created_by  uuid default auth.uid(),
  updated_at  timestamptz not null default now(),
  deleted_at  timestamptz
);

create table weights (
  id          uuid primary key default gen_random_uuid(),
  pet_id      uuid not null references pets(id) on delete cascade,
  kilograms   numeric(6,2) not null check (kilograms > 0),
  date        date not null,
  updated_at  timestamptz not null default now(),
  deleted_at  timestamptz
);

create table vaccinations (
  id                uuid primary key default gen_random_uuid(),
  pet_id            uuid not null references pets(id) on delete cascade,
  name              text not null,
  administered_on   date not null,
  valid_for_months  int,
  clinic_name       text,
  source_record_id  text,                  -- set when imported from a clinic
  updated_at        timestamptz not null default now(),
  deleted_at        timestamptz
);

create table medications (
  id                        uuid primary key default gen_random_uuid(),
  pet_id                    uuid not null references pets(id) on delete cascade,
  name                      text not null,
  dosage                    text not null default '',
  schedule_type             text not null check (schedule_type in ('DAILY','EVERY_N_DAYS','AS_NEEDED')),
  times                     time[] not null default '{}',
  interval_days             int not null default 1,
  start_date                date not null,
  end_date                  date,
  units_remaining           numeric,
  units_per_dose            numeric not null default 1,
  reminders_enabled         boolean not null default true,
  pharmacy_prescription_id  text,
  pharmacy_provider_id      text,
  updated_at                timestamptz not null default now(),
  deleted_at                timestamptz
);

create table dose_logs (
  id             uuid primary key default gen_random_uuid(),
  medication_id  uuid not null references medications(id) on delete cascade,
  scheduled_for  timestamptz not null,
  taken_at       timestamptz,
  skipped        boolean not null default false,
  given_by       uuid default auth.uid(),   -- who gave it: handy when a family shares a pet
  updated_at     timestamptz not null default now(),
  deleted_at     timestamptz
);

-- ---------------------------------------------------------------- integrations

create table clinic_links (
  pet_id           uuid primary key references pets(id) on delete cascade,
  provider_id      text not null,
  clinic_id        text not null,
  clinic_name      text not null,
  patient_id       text not null,
  last_synced_at   timestamptz,
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz
);

create table appointments (
  id            uuid primary key default gen_random_uuid(),
  external_id   text not null,
  pet_id        uuid not null references pets(id) on delete cascade,
  provider_id   text not null,
  clinic_id     text not null,
  clinic_name   text not null,
  starts_at     timestamp not null,          -- clinic local time
  minutes       int not null,
  reason        text not null,
  status        text not null,
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  unique (provider_id, external_id)
);

create table refill_orders (
  id               uuid primary key default gen_random_uuid(),
  external_id      text not null,
  medication_id    uuid not null references medications(id) on delete cascade,
  provider_id      text not null,
  prescription_id  text not null,
  state            text not null,
  placed_at        timestamptz not null,
  message          text,
  tracking_url     text,
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz,
  unique (provider_id, external_id)
);

-- Partner OAuth tokens live server-side only; clients never read this table.
create table partner_accounts (
  user_id        uuid not null references auth.users(id) on delete cascade,
  provider_id    text not null,
  account_id     text not null,
  display_name   text,
  access_token   text,       -- encrypt with Supabase Vault / pgsodium in production
  refresh_token  text,
  expires_at     timestamptz,
  primary key (user_id, provider_id)
);

-- ---------------------------------------------------------------- triggers + indexes

do $$
declare t text;
begin
  foreach t in array array['pets','symptom_entries','weights','vaccinations','medications','dose_logs','clinic_links','appointments','refill_orders']
  loop
    execute format('create trigger %I_updated before update on %I for each row execute function set_updated_at()', t, t);
    execute format('create index %I_updated_idx on %I (updated_at)', t, t);
  end loop;
end $$;

create index symptom_entries_pet_idx on symptom_entries (pet_id, logged_at desc);
create index weights_pet_idx on weights (pet_id, date);
create index vaccinations_pet_idx on vaccinations (pet_id);
create index medications_pet_idx on medications (pet_id);
create index dose_logs_med_idx on dose_logs (medication_id, scheduled_for desc);

-- ---------------------------------------------------------------- row level security

alter table pets enable row level security;
alter table pet_members enable row level security;
alter table symptom_entries enable row level security;
alter table weights enable row level security;
alter table vaccinations enable row level security;
alter table medications enable row level security;
alter table dose_logs enable row level security;
alter table clinic_links enable row level security;
alter table appointments enable row level security;
alter table refill_orders enable row level security;
alter table partner_accounts enable row level security;  -- no policies: service role only

create policy pets_read on pets for select using (is_pet_member(id));
create policy pets_insert on pets for insert with check (owner_id = auth.uid());
create policy pets_update on pets for update using (can_edit_pet(id));
create policy pets_delete on pets for delete using (owner_id = auth.uid());

create policy members_read on pet_members for select using (is_pet_member(pet_id));
create policy members_manage on pet_members for all
  using (exists (select 1 from pets p where p.id = pet_id and p.owner_id = auth.uid()))
  with check (exists (select 1 from pets p where p.id = pet_id and p.owner_id = auth.uid()));

-- Child tables keyed directly by pet_id.
do $$
declare t text;
begin
  foreach t in array array['symptom_entries','weights','vaccinations','medications','clinic_links','appointments']
  loop
    execute format('create policy %I_read on %I for select using (is_pet_member(pet_id))', t, t);
    execute format('create policy %I_write on %I for all using (can_edit_pet(pet_id)) with check (can_edit_pet(pet_id))', t, t);
  end loop;
end $$;

-- Tables keyed by medication.
create policy dose_logs_read on dose_logs for select
  using (is_pet_member((select pet_id from medications m where m.id = medication_id)));
create policy dose_logs_write on dose_logs for all
  using (can_edit_pet((select pet_id from medications m where m.id = medication_id)))
  with check (can_edit_pet((select pet_id from medications m where m.id = medication_id)));
create policy refill_orders_read on refill_orders for select
  using (is_pet_member((select pet_id from medications m where m.id = medication_id)));
create policy refill_orders_write on refill_orders for all
  using (can_edit_pet((select pet_id from medications m where m.id = medication_id)))
  with check (can_edit_pet((select pet_id from medications m where m.id = medication_id)));
