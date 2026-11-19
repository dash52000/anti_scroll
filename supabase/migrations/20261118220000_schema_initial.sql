-- Chevet : schéma initial (spec V1, section « Données et journal d'utilisation »).
-- Chaque ligne appartient à un utilisateur ; la RLS limite l'accès à ses propres lignes.
-- Les fonctions serveur (génération du lot, creuser) utilisent la clé service et passent outre la RLS.

-- Profil : intérêts, niveaux, poids des thèmes, style, déclencheur, heure de coucher apprise.
create table public.profil (
  user_id uuid primary key default auth.uid() references auth.users on delete cascade,
  interets jsonb not null default '{}'::jsonb,
  niveaux jsonb not null default '{}'::jsonb,
  poids_themes jsonb not null default '{}'::jsonb,
  style jsonb not null default '{}'::jsonb,
  a_eviter text[] not null default '{}',
  declencheur text,
  heure_coucher time not null default '22:30',
  mis_a_jour_le timestamptz not null default now()
);

-- Concepts : les nœuds du terrier.
create table public.concepts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  titre text not null,
  theme text not null,
  wikipedia_url text,
  a_retenir text,
  a_raconter text,
  qcm jsonb,
  decouvert_le timestamptz not null default now(),
  unique (user_id, wikipedia_url)
);

-- Liens : les arêtes du graphe entre concepts.
create table public.liens (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  source_id uuid not null references public.concepts on delete cascade,
  cible_id uuid not null references public.concepts on delete cascade,
  primary key (source_id, cible_id)
);

-- Lots : un lot par jour, généré le matin, remplacé le lendemain.
create table public.lots (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  jour date not null,
  contenu jsonb not null,
  genere_le timestamptz not null default now(),
  consomme_le timestamptz,
  unique (user_id, jour)
);

-- Révisions : état de répétition espacée par concept.
create table public.revisions (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  concept_id uuid not null references public.concepts on delete cascade,
  intervalle_jours int not null default 1,
  echeance date not null default (current_date + 1),
  repetitions int not null default 0,
  primary key (user_id, concept_id)
);

-- Boîte à curiosités : partages et saisies de la journée.
create table public.curiosites (
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
create table public.journal (
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
create index journal_user_horodatage on public.journal (user_id, horodatage);

-- Check-in du dimanche.
create table public.checkins (
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
  foreach t in array array['profil', 'concepts', 'liens', 'lots', 'revisions', 'curiosites', 'journal', 'checkins']
  loop
    execute format('alter table public.%I enable row level security', t);
    execute format(
      'create policy proprietaire on public.%I for all to authenticated '
      'using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))',
      t
    );
  end loop;
end
$$;
