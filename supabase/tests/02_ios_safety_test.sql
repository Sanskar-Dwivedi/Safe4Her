begin;
set local search_path = public, extensions;
create extension if not exists pgtap;
select no_plan();
insert into auth.users(id,email,raw_user_meta_data) values
 ('00000000-0000-0000-0000-00000000001a','ios-a@example.com','{}'),
 ('00000000-0000-0000-0000-00000000001b','ios-b@example.com','{}');
update public.profiles set verified_female = true,verification_method = 'mock'
 where id in ('00000000-0000-0000-0000-00000000001a','00000000-0000-0000-0000-00000000001b');
insert into public.trusted_contacts(owner_id,name,phone,telegram_chat_id) values
 ('00000000-0000-0000-0000-00000000001a','Contact','+910000000000',42);
select public.ios_worker_heartbeat();
set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000001a';
select is((public.ios_safety('capabilities','{}')->>'eligibleRecipients')::int,1,'only linked eligible owner contacts counted');
select throws_ok($$select * from public.ios_terminal_sessions$$,'42501',null,'terminal records are private');
select throws_ok($$select public.ios_claim_alerts(null)$$,'42501',null,'client cannot claim delivery jobs');
select is(public.ios_safety('end','{"incidentId":"10000000-0000-0000-0000-00000000001a"}')->>'state','resolved','cancel before start is acknowledged');
select is(public.ios_safety('sos','{"incidentId":"10000000-0000-0000-0000-00000000001a","test":false}')->>'state','resolved','late SOS cannot resurrect cancellation');
select is(public.ios_safety('journeyEnd','{"journeyId":"20000000-0000-0000-0000-00000000001a"}')->>'state','ended','cancel before journey start creates terminal record');
select is(public.ios_safety('journeyStart',jsonb_build_object('journeyId','20000000-0000-0000-0000-00000000001a',
 'incidentId','10000000-0000-0000-0000-00000000002a','name','Home','deadline',now()+interval '30 minutes','test',false))->>'state','ended','late journey start stays ended');
select is(public.ios_safety('journeyStart',jsonb_build_object('journeyId','20000000-0000-0000-0000-00000000002a',
 'incidentId','10000000-0000-0000-0000-00000000003a','name','Home','deadline',now()+interval '30 minutes','test',false))->>'state','active','live journey armed');
select is((public.ios_safety('journeyExtend',jsonb_build_object('journeyId','20000000-0000-0000-0000-00000000002a',
 'deadline',now()+interval '50 minutes','revision',2))->>'revision')::int,2,'new extension accepted');
select is((public.ios_safety('journeyExtend',jsonb_build_object('journeyId','20000000-0000-0000-0000-00000000002a',
 'deadline',now()+interval '35 minutes','revision',1))->>'deadline')::timestamptz,now()+interval '50 minutes','reordered extension cannot shorten acknowledged deadline');
select is((public.ios_safety('journeyExtend',jsonb_build_object('journeyId','20000000-0000-0000-0000-00000000002a',
 'deadline',now()+interval '60 minutes'))->>'revision')::int,3,'legacy extension remains compatible');
reset role;
select is((select count(*)::int from public.ios_alert_jobs where event='journey_started'),1,'journey-start lifecycle deduplicated');
update public.ios_journeys set deadline=now()-interval '1 second' where id='20000000-0000-0000-0000-00000000002a';
create temp table claimed as select public.ios_claim_alerts(null) jobs;
select is((select state from public.ios_journeys where id='20000000-0000-0000-0000-00000000002a'),'alerted','deadline worker escalates');
select is(jsonb_array_length(public.ios_claim_alerts(null)),0,'active lease prevents duplicate claim');
select public.ios_finish_delivery((jobs->0->>'id')::uuid,gen_random_uuid(),true,60) from claimed;
select is((select count(*)::int from public.ios_alert_jobs where state='sent'),0,'wrong lease cannot acknowledge delivery');
update public.ios_alert_jobs set lease_until=now()-interval '1 second' where state='pending';
create temp table reclaimed as select public.ios_claim_alerts(null) jobs;
select public.ios_finish_delivery((jobs->0->>'id')::uuid,(jobs->0->>'lease')::uuid,true,60) from claimed;
select is((select count(*)::int from public.ios_alert_jobs where state='sent'),0,'expired worker cannot acknowledge a reclaimed job');
select public.ios_finish_delivery((jobs->0->>'id')::uuid,(jobs->0->>'lease')::uuid,true,60) from reclaimed;
select is((select count(*)::int from public.ios_alert_jobs where state='sent'),1,'matching lease can acknowledge delivery');
set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000001a';
select public.ios_safety('end','{"incidentId":"10000000-0000-0000-0000-00000000003a"}');
select is(public.ios_safety('journeyStatus','{"journeyId":"20000000-0000-0000-0000-00000000002a"}')->>'state','ended','resolving journey SOS also ends journey');
select is(public.ios_safety('journeyStatus','{"journeyId":"20000000-0000-0000-0000-00000000002a"}')->>'incidentState','resolved','linked incident status is explicit');
select public.ios_safety('end','{"incidentId":"10000000-0000-0000-0000-00000000003a"}');
select public.ios_safety('sos','{"incidentId":"10000000-0000-0000-0000-00000000004a","test":true}');
select public.ios_safety('end','{"incidentId":"10000000-0000-0000-0000-00000000004a"}');
select public.ios_safety('journeyStart',jsonb_build_object('journeyId','20000000-0000-0000-0000-00000000003a',
 'incidentId','10000000-0000-0000-0000-00000000005a','name','Practice','deadline',now()+interval '30 minutes','test',true));
select public.ios_safety('journeyEnd','{"journeyId":"20000000-0000-0000-0000-00000000003a","reason":"arrived"}');
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000001b';
select throws_ok($$select public.ios_safety('end','{"incidentId":"10000000-0000-0000-0000-00000000003a"}')$$,'P0001','incident belongs to another user','other owner cannot end incident');
select throws_ok($$select public.ios_safety('journeyEnd','{"journeyId":"20000000-0000-0000-0000-00000000002a"}')$$,'P0001','journey belongs to another user','other owner cannot end journey');
reset role;
select is((select count(*)::int from public.ios_alert_jobs where event='resolved'),1,'resolution lifecycle deduplicated');
select is((select count(*)::int from public.ios_alert_jobs where incident_id='10000000-0000-0000-0000-00000000004a'),0,'test SOS and resolution never enqueue contacts');
select is((select count(*)::int from public.ios_alert_jobs where journey_id='20000000-0000-0000-0000-00000000003a'),0,'test journey lifecycle never enqueues contacts');
set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-0000-0000-00000000001a';
select public.ios_safety('sos','{"incidentId":"10000000-0000-0000-0000-00000000006a","test":false}');
reset role;
create temp table before_cancel as select public.ios_claim_alerts(null) jobs;
set local role authenticated;
select public.ios_safety('end','{"incidentId":"10000000-0000-0000-0000-00000000006a"}');
reset role;
select public.ios_finish_delivery((job->>'id')::uuid,(job->>'lease')::uuid,true,60)
 from before_cancel,jsonb_array_elements(jobs) job where job->>'event'='sos';
select is((select state from public.ios_alert_jobs where incident_id='10000000-0000-0000-0000-00000000006a' and event='sos'),
 'cancelled','cancellation invalidates an in-flight lease acknowledgement');
select * from finish();
rollback;
