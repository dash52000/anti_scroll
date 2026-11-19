-- Chevet : menu, terrier et cœur (spec V1, section « Menu, terrier et cœur »).
-- mots_par_carte, cartes_par_concept : longueur des cartes, réglée en 3 crans dans l'appli.
-- aime : au moins une carte du concept a reçu un cœur ; le concept passe en tête des
--        révisions (côté appli) et nourrit plus souvent les portes « prolongement ».
-- prolonger, prolonger_vers : « prolonger demain » depuis le graphe du terrier, éventuellement
--        vers un voisin précis ; remis à zéro quand le lot du matin l'a utilisé.
-- voisins : articles Wikipédia liés choisis par l'IA pour le graphe, gardés en cache.

alter table public.chevet_profil
  add column if not exists mots_par_carte int not null default 40
    check (mots_par_carte in (25, 40, 60)),
  add column if not exists cartes_par_concept text not null default '2-4'
    check (cartes_par_concept in ('1-2', '2-4', '4-6'));

alter table public.chevet_concepts
  add column if not exists aime boolean not null default false,
  add column if not exists prolonger boolean not null default false,
  add column if not exists prolonger_vers text,
  add column if not exists voisins jsonb;
