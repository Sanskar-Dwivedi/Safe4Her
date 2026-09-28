-- pgTAP tests for the Security4Her schema and RPCs.
-- Run locally with scripts/test_db.sh (plain PostGIS + the Supabase stub).
begin;
set local search_path = public, extensions;
create extension if not exists pgtap;
select plan(39);

-- Users: A, D, E verified; B unverified.
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'asha@example.com',  '{"display_name":"Asha Verma"}'),
  ('00000000-0000-0000-0000-00000000000b', 'bina@example.com',  '{}'),
  ('00000000-0000-0000-0000-00000000000d', 'divya@example.com', '{"display_name":"Divya"}'),
  ('00000000-0000-0000-0000-00000000000e', 'esha@example.com',  '{"display_name":"Esha"}');
update public.profiles set verified_female = true, verification_method = 'mock'
 where id in ('00000000-0000-0000-0000-00000000000a',
              '00000000-0000-0000-0000-00000000000d',
              '00000000-0000-0000-0000-00000000000e');

select is((select display_name from public.profiles where id = '00000000-0000-0000-0000-00000000000a'),
          'Asha Verma', 'profile created from auth trigger with display_name');
select is((select display_name from public.profiles where id = '00000000-0000-0000-0000-00000000000b'),
          'bina', 'display_name falls back to email local part');

-- Safe places for KNN (Connaught Place area, Delhi)
insert into public.safe_places (name, category, geog, verified) values
  ('Far Hospital',  'hospital', st_setsrid(st_makepoint(77.2300, 28.6500), 4326)::geography, true),
  ('Near Police',   'police',   st_setsrid(st_makepoint(77.2091, 28.6316), 4326)::geography, true),
  ('Mid Pharmacy',  'pharmacy', st_setsrid(st_makepoint(77.2150, 28.6350), 4326)::geography, false);

-- ============ unverified user B ============
set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000b';

select throws_ok($$ select * from public.upsert_incident('10000000-0000-0000-0000-000000000001', 'knock', 'phone') $$,
                 '42501', 'not verified', 'unverified user cannot create incidents');
select throws_ok($$ insert into public.trusted_contacts (name, phone) values ('Mom', '+911234567890') $$,
                 '42501', null, 'unverified user cannot add contacts (RLS)');
select throws_ok($$ select * from public.nearest_safe_places(28.63, 77.21) $$,
                 '42501', 'not verified', 'unverified user cannot use the map');
select throws_ok($$ update public.profiles set verified_female = true $$,
                 '42501', null, 'users cannot set verified_female directly');
select ok(public.mock_verify_female(), 'mock verification works in mock mode');
select ok((select verified_female from public.profiles), 'B is now verified (mock)');

-- ============ verified user A ============
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';

insert into public.trusted_contacts (name, phone, relation, priority) values
  ('Mom', '+911111111111', 'mother', 1),
  ('Priya', '+912222222222', 'friend', 2);
select is((select count(*)::int from public.trusted_contacts), 2, 'A sees her 2 contacts');
select throws_ok($$ update public.trusted_contacts set telegram_chat_id = 42 $$,
                 '42501', null, 'telegram_chat_id is server-managed');

select is((select created from public.upsert_incident(
            '10000000-0000-0000-0000-000000000001', 'knock', 'watch', 28.6315, 77.2167, 12, false)),
          true, 'first upsert creates the incident');
select is((select created from public.upsert_incident(
            '10000000-0000-0000-0000-000000000001', 'knock', 'watch', 28.6316, 77.2168, 10, false)),
          false, 'second upsert with same id is deduplicated');
select is((select count(*)::int from public.incidents), 1, 'exactly one incident row');
select is((select count(*)::int from public.incident_locations), 2, 'both fixes stored');
select lives_ok($$ select public.add_incident_location('10000000-0000-0000-0000-000000000001', 28.6317, 77.2169, 8) $$,
                'owner can add a location');

select results_eq(
  $$ select name from public.nearest_safe_places(28.6315, 77.2100, 3) $$,
  $$ values ('Near Police'::text), ('Mid Pharmacy'), ('Far Hospital') $$,
  'nearest_safe_places orders by distance');
select ok((select distance_m from public.nearest_safe_places(28.6315, 77.2100, 1)) between 50 and 200,
          'distance is in metres');

-- share token for later
create temp table t_share as
  select share_token from public.incidents where id = '10000000-0000-0000-0000-000000000001';
grant select on t_share to anon, authenticated;

-- ============ another verified user D ============
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000d';
select is((select count(*)::int from public.trusted_contacts), 0, 'D cannot see A''s contacts');
select is((select count(*)::int from public.incidents), 0, 'D cannot see A''s incidents');
select throws_ok($$ select * from public.upsert_incident('10000000-0000-0000-0000-000000000001', 'knock', 'phone') $$,
                 '42501', 'incident belongs to another user', 'D cannot hijack A''s incident id');
select throws_ok($$ select public.end_incident('10000000-0000-0000-0000-000000000001', 'resolved') $$,
                 'P0002', 'incident not found', 'D cannot end A''s incident');

-- ============ anon: live tracking ============
set local role anon;
select is((public.track((select share_token from t_share)) ->> 'status'), 'active', 'track shows active incident');
select is(jsonb_array_length(public.track((select share_token from t_share)) -> 'locations'), 3,
          'track returns the 3 locations');
select is((public.track((select share_token from t_share)) ->> 'name'), 'Asha', 'track shows first name only');
select is(public.track('not-a-real-token'), null, 'unknown token returns null');
select throws_ok($$ select * from public.upsert_incident('10000000-0000-0000-0000-000000000002', 'knock', 'phone') $$,
                 '42501', null, 'anon cannot call upsert_incident');
select throws_ok($$ select * from public.incidents $$, '42501', null, 'anon cannot read incidents');

-- ============ A ends the incident ============
set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
select lives_ok($$ select public.end_incident('10000000-0000-0000-0000-000000000001', 'resolved') $$, 'A resolves');
select public.add_incident_location('10000000-0000-0000-0000-000000000001', 28.7, 77.3, 5);
set local role anon;
select is((public.track((select share_token from t_share)) ->> 'status'), 'ended', 'track shows ended');
select is(jsonb_array_length(public.track((select share_token from t_share)) -> 'locations'), 0,
          'no locations exposed after the incident ends');

-- ============ watch direct path (service role) ============
set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
create temp table t_dev as select public.register_watch() as token;
grant select on t_dev to service_role;
set local role service_role;
select is((public.watch_sos((select token from t_dev), 'start', '20000000-0000-0000-0000-000000000001',
                             'shake', 28.64, 77.22, 9, now(), true) ->> 'created')::boolean,
          true, 'watch_sos start creates incident');
select is(jsonb_array_length(public.watch_sos((select token from t_dev), 'start', '20000000-0000-0000-0000-000000000001',
                             'shake', 28.64, 77.22, 9, now(), true) -> 'contacts'),
          2, 'watch_sos repeat is idempotent and returns SOS contacts');
select throws_ok($$ select public.watch_sos('wrong-token', 'start', gen_random_uuid()) $$,
                 '28000', 'invalid device token', 'bad device token rejected');

-- ============ danger cells: k-anonymity ============
reset role;
-- three distinct verified reporters at one spot, one reporter at another
do $$
declare u text;
begin
  foreach u in array array['00000000-0000-0000-0000-00000000000a',
                           '00000000-0000-0000-0000-00000000000d',
                           '00000000-0000-0000-0000-00000000000e'] loop
    perform set_config('request.jwt.claim.sub', u, true);
    set local role authenticated;
    perform public.report_unsafe(28.6200, 77.2000, 'poor_lighting', 'dark lane');
    reset role;
  end loop;
  perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
  set local role authenticated;
  perform public.report_unsafe(28.6000, 77.1800, 'harassment', null);
  reset role;
end $$;
set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000d';
select is((select count(*)::int from public.danger_cells_in_bbox(28.5, 77.1, 28.7, 77.3)), 1,
          'only the cell with >= 3 distinct reporters is shown');
select is((select reports from public.danger_cells_in_bbox(28.5, 77.1, 28.7, 77.3)), 3,
          'cell reports 3 reporters');

-- ============ report rate limit (A already has 2 reports today; limit 10) ============
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
select lives_ok($$ select public.report_unsafe(28.5 + g * 0.01, 77.0, 'other') from generate_series(1, 8) g $$,
                'A can file up to 10 reports a day');
select throws_ok($$ select public.report_unsafe(28.9, 77.0, 'other') $$,
                 '54000', null, '11th report in a day is rejected');

-- ============ expiry ============
reset role;
update public.incidents set expires_at = now() - interval '1 minute'
 where id = '20000000-0000-0000-0000-000000000001';
select is(public.expire_incidents(), 1, 'expire_incidents expires overdue incidents');
select is((select status from public.incidents where id = '20000000-0000-0000-0000-000000000001'),
          'expired', 'status is expired');

select * from finish();
rollback;
