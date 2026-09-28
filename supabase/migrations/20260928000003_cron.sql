-- Scheduled housekeeping. pg_cron is available on Supabase; skipped where it isn't (local tests).
do $outer$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron;
    perform cron.schedule('s4h-expire-incidents', '* * * * *', 'select public.expire_incidents()');
    perform cron.schedule('s4h-refresh-danger-cells', '17 * * * *', 'select public.refresh_danger_cells()');
  else
    raise notice 'pg_cron not available: incident expiry and danger-cell refresh are not scheduled';
  end if;
end
$outer$;
