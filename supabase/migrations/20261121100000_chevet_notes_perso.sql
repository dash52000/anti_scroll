-- Chevet : notes perso (spec V1, « Notes perso » et « La note qui revient »).
-- chevet_notes : une note libre du lecteur. « Réponds-moi » écrit la réponse dans la note
--                (suite = 'reponse') ; « en faire une porte » la dépose dans la boîte à curiosités
--                (suite = 'porte'). rappelee_le : dernière fois que le récap l'a ressortie.

create table public.chevet_notes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  texte text not null check (length(trim(texte)) > 0),
  suite text not null default 'aucune' check (suite in ('aucune', 'porte', 'reponse')),
  reponse text,
  reponse_le timestamptz,
  rappelee_le timestamptz,
  cree_le timestamptz not null default now()
);

create index chevet_notes_user_date on public.chevet_notes (user_id, cree_le desc);

-- RLS : chacun ne voit et ne modifie que ses lignes (les fonctions serveur passent outre).
alter table public.chevet_notes enable row level security;
create policy chevet_proprietaire on public.chevet_notes for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- Une curiosité peut venir d'une note perso.
alter table public.chevet_curiosites drop constraint if exists chevet_curiosites_origine_check;
alter table public.chevet_curiosites
  add constraint chevet_curiosites_origine_check check (origine in ('partage', 'saisie', 'note'));
