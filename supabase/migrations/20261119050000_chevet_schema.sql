-- Chevet : schéma (spec V1, section « Données et journal d'utilisation »).
-- Hébergé dans le projet Supabase « Panier » : toutes les tables sont préfixées chevet_.
-- RLS : chaque utilisateur ne voit que ses lignes. Les fonctions serveur (chevet-lot,
-- chevet-creuser) utilisent la clé service et passent outre la RLS.
-- Les lignes sans user_id (profil modèle, lot de test) sont invisibles pour les clients.

create extension if not exists pg_net;
create extension if not exists pg_cron;

-- Profil : intérêts, niveaux, style, sources, graines du premier soir.
create table public.chevet_profil (
  id uuid primary key default gen_random_uuid(),
  user_id uuid unique references auth.users on delete cascade,
  interets jsonb not null default '{}'::jsonb,
  niveaux jsonb not null default '{}'::jsonb,
  style jsonb not null default '{}'::jsonb,
  a_eviter text[] not null default '{}',
  graines jsonb not null default '[]'::jsonb,
  sources_rss jsonb not null default '[]'::jsonb,
  declencheur text,
  heure_coucher time not null default '22:30',
  mis_a_jour_le timestamptz not null default now()
);

-- Concepts : les nœuds du terrier.
create table public.chevet_concepts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade,
  slug text not null,
  titre text not null,
  theme text not null,
  wikipedia_url text,
  a_retenir text,
  a_raconter text,
  qcm jsonb,
  lot_jour date,
  decouvert_le timestamptz not null default now(),
  unique (user_id, slug)
);

-- Liens : les arêtes du graphe entre concepts.
create table public.chevet_liens (
  user_id uuid references auth.users on delete cascade,
  source_id uuid not null references public.chevet_concepts on delete cascade,
  cible_id uuid not null references public.chevet_concepts on delete cascade,
  primary key (source_id, cible_id)
);

-- Lots : un lot par jour, préparé le matin étape par étape, remplacé le lendemain.
create table public.chevet_lots (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade,
  jour date not null,
  statut text not null default 'composition'
    check (statut in ('composition', 'en_cours', 'pret', 'erreur')),
  etape int not null default 0,
  contenu jsonb not null default '{}'::jsonb,
  travail jsonb not null default '{}'::jsonb,
  erreur text,
  genere_le timestamptz not null default now(),
  pret_le timestamptz,
  consomme_le timestamptz,
  -- Verrou court pendant qu'une étape de génération tourne.
  verrou_jusqua timestamptz
);
create unique index chevet_lots_user_jour on public.chevet_lots (user_id, jour) nulls not distinct;

-- Révisions : état de répétition espacée par concept.
create table public.chevet_revisions (
  user_id uuid not null references auth.users on delete cascade,
  concept_id uuid not null references public.chevet_concepts on delete cascade,
  intervalle_jours int not null default 1,
  echeance date not null default (current_date + 1),
  repetitions int not null default 0,
  primary key (user_id, concept_id)
);

-- Boîte à curiosités : partages et saisies de la journée.
create table public.chevet_curiosites (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  texte text,
  url text,
  origine text not null check (origine in ('partage', 'saisie')),
  statut text not null default 'en_attente'
    check (statut in ('en_attente', 'porte_generee', 'utilisee', 'ecartee')),
  cree_le timestamptz not null default now()
);

-- Journal d'utilisation : jamais affiché dans l'appli, analysé en R.
create table public.chevet_journal (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  horodatage timestamptz not null default now(),
  evenement text not null,
  lot_jour date,
  concept_ref text,
  page int,
  duree_ms int,
  donnees jsonb not null default '{}'::jsonb
);
create index chevet_journal_user_horodatage on public.chevet_journal (user_id, horodatage);

-- Check-in du dimanche.
create table public.chevet_checkins (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  semaine date not null,
  ressenti text not null check (ressenti in ('top', 'bof', 'lassant')),
  raisons text[] not null default '{}',
  cree_le timestamptz not null default now(),
  unique (user_id, semaine)
);

-- RLS : chacun ne voit et ne modifie que ses lignes.
do $$
declare
  t text;
begin
  foreach t in array array[
    'chevet_profil', 'chevet_concepts', 'chevet_liens', 'chevet_lots',
    'chevet_revisions', 'chevet_curiosites', 'chevet_journal', 'chevet_checkins'
  ]
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
