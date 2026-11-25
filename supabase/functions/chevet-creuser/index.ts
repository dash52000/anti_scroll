// Chevet : bouton « creuser ». Répond à une question sur une carte (rôle « léger » : gpt-oss-20b, puis Gemini si Groq sature).
// Quota : réglable de 1 à 10 réponses par soir (3 par défaut), vérifié ici côté serveur.
//
// POST JSON, avec le JWT de l'utilisateur :
//   {"question":"...","concept":"titre","contexte":"texte des cartes","source":"url Wikipédia"}
// Réponse : {"reponse":"...","restants":2,"parSoir":3} ou {"quota":true,"restants":0,"parSoir":3}

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { appelerTexte, LimiteLLM } from "../_shared/llm.ts";

const QUOTA_PAR_DEFAUT = 3;

const admin = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function reponse(corps: unknown, statut = 200): Response {
  return new Response(JSON.stringify(corps), {
    status: statut,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const jeton = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const { data: utilisateur } = await admin.auth.getUser(jeton);
    if (!utilisateur?.user) return reponse({ erreur: "Utilisateur non connecté" }, 401);
    const userId = utilisateur.user.id;

    const { data: profil } = await admin.from("chevet_profil").select("niveaux, style, fiche, creusages_par_soir")
      .eq("user_id", userId).maybeSingle();
    const QUOTA = Math.max(1, Math.min(10, Number(profil?.creusages_par_soir) || QUOTA_PAR_DEFAUT));

    // Le soir dure au plus 12 heures : on compte les réponses depuis.
    const depuis = new Date(Date.now() - 12 * 3600 * 1000).toISOString();
    const { count } = await admin.from("chevet_journal")
      .select("id", { count: "exact", head: true })
      .eq("user_id", userId)
      .eq("evenement", "creuser_reponse")
      .gte("horodatage", depuis);
    const dejaFaits = count ?? 0;
    if (dejaFaits >= QUOTA) return reponse({ quota: true, restants: 0, parSoir: QUOTA });

    const { question, concept, contexte, source } = await req.json();
    if (!question) return reponse({ erreur: "Question manquante" }, 400);

    // Le style vient de la fiche réglée par le lecteur quand elle existe.
    const fiche: { titre?: string; puces?: string[] }[] = Array.isArray(profil?.fiche) ? profil.fiche : [];
    const styleFiche = fiche.find((r) => r.titre === "Style attendu")?.puces ?? [];
    const style = styleFiche.length ? styleFiche.join(" ; ") : JSON.stringify(profil?.style ?? {});

    let texte: string;
    try {
      texte = await appelerTexte({
        role: "leger",
        fonction: "chevet-creuser",
        maxTokens: 900,
        temperature: 0.6,
        systeme:
          "Tu es le conteur de Chevet, une appli de petites leçons du soir. Le lecteur est au lit, fatigué. " +
          "Réponds en français, en 90 mots au plus, de façon concrète, avec une anecdote si possible, sans jargon ni morale. " +
          "Si tu n'es pas sûr d'un fait, dis-le simplement plutôt que d'inventer. " +
          `Niveaux du lecteur : ${JSON.stringify(profil?.niveaux ?? {})}. Style : ${style}.`,
        utilisateur: `Concept : ${concept}\nCe que la carte disait : ${contexte}\nSource : ${source}\n\nQuestion : ${question}`,
      });
    } catch (e) {
      if (e instanceof LimiteLLM) {
        return reponse({ erreur: "Les modèles sont saturés, réessaie dans une minute.", restants: QUOTA - dejaFaits }, 503);
      }
      throw e;
    }

    await admin.from("chevet_journal").insert({
      user_id: userId,
      evenement: "creuser_reponse",
      concept_ref: String(concept ?? "").slice(0, 120),
      donnees: { question },
    });

    return reponse({ reponse: texte.trim(), restants: QUOTA - dejaFaits - 1, parSoir: QUOTA });
  } catch (e) {
    return reponse({ erreur: String(e) }, 500);
  }
});
