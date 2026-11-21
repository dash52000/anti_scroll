// Chevet : onboarding. Une action, avec le JWT de l'utilisateur.
//
//   {"action":"youtube","chaines":[{"nom":"...","n":42}],"titres":["..."]}
//       Tire des centres d'intérêt de l'historique YouTube du lecteur (Google Takeout) avec gpt-oss-20b.
//       Le fichier n'est jamais envoyé en entier : l'appli ne transmet que ses chaînes les plus regardées
//       et un échantillon de titres. Rien n'est gardé ici : l'appli ajoute les puces à la fiche.
//       Réponse : {"puces":["...", ...]}

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

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

async function interetsYoutube(chaines: { nom: string; n: number }[], titres: string[]) {
  const cle = Deno.env.get("CHEVET_GROQ_API_KEY");
  if (!cle) throw new Error("Secret CHEVET_GROQ_API_KEY absent");
  const r = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: { Authorization: `Bearer ${cle}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      model: "openai/gpt-oss-20b",
      temperature: 0.2,
      max_completion_tokens: 900,
      reasoning_effort: "low",
      response_format: { type: "json_object" },
      messages: [
        {
          role: "system",
          content:
            "Tu lis l'historique YouTube d'un lecteur pour en tirer ses centres d'intérêt de fond, à des fins " +
            "d'apprentissage du soir. Tu écris en français, des puces courtes (12 mots au plus), concrètes " +
            "(un sujet, un type de contenu, une chaîne marquante). Pas de jugement, pas de déduction sur la vie " +
            "privée, la santé, les opinions ou les croyances : uniquement ce qui se voit dans les titres et les chaînes.",
        },
        {
          role: "user",
          content: `Chaînes les plus regardées (nom, nombre de vidéos) :
${chaines.map((c) => `- ${c.nom} (${c.n})`).join("\n")}

Échantillon de titres :
${titres.map((t) => `- ${t}`).join("\n")}

Donne de 6 à 12 puces. Format JSON : {"puces":["..."]}`,
        },
      ],
    }),
  });
  if (r.status === 429) return { erreur: "Groq est saturé, réessaie dans une minute." };
  if (!r.ok) throw new Error(`Groq ${r.status} : ${(await r.text()).slice(0, 300)}`);
  const brut: string = (await r.json()).choices?.[0]?.message?.content ?? "{}";
  const donnees = JSON.parse(brut.match(/\{[\s\S]*\}/)?.[0] ?? "{}");
  const puces = (Array.isArray(donnees.puces) ? donnees.puces : [])
    .map((p: unknown) => String(p ?? "").trim().slice(0, 160))
    .filter(Boolean)
    .slice(0, 12);
  return { puces };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const jeton = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const { data: utilisateur } = await admin.auth.getUser(jeton);
    if (!utilisateur?.user) return reponse({ erreur: "Utilisateur non connecté" }, 401);

    const corps = await req.json().catch(() => ({}));
    if (corps.action !== "youtube") return reponse({ erreur: `Action inconnue : ${corps.action}` }, 400);

    const chaines = (Array.isArray(corps.chaines) ? corps.chaines : [])
      .map((c: { nom?: unknown; n?: unknown }) => ({ nom: String(c?.nom ?? "").slice(0, 80), n: Number(c?.n) || 0 }))
      .filter((c: { nom: string }) => c.nom)
      .slice(0, 40);
    const titres = (Array.isArray(corps.titres) ? corps.titres : []).map((t: unknown) => String(t ?? "").slice(0, 140)).slice(0, 120);
    if (chaines.length === 0 && titres.length === 0) return reponse({ erreur: "Historique vide" }, 400);

    const resultat = await interetsYoutube(chaines, titres);
    return reponse(resultat, "erreur" in resultat ? 503 : 200);
  } catch (e) {
    return reponse({ erreur: String(e) }, 500);
  }
});
