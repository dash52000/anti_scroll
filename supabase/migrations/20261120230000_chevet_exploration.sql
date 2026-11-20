-- Chevet : exploration (spec V1, section « Exploration et ergonomie »).
-- chevet_crans : « plus loin » et « à côté » depuis un concept ou un cran, une ligne par concept de
--                départ et par jour, préparée étape par étape par chevet-lot (action « crans »),
--                avec son propre verrou pour ne pas bloquer le lot ni les portes bonus.
-- chevet_marque_page : l'endroit où le lecteur s'est arrêté ; le lot du lendemain en fait la reprise.
-- chevet_notes_portes : la note de 1 à 10 donnée à une porte dans le récap ; 8 ou plus propose sa
--                suite dans le lot suivant (suite_servie passe alors à vrai).

create table public.chevet_crans (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users on delete cascade,
  jour date not null,
  depuis text not null,
  court boolean not null default false,
  statut text not null default 'en_cours' check (statut in ('en_cours', 'pret', 'erreur')),
  etape int not null default 0,
  travail jsonb not null default '{}'::jsonb,
  contenu jsonb not null default '{}'::jsonb,
  erreur text,
  verrou_jusqua timestamptz,
  cree_le timestamptz not null default now(),
  unique (user_id, jour, depuis)
);

create table public.chevet_marque_page (
  user_id uuid primary key references auth.users on delete cascade,
  porte_titre text,
  dernier jsonb,
  suite jsonb not null default '[]'::jsonb,
  utilise boolean not null default false,
  cree_le timestamptz not null default now()
);

create table public.chevet_notes_portes (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  jour date not null,
  porte_id text not null,
  porte_titre text not null,
  theme text,
  type text,
  note int not null check (note between 1 and 10),
  suite_servie boolean not null default false,
  cree_le timestamptz not null default now(),
  primary key (user_id, jour, porte_id)
);

-- RLS : chacun ne voit et ne modifie que ses lignes (les fonctions serveur passent outre).
do $$
declare
  t text;
begin
  foreach t in array array['chevet_crans', 'chevet_marque_page', 'chevet_notes_portes']
  loop
    execute format('alter table public.%I enable row level security', t);
    execute format(
      'create policy chevet_proprietaire on public.%I for all to authenticated '
      'using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))',
      t
    );
  end loop;
end
$$;
