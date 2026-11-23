-- CuteFamilyPet: photos and videos, plans and purchases, AI checks, contests, contact form.
-- Run after 0001_init.sql (Supabase SQL editor, or `supabase db push`).

-- ---------------------------------------------------------------- plans and purchases
-- Entitlements are written only by the server (the billing-webhook function, using the service
-- role key), never by apps, so a modified app cannot grant itself Premium.

create table entitlements (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  plan        text not null default 'FREE' check (plan in ('FREE','PREMIUM','FAMILY')),
  period      text check (period in ('MONTHLY','YEARLY')),
  expires_at  timestamptz,
  in_trial    boolean not null default false,
  store       text check (store in ('PLAY_STORE','APP_STORE','STRIPE','PROMO')),
  updated_at  timestamptz not null default now()
);

create table purchases (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  product_id      text not null,              -- e.g. cfp.stickers.seasonal, cfp.reel.yearly
  store           text not null,
  transaction_id  text not null unique,
  pet_id          uuid references pets(id) on delete set null,
  status          text not null default 'PAID' check (status in ('PAID','FULFILLING','FULFILLED','REFUNDED')),
  created_at      timestamptz not null default now()
);

-- The plan in effect right now. Expired subscriptions fall back to FREE.
create or replace function current_plan(u uuid default auth.uid()) returns text language sql stable security definer as $$
  select coalesce((select plan from entitlements e where e.user_id = u and (e.expires_at is null or e.expires_at > now())), 'FREE');
$$;

-- Family plan: everyone sharing a pet with a Family subscriber gets Premium features for that pet.
create or replace function pet_plan(p uuid) returns text language sql stable security definer as $$
  select case
    when exists (select 1 from pet_members m where m.pet_id = p and current_plan(m.user_id) = 'FAMILY') then 'FAMILY'
    when exists (select 1 from pet_members m where m.pet_id = p and m.role = 'owner' and current_plan(m.user_id) = 'PREMIUM') then 'PREMIUM'
    else 'FREE' end;
$$;

alter table entitlements enable row level security;
alter table purchases enable row level security;
create policy entitlements_read on entitlements for select using (user_id = auth.uid());
create policy purchases_read on purchases for select using (user_id = auth.uid());

-- ---------------------------------------------------------------- photos and videos
-- Files live in the private Storage bucket "pet-media" under <pet_id>/<file>. Rows here hold the
-- metadata. Free plan: 200 items per pet owner and clips up to 15 s (see core Plans.kt).

insert into storage.buckets (id, name, public, file_size_limit)
values ('pet-media', 'pet-media', false, 209715200) -- 200 MB per file
on conflict (id) do nothing;

create table pet_media (
  id            uuid primary key default gen_random_uuid(),
  pet_id        uuid not null references pets(id) on delete cascade,
  uploaded_by   uuid not null references auth.users(id) on delete cascade default auth.uid(),
  kind          text not null check (kind in ('PHOTO','VIDEO')),
  storage_path  text not null,               -- <pet_id>/<uuid>.<ext> in bucket pet-media
  mime_type     text not null,
  duration_sec  numeric,                     -- videos only
  caption       text,
  taken_at      timestamptz not null default now(),
  is_health     boolean not null default false, -- attached to a symptom check
  symptom_entry_id uuid references symptom_entries(id) on delete set null,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz
);
create index on pet_media (pet_id, taken_at desc);
create trigger pet_media_updated before update on pet_media for each row execute function set_updated_at();

alter table pet_media enable row level security;
create policy pet_media_read on pet_media for select using (is_pet_member(pet_id));
create policy pet_media_write on pet_media for all using (can_edit_pet(pet_id)) with check (can_edit_pet(pet_id));

create or replace function enforce_media_quota() returns trigger language plpgsql security definer as $$
declare plan text := pet_plan(new.pet_id); n int;
begin
  if plan = 'FREE' then
    select count(*) into n from pet_media m join pets p on p.id = m.pet_id
      where p.owner_id = (select owner_id from pets where id = new.pet_id) and m.deleted_at is null;
    if n >= 200 then raise exception 'MEDIA_LIMIT: the free plan holds 200 photos and videos. Upgrade to Premium for unlimited.'; end if;
    if new.kind = 'VIDEO' and coalesce(new.duration_sec, 0) > 15 then
      raise exception 'CLIP_LIMIT: the free plan allows clips up to 15 seconds.';
    end if;
  elsif new.kind = 'VIDEO' and coalesce(new.duration_sec, 0) > 180 then
    raise exception 'CLIP_LIMIT: clips can be up to 3 minutes.';
  end if;
  return new;
end $$;
create trigger pet_media_quota before insert on pet_media for each row execute function enforce_media_quota();

-- Storage policies: members read, caregivers/owners write, paths start with the pet id.
create policy pet_media_obj_read on storage.objects for select
  using (bucket_id = 'pet-media' and is_pet_member(((storage.foldername(name))[1])::uuid));
create policy pet_media_obj_insert on storage.objects for insert
  with check (bucket_id = 'pet-media' and can_edit_pet(((storage.foldername(name))[1])::uuid));
create policy pet_media_obj_delete on storage.objects for delete
  using (bucket_id = 'pet-media' and can_edit_pet(((storage.foldername(name))[1])::uuid));

-- Family plan seat limit: at most 6 people per pet when the owner is on Family, 2 otherwise
-- (owner plus one), 2 on Premium as well.
create or replace function enforce_member_limit() returns trigger language plpgsql security definer as $$
declare plan text := pet_plan(new.pet_id); n int;
begin
  select count(*) into n from pet_members where pet_id = new.pet_id;
  if n >= (case when plan = 'FAMILY' then 6 else 2 end) then
    raise exception 'MEMBER_LIMIT: upgrade to Family to share with up to 6 people.';
  end if;
  return new;
end $$;
create trigger pet_members_limit before insert on pet_members for each row
  when (new.role <> 'owner') execute function enforce_member_limit();

-- ---------------------------------------------------------------- AI symptom checks
-- One row per AI check, written by the ai-triage function. Used for rate limits and history.
create table ai_checks (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  pet_id      uuid references pets(id) on delete set null,
  urgency     text not null,
  rule_urgency text not null,
  had_photo   boolean not null default false,
  created_at  timestamptz not null default now()
);
create index on ai_checks (user_id, created_at desc);
alter table ai_checks enable row level security;
create policy ai_checks_read on ai_checks for select using (user_id = auth.uid());

-- ---------------------------------------------------------------- Cutest Pet of the Week
create table contest_entries (
  id          uuid primary key default gen_random_uuid(),
  week        date not null default date_trunc('week', now())::date,
  media_id    uuid not null references pet_media(id) on delete cascade,
  pet_id      uuid not null references pets(id) on delete cascade,
  user_id     uuid not null references auth.users(id) on delete cascade default auth.uid(),
  boosted     boolean not null default false,  -- set by billing-webhook after a boost purchase
  created_at  timestamptz not null default now(),
  unique (week, pet_id)
);
create table contest_votes (
  entry_id  uuid not null references contest_entries(id) on delete cascade,
  user_id   uuid not null references auth.users(id) on delete cascade default auth.uid(),
  primary key (entry_id, user_id)
);
alter table contest_entries enable row level security;
alter table contest_votes enable row level security;
-- Entries are public to signed-in users while the contest runs.
create policy contest_read on contest_entries for select using (auth.uid() is not null);
create policy contest_enter on contest_entries for insert with check (user_id = auth.uid() and can_edit_pet(pet_id) and boosted = false);
create policy contest_withdraw on contest_entries for delete using (user_id = auth.uid());
create policy votes_read on contest_votes for select using (auth.uid() is not null);
create policy votes_cast on contest_votes for insert with check (user_id = auth.uid());
create policy votes_undo on contest_votes for delete using (user_id = auth.uid());

-- Voters may view a photo only while it is entered in the current or last week's contest.
create policy pet_media_contest_read on pet_media for select using (
  exists (select 1 from contest_entries e where e.media_id = pet_media.id and e.week >= (date_trunc('week', now()) - interval '7 days')::date));
create policy pet_media_obj_contest_read on storage.objects for select using (
  bucket_id = 'pet-media' and exists (
    select 1 from contest_entries e join pet_media m on m.id = e.media_id
    where m.storage_path = storage.objects.name and e.week >= (date_trunc('week', now()) - interval '7 days')::date));
create view contest_leaderboard with (security_invoker = true) as
  select e.id, e.week, e.pet_id, e.media_id, e.boosted, count(v.user_id) as votes
  from contest_entries e left join contest_votes v on v.entry_id = e.id
  group by e.id;

-- ---------------------------------------------------------------- website contact form
create table contact_messages (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (char_length(name) between 1 and 120),
  email       text not null check (char_length(email) between 3 and 200),
  topic       text not null default 'question',
  message     text not null check (char_length(message) between 1 and 5000),
  created_at  timestamptz not null default now()
);
alter table contact_messages enable row level security;
-- Anyone may send; nobody can read through the API. Read them in the Supabase dashboard.
create policy contact_insert on contact_messages for insert to anon, authenticated with check (true);
