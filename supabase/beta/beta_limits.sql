-- Security4Her beta: limits on the whole table, not just per tester.
--
-- The public key can create new tester IDs freely, so the per-tester limit (600 events an hour) alone doesn't stop
-- someone flooding the table with thousands of fake testers. These add two global caps:
--   * at most 300 new testers an hour (a college beta adds a few dozen a day)
--   * at most 20,000 events an hour across everyone
-- Run once in Supabase: SQL Editor -> New query -> paste -> Run. Safe to run again.

create index if not exists beta_consents_created on public.beta_consents (accepted_at);
create index if not exists beta_events_received on public.beta_events (received_at);

create or replace function public.beta_global_tester_limit() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if exists (select 1 from beta_consents where tester = new.tester) then return new; end if; -- an existing tester updating choices
  if (select count(*) from beta_consents where accepted_at > now() - interval '1 hour') >= 300 then
    raise exception 'too many new testers right now' using errcode = 'P0001';
  end if;
  return new;
end $$;

drop trigger if exists beta_global_tester_limit on public.beta_consents;
create trigger beta_global_tester_limit before insert on public.beta_consents
  for each row execute function public.beta_global_tester_limit();

create or replace function public.beta_global_event_limit() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if (select count(*) from beta_events where received_at > now() - interval '1 hour') >= 20000 then
    raise exception 'beta table is busy' using errcode = 'P0001';
  end if;
  return new;
end $$;

drop trigger if exists beta_global_event_limit on public.beta_events;
create trigger beta_global_event_limit before insert on public.beta_events
  for each row execute function public.beta_global_event_limit();
