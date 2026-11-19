# Chevet

Appli Android anti-doomscrolling : le soir, au lit, elle remplace le scroll Facebook/YouTube
par un fil **borné** de petites leçons (3 concepts, puis récap et bonne nuit).

Spec complète : [Chevet — Spec V1](https://claude.ai/artifact/YPLLzfDEQkapQjjSUku3rd)

## Installer l'APK

Chaque push déclenche le workflow **Build APK**. L'APK est publié dans l'onglet
**Releases** du dépôt (pré-version `v0.1.N`) : ouvre la page depuis le téléphone et
télécharge le fichier `.apk`.

Toutes les versions sont signées avec la même clé de debug (`app/debug.keystore`),
donc une nouvelle version s'installe par-dessus l'ancienne sans désinstaller.

## Où en est-on

| Brique | État |
| --- | --- |
| Rituel du soir (échauffement, pépite, portes, fil, récap, bonne nuit) | Fait |
| Ciel du soir qui s'assombrit, étoiles au « bonne nuit » | Fait (dessiné par le code) |
| Lot du jour généré par Groq (fonction `chevet-lot`, préparé chaque matin par pg_cron) | Fait, lot d'exemple en secours |
| Creuser (fonction `chevet-creuser`, gpt-oss-20b, 3 par soir) | Fait |
| Connexion | Anonyme et automatique au premier lancement |
| Journal d'utilisation | Local puis envoyé dans `chevet_journal` |
| Terrier | Concepts explorés envoyés dans `chevet_concepts` |
| Répétition espacée des QCM | Locale |
| Notification du soir, onboarding, carnet, graphe du terrier | À faire |

Reporté en V2 : banque d'images de paysages, météo, version courte automatique, photo
dans la boîte à curiosités.

## Côté serveur

Chevet est hébergé dans le projet Supabase **Panier** : tables préfixées `chevet_`,
fonctions `chevet-lot` et `chevet-creuser`. La clé Groq est un secret de fonction
(`CHEVET_GROQ_API_KEY`), jamais dans l'APK. Le profil du lecteur vit dans la table
`chevet_profil`, pas dans ce dépôt.

## Structure

```
app/src/main/java/fr/dash/chevet/
  MainActivity.kt          point d'entrée, luminosité du soir, partage
  model/Lot.kt             modèle du lot du jour (portes, concepts, cartes, QCM)
  data/                    Supabase, lot d'exemple, journal, progression (révisions)
  ui/                      ciel, écrans du rituel, thème
app/src/main/assets/lot_exemple.json   le best of du premier soir
supabase/migrations/       schéma de la base et tâche du matin
supabase/functions/        génération du lot et « creuser »
.github/workflows/build.yml            build de l'APK
```

Le bouton « Rejouer (test) » du récap remet le lot à zéro et rend les révisions
dues tout de suite, pour tester l'échauffement sans attendre le lendemain.
