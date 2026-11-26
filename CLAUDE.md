# Chevet (repo anti_scroll)

- Appli Android anti-doomscrolling de Damien, en Kotlin + Jetpack Compose. Interface et code en français.
- Spec de référence : https://claude.ai/artifact/YPLLzfDEQkapQjjSUku3rd (doc Claude).
- Pas de SDK Android dans les sessions cloud : le build se vérifie par GitHub Actions (`.github/workflows/build.yml`), qui publie l'APK en pré-version.
- Garder la même clé `app/debug.keystore` pour que les mises à jour s'installent par-dessus.
- IA : Gemini et Groq avec bascule automatique (429, 5xx, délai dépassé), module `supabase/functions/_shared/llm.ts`. Trois rôles : rédaction (Gemini 3.8 Flash, 4 Hydrogen, 3 Flash preview, puis gpt-oss-120b, puis Gemini de secours), vérification (gpt-oss-120b puis les Gemini principaux), léger (gpt-oss-20b puis Gemini flash-lite). Chaque modèle Gemini a son quota gratuit : la liste est dans `chaine()`, `CHEVET_GEMINI_MODELE` passe un modèle en tête. Jamais d'appel direct à un fournisseur : passer par `appelerJson` / `appelerTexte`. Appelée uniquement depuis des fonctions Supabase ; les clés (`CHEVET_GROQ_API_KEY`, `CHEVET_GEMINI_API_KEY`) ne doivent jamais être dans l'APK. État et journal des bascules : tables `chevet_llm_etat` et `chevet_llm_journal`.
- Principes produit à respecter : fil borné, zéro dette, aucune culpabilisation, cartes d'environ 40 mots, ton de conteur.
- Serveur : projet Supabase « Panier » (ref iolmjlqcvipzsihvrxlj), partagé avec d'autres applis de Damien. Ne toucher qu'aux tables `chevet_*` et aux fonctions `chevet-*`.
- Le dépôt est public : jamais de profil personnel, de clé secrète ni de donnée privée dans le code. Le profil vit dans `chevet_profil`.
