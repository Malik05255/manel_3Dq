create or replace function public.enforce_manzili_cloud_revision_guard()
returns trigger
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
  expected_revision integer;
begin
  if tg_op = 'UPDATE' then
    begin
      expected_revision := nullif(new.plan ->> '_cloud_expected_revision', '')::integer;
    exception when others then
      expected_revision := null;
    end;

    if expected_revision is null or expected_revision <> old.revision then
      raise exception using
        errcode = '23505',
        message = 'manzili_revision_conflict',
        detail = format(
          'expected_revision=%s current_revision=%s',
          coalesce(expected_revision::text, 'null'),
          old.revision::text
        );
    end if;

    if new.revision <= old.revision then
      raise exception using
        errcode = '23505',
        message = 'manzili_revision_conflict',
        detail = format(
          'new_revision=%s current_revision=%s',
          new.revision::text,
          old.revision::text
        );
    end if;
  end if;

  new.plan := new.plan - '_cloud_expected_revision';
  return new;
end;
$$;

drop trigger if exists manzili_projects_revision_guard on public.manzili_projects;
create trigger manzili_projects_revision_guard
before insert or update on public.manzili_projects
for each row execute function public.enforce_manzili_cloud_revision_guard();
