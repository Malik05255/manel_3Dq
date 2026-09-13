create table if not exists public.manzili_projects (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null default 'مشروعي',
  revision integer not null default 1 check (revision > 0),
  plan jsonb not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists manzili_projects_user_updated_idx
  on public.manzili_projects (user_id, updated_at desc);

alter table public.manzili_projects enable row level security;

drop policy if exists "manzili_select_own" on public.manzili_projects;
create policy "manzili_select_own"
  on public.manzili_projects for select
  using (auth.uid() = user_id);

drop policy if exists "manzili_insert_own" on public.manzili_projects;
create policy "manzili_insert_own"
  on public.manzili_projects for insert
  with check (auth.uid() = user_id);

drop policy if exists "manzili_update_own" on public.manzili_projects;
create policy "manzili_update_own"
  on public.manzili_projects for update
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

drop policy if exists "manzili_delete_own" on public.manzili_projects;
create policy "manzili_delete_own"
  on public.manzili_projects for delete
  using (auth.uid() = user_id);

create or replace function public.set_manzili_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists manzili_projects_updated_at on public.manzili_projects;
create trigger manzili_projects_updated_at
before update on public.manzili_projects
for each row execute function public.set_manzili_updated_at();
