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
| Menu à un tap (icône maison ou geste retour) : arbre, « Ce soir », Relire, Faire | Fait |
| Terrier : arbre qui pousse (saisons, fruits, feuilles du soir) et graphe en toile libre | Fait |
| Cœur sur chaque carte, coups de cœur, carnet, soirées passées, recherche, stats | Fait (local) |
| Autre porte : les restantes ou 3 portes neuves (action `bonus`), 3 par soir au plus | Fait |
| Voisins d'un concept et « prolonger demain » (action `voisins`, colonnes `prolonger`) | Fait |
| Longueur des cartes réglable, ajustée après 3 « trop dense » en 7 jours | Fait |
| Boîte à curiosités : saisie et menu de partage Android | Fait |
| Note de la porte (1 à 10) au récap | Fait |
| Score d'intérêt : les notes de porte pondèrent la composition du lot et l'ordre des portes (`chevet-lot`) | Fait |
| Notes perso (menu) : « en faire une porte », « réponds-moi » (fonction `chevet-notes`, 3 par jour) | Fait |
| La note qui revient : une vieille note ressort au récap, une fois par semaine au plus | Fait |
| Onboarding (swipe de sujets, import de l'historique YouTube, fiche, niveaux, coucher) | Fait |
| Check-in du dimanche au récap (top / bof / lassant, et pourquoi), pris en compte par la composition | Fait |

Reporté en V2 : banque d'images de paysages, météo, version courte automatique, photo
dans la boîte à curiosités.

## Côté serveur

Chevet est hébergé dans le projet Supabase **Panier** : tables préfixées `chevet_`,
fonctions `chevet-lot` (lot du jour, réglages, autres portes, voisins, exploration), `chevet-creuser`, `chevet-notes` (réponds-moi, note qui revient) et `chevet-onboarding` (centres d'intérêt tirés de l'historique YouTube). La clé Groq est un secret de fonction
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
