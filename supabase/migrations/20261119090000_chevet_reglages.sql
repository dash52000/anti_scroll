-- Chevet : réglages (spec V1, section « Réglages »).
-- poids_themes : curseur de 0 à 100 par thème, lu par la composition du lot.
-- fiche : « Ce que Chevet sait de moi », des puces rangées par rubrique. Remplie au premier
--         passage par la fonction chevet-lot (action « profil ») à partir de interets et style.
-- creusages_par_soir : quota du bouton « creuser », vérifié par la fonction chevet-creuser.
-- Les sources RSS gardent leur colonne : chaque entrée gagne un champ facultatif « actif ».

alter table public.chevet_profil
  add column if not exists poids_themes jsonb not null default
    '{"Sciences & techno": 50, "Histoire & sociétés": 50, "Stats, maths, data": 50, "Philo & idées": 50}'::jsonb,
  add column if not exists fiche jsonb not null default '[]'::jsonb,
  add column if not exists creusages_par_soir int not null default 3
    check (creusages_par_soir between 1 and 10);
