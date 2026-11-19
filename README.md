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
| Creuser (fonction `chevet-creuser`, gpt-oss-20b, 1 à 10 par soir, 3 par défaut) | Fait |
| Réglages (engrenage du fil) : soirée, thèmes, niveaux, sujets à éviter, sources d'actu, creuser, « Ce que Chevet sait de moi » | Fait |
| Régénération en arrière-plan après un changement de contenu (lot entier ou suite du fil) | Fait |
| Notif du soir, 1 h avant le coucher appris sur les ouvertures | Fait (version simple) |
| Connexion | Anonyme et automatique au premier lancement |
| Journal d'utilisation | Local puis envoyé dans `chevet_journal` |
| Terrier | Concepts explorés envoyés dans `chevet_concepts` |
| Répétition espacée des QCM | Locale |
| Onboarding, carnet, graphe du terrier | À faire |

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
  Rappel.kt                notif du soir (alarme, redémarrage du téléphone)
  model/Lot.kt             modèle du lot du jour (portes, concepts, cartes, QCM)
  data/                    Supabase, lot d'exemple, journal, progression (révisions),
                           profil réglable, soirée (heure de coucher, notif)
  ui/                      ciel, écrans du rituel, réglages, thème
app/src/main/assets/lot_exemple.json   le best of du premier soir
supabase/migrations/       schéma de la base et tâche du matin
supabase/functions/        génération du lot et « creuser »
.github/workflows/build.yml            build de l'APK
```

## Outils de test

Sept taps sur le numéro de version, en bas des réglages, ouvrent les outils de test :
« Rejouer le lot » remet le lot à zéro et rend les révisions dues tout de suite (pour
tester l'échauffement sans attendre le lendemain), « Forcer la régénération du lot »
recompose le lot du jour côté serveur, et l'état de la synchro Supabase s'affiche.
Une notif de test part aussi de là.
