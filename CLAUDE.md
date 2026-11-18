# Chevet (repo anti_scroll)

- Appli Android anti-doomscrolling de Damien, en Kotlin + Jetpack Compose. Interface et code en français.
- Spec de référence : https://claude.ai/artifact/YPLLzfDEQkapQjjSUku3rd (doc Claude).
- Pas de SDK Android dans les sessions cloud : le build se vérifie par GitHub Actions (`.github/workflows/build.yml`), qui publie l'APK en pré-version.
- Garder la même clé `app/debug.keystore` pour que les mises à jour s'installent par-dessus.
- IA : Groq (gpt-oss-120b pour le lot, gpt-oss-20b pour « creuser »), appelée uniquement depuis des fonctions Supabase ; la clé ne doit jamais être dans l'APK.
- Principes produit à respecter : fil borné, zéro dette, aucune culpabilisation, cartes d'environ 40 mots, ton de conteur.
