-- Chevet : bascule automatique entre modèles (Gemini puis Groq) quand l'un sature.
-- chevet_llm_etat    : un modèle qui répond 429 ou 5xx est mis au repos pour quelques minutes, pour que les
--                      appels suivants (les fonctions n'ont pas de mémoire) passent directement au suivant.
-- chevet_llm_journal : une ligne par essai (modèle, résultat, durée), pour analyser en R au bout d'un mois.
-- Tables réservées aux fonctions serveur : RLS activée, aucune politique, donc invisibles pour l'appli.

create table public.chevet_llm_etat (
  modele text primary key,                 -- « fournisseur:modèle », ex. gemini:gemini-3.8-flash
  repos_jusqua timestamptz not null,
  dernier_statut integer,                  -- code HTTP ; 0 = délai dépassé ou erreur réseau
  motif text,
  mis_a_jour_le timestamptz not null default now()
);

create table public.chevet_llm_journal (
  id bigint generated always as identity primary key,
  horodatage timestamptz not null default now(),
  fonction text not null,                  -- chevet-lot, chevet-creuser, ...
  role text not null check (role in ('redaction', 'verification', 'leger')),
  fournisseur text not null,
  modele text not null,
  rang integer not null,                   -- place dans la chaîne : 1 = modèle préféré
  essai integer not null default 1,
  resultat text not null check (resultat in ('ok', 'repos', 'limite', 'indisponible', 'erreur', 'inutilisable')),
  statut integer,                          -- code HTTP ; 0 = délai dépassé ou erreur réseau
  duree_ms integer
);

create index chevet_llm_journal_date on public.chevet_llm_journal (horodatage desc);

alter table public.chevet_llm_etat enable row level security;
alter table public.chevet_llm_journal enable row level security;

-- Le journal garde 60 jours.
select cron.schedule('chevet-llm-purge', '17 3 * * 0', $cron$
  delete from public.chevet_llm_journal where horodatage < now() - interval '60 days';
$cron$);
