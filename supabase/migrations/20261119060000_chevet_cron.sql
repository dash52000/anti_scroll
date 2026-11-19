-- Chevet : préparation du lot du matin.
-- Toutes les 5 minutes entre 4 h et 6 h 55 UTC (5 h à 7 h 55 à Paris en hiver), la fonction
-- chevet-lot fait avancer d'une étape le lot de chaque utilisateur. Un lot prêt ne bouge plus.
-- La clé utilisée est la clé publique (anon) : la fonction est idempotente, un appel en trop ne coûte rien.

select cron.schedule('chevet-lot-matin', '*/5 4-6 * * *', $cron$
  select net.http_post(
    url := 'https://iolmjlqcvipzsihvrxlj.supabase.co/functions/v1/chevet-lot',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlvbG1qbHFjdmlwenNpaHZyeGxqIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTMxNDgzNDMsImV4cCI6MjEwODcyNDM0M30.WVYQtlX69QpBYjsIUnH2srL0bSpavVM3dO9pH6mAv0I'
    ),
    body := '{"action":"cron"}'::jsonb,
    timeout_milliseconds := 150000
  );
$cron$);
