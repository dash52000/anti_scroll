// Chevet : notes perso. Deux actions, avec le JWT de l'utilisateur.
//
//   {"action":"repondre","note_id":"..."}
//       « Réponds-moi » : une réponse tout de suite, gpt-oss-20b, 120 mots au plus. Elle s'écrit dans la
//       note et y reste. Quota à part de « creuser » : 3 réponses par tranche de 24 h.
//       Réponse : {"reponse":"...","restants":2,"parJour":3} ou {"quota":true,"restants":0,"parJour":3}
//   {"action":"revient"}
//       « La note qui revient » : une fois par semaine au plus, le récap ressort une vieille note (7 jours
//       ou plus) avec les concepts lus depuis qui s'y rattachent. Si rien ne s'y rattache, rien ne revient.
//       Réponse : {"note":{"id","texte","jour"},"concepts":["titre",...]} ou {"note":null}

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
const MODELE = "openai/gpt-oss-20b";
const REPONSES_PAR_JOUR = 3;
const JOUR_MS = 86_400_000;

const admin = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

// deno-lint-ignore no-explicit-any
type Json = any;

class LimiteGroq extends Error {}

function reponse(corps: unknown, statut = 200): Response {
  return new Response(JSON.stringify(corps), {
    status: statut,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function jourParis(): string {
  return new Intl.DateTimeFormat("fr-CA", { timeZone: "Europe/Paris" }).format(new Date());
}

async function groq(systeme: string, utilisateur: string, maxTokens: number, json: boolean): Promise<string> {
  const cle = Deno.env.get("CHEVET_GROQ_API_KEY");
  if (!cle) throw new Error("Secret CHEVET_GROQ_API_KEY absent");
  const r = await fetch(GROQ_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${cle}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      model: MODELE,
      temperature: json ? 0.2 : 0.6,
      max_completion_tokens: maxTokens,
      reasoning_effort: "low",
      ...(json ? { response_format: { type: "json_object" } } : {}),
      messages: [
        { role: "system", content: systeme },
        { role: "user", content: utilisateur },
      ],
    }),
  });
  if (r.status === 429) throw new LimiteGroq("Groq est saturé, réessaie dans une minute.");
  if (!r.ok) throw new Error(`Groq ${r.status} : ${(await r.text()).slice(0, 300)}`);
  return String((await r.json()).choices?.[0]?.message?.content ?? "");
}

// ------------------------------------------------------------------ Réponds-moi

async function repondre(userId: string, noteId: string) {
  const { data: note } = await admin.from("chevet_notes").select("id, texte, reponse")
    .eq("id", noteId).eq("user_id", userId).maybeSingle();
  if (!note) return { erreur: "Note introuvable" };
  // Une note déjà répondue garde sa réponse : la relire ne coûte rien.
  if (note.reponse) return { reponse: note.reponse, restants: null, parJour: REPONSES_PAR_JOUR };

  const depuis = new Date(Date.now() - JOUR_MS).toISOString();
  const { count } = await admin.from("chevet_journal").select("id", { count: "exact", head: true })
    .eq("user_id", userId).eq("evenement", "note_reponse").gte("horodatage", depuis);
  const dejaFaites = count ?? 0;
  if (dejaFaites >= REPONSES_PAR_JOUR) return { quota: true, restants: 0, parJour: REPONSES_PAR_JOUR };

  const { data: profil } = await admin.from("chevet_profil").select("niveaux, style, fiche")
    .eq("user_id", userId).maybeSingle();
  const fiche: { titre?: string; puces?: string[] }[] = Array.isArray(profil?.fiche) ? profil.fiche : [];
  const styleFiche = fiche.find((r) => r.titre === "Style attendu")?.puces ?? [];
  const style = styleFiche.length ? styleFiche.join(" ; ") : JSON.stringify(profil?.style ?? {});

  const texte = (await groq(
    "Tu es le conteur de Chevet, une appli de petites leçons du soir. Le lecteur t'écrit une note : une idée, " +
      "une réflexion ou une question à lui. Réponds-lui en français, en tutoyant, en 120 mots au plus. " +
      "Commence par prolonger son idée, puis, si elle en vaut la peine, donne la meilleure objection ou nuance qu'on lui ferait. " +
      "Appuie-toi sur un fait ou une anecdote seulement si tu en es sûr ; sinon dis simplement ce que tu ne sais pas. " +
      "Pas de jargon, pas de leçon de morale, pas de flatterie. " +
      `Niveaux du lecteur : ${JSON.stringify(profil?.niveaux ?? {})}. Style : ${style}.`,
    `Note du lecteur :\n${note.texte}`,
    900,
    false,
  )).trim();
  if (!texte) throw new Error("Réponse vide");

  await admin.from("chevet_notes").update({ suite: "reponse", reponse: texte, reponse_le: new Date().toISOString() })
    .eq("id", noteId).eq("user_id", userId);
  await admin.from("chevet_journal").insert({
    user_id: userId,
    evenement: "note_reponse",
    donnees: { note: noteId },
  });
  return { reponse: texte, restants: REPONSES_PAR_JOUR - dejaFaites - 1, parJour: REPONSES_PAR_JOUR };
}

// ------------------------------------------------------------------ La note qui revient

async function revient(userId: string) {
  const aujourdhui = jourParis();
  const maintenant = Date.now();
  const septJours = new Date(maintenant - 7 * JOUR_MS).toISOString();

  // Une fois par semaine au plus.
  const { count: recentes } = await admin.from("chevet_notes").select("id", { count: "exact", head: true })
    .eq("user_id", userId).gte("rappelee_le", septJours);
  if ((recentes ?? 0) > 0) return { note: null };

  // Une seule recherche par jour : si elle n'a rien trouvé ce soir, inutile de la refaire au prochain récap.
  const { count: essais } = await admin.from("chevet_journal").select("id", { count: "exact", head: true })
    .eq("user_id", userId).eq("evenement", "note_revient_essai").eq("lot_jour", aujourdhui);
  if ((essais ?? 0) > 0) return { note: null };
  await admin.from("chevet_journal").insert({ user_id: userId, evenement: "note_revient_essai", lot_jour: aujourdhui });

  // Notes d'au moins 7 jours, pas ressorties depuis 60 jours.
  const { data: brutes } = await admin.from("chevet_notes").select("id, texte, cree_le, rappelee_le")
    .eq("user_id", userId).lte("cree_le", septJours).order("cree_le", { ascending: false }).limit(40);
  const soixante = maintenant - 60 * JOUR_MS;
  const notes: Json[] = (brutes ?? []).filter((n: Json) => !n.rappelee_le || Date.parse(n.rappelee_le) < soixante).slice(0, 8);
  if (notes.length === 0) return { note: null };

  const plusAncienne = notes.reduce((m: string, n: Json) => (n.cree_le < m ? n.cree_le : m), notes[0].cree_le);
  const { data: lus } = await admin.from("chevet_concepts").select("titre, decouvert_le")
    .eq("user_id", userId).gt("decouvert_le", plusAncienne).order("decouvert_le", { ascending: false }).limit(80);
  const concepts: Json[] = lus ?? [];
  if (concepts.length === 0) return { note: null };

  const choix = JSON.parse(await groq(
    "Tu relies des notes personnelles à des concepts lus depuis. Tu ne retiens un lien que s'il est net : " +
      "le concept éclaire, prolonge ou contredit l'idée de la note. Dans le doute, tu ne retiens rien.",
    `Notes (identifiant, date, texte) :
${notes.map((n) => `- ${n.id} · ${n.cree_le.slice(0, 10)} · ${String(n.texte).slice(0, 300)}`).join("\n")}

Concepts lus depuis (titre, date de lecture) :
${concepts.map((c) => `- ${c.titre} · ${String(c.decouvert_le).slice(0, 10)}`).join("\n")}

Choisis au plus UNE note et 1 à 3 concepts lus APRÈS la date de cette note qui s'y rattachent.
Format JSON : {"note_id":"identifiant ou null","concepts":["titre exact","..."]}`,
    500,
    true,
  ).catch(() => "{}"));

  const note = notes.find((n) => n.id === choix.note_id);
  if (!note) return { note: null };
  const titres: string[] = (Array.isArray(choix.concepts) ? choix.concepts : [])
    .map(String)
    .filter((t: string) => concepts.some((c) => c.titre === t && String(c.decouvert_le) > String(note.cree_le)))
    .slice(0, 3);
  if (titres.length === 0) return { note: null };

  await admin.from("chevet_notes").update({ rappelee_le: new Date(maintenant).toISOString() })
    .eq("id", note.id).eq("user_id", userId);
  return { note: { id: note.id, texte: note.texte, jour: String(note.cree_le).slice(0, 10) }, concepts: titres };
}

// ------------------------------------------------------------------ Point d'entrée

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const jeton = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const { data: utilisateur } = await admin.auth.getUser(jeton);
    if (!utilisateur?.user) return reponse({ erreur: "Utilisateur non connecté" }, 401);
    const userId = utilisateur.user.id;

    const corps = await req.json().catch(() => ({}));
    switch (corps.action) {
      case "repondre":
        return reponse(await repondre(userId, String(corps.note_id ?? "")));
      case "revient":
        return reponse(await revient(userId));
      default:
        return reponse({ erreur: `Action inconnue : ${corps.action}` }, 400);
    }
  } catch (e) {
    if (e instanceof LimiteGroq) return reponse({ erreur: e.message }, 503);
    return reponse({ erreur: String(e) }, 500);
  }
});
