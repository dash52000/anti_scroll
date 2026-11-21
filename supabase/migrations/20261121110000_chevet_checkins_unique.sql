-- Un seul check-in par semaine et par lecteur : l'appli l'écrit avec on_conflict=user_id,semaine.
create unique index if not exists chevet_checkins_user_semaine on public.chevet_checkins (user_id, semaine);
