create table if not exists public.manzili_reader_learning_cases (
  id text primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  candidate_id text not null,
  object_path text not null unique,
  reader_model text not null,
  city text not null,
  region text not null,
  project_type text not null,
  floors integer not null check (floors between 1 and 20),
  consent_schema integer not null default 2,
  status text not null default 'uploaded-private'
    check (status in ('uploaded-private','accepted-training','withdrawn','trained')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.manzili_reader_learning_cases enable row level security;

create policy manzili_reader_learning_select_own
  on public.manzili_reader_learning_cases for select to authenticated
  using (auth.uid() = user_id);
create policy manzili_reader_learning_insert_own
  on public.manzili_reader_learning_cases for insert to authenticated
  with check (auth.uid() = user_id);
create policy manzili_reader_learning_delete_own
  on public.manzili_reader_learning_cases for delete to authenticated
  using (auth.uid() = user_id);

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('manzili-reader-learning-private', 'manzili-reader-learning-private', false, 26214400, array['application/zip'])
on conflict (id) do nothing;

create policy manzili_learning_storage_insert_own
  on storage.objects for insert to authenticated
  with check (
    bucket_id = 'manzili-reader-learning-private'
    and (storage.foldername(name))[1] = auth.uid()::text
  );
create policy manzili_learning_storage_select_own
  on storage.objects for select to authenticated
  using (
    bucket_id = 'manzili-reader-learning-private'
    and (storage.foldername(name))[1] = auth.uid()::text
  );
create policy manzili_learning_storage_delete_own
  on storage.objects for delete to authenticated
  using (
    bucket_id = 'manzili-reader-learning-private'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

create index if not exists manzili_reader_learning_cases_user_created_idx
  on public.manzili_reader_learning_cases(user_id, created_at desc);
