// Chevet : appel des modèles de langage avec bascule automatique.
//
// Chaque appel déclare un rôle ; chaque rôle a sa chaîne de modèles, essayés dans l'ordre :
//   redaction    Gemini, puis Groq gpt-oss-120b (composition des portes, rédaction des cartes)
//   verification Groq gpt-oss-120b, puis Gemini (juge d'une autre famille que le rédacteur, et qui ne
//                double pas le quota Gemini ; jamais de modèle plus faible : mieux vaut attendre)
//   leger        Groq gpt-oss-20b, puis Gemini (creuser, notes, pépite, voisins : réponse rapide voulue)
//
// Quand basculer :
//   429             un seul nouvel essai si retry-after ≤ 3 s, sinon modèle suivant et repos du modèle
//   500/502/503/504 modèle suivant, repos de 2 minutes
//   délai dépassé   idem (Gemini peut mettre plus de 100 s quand il sature)
//   réponse vide ou JSON inutilisable   modèle suivant, sans repos
//   401/403/404     modèle suivant, repos de 10 minutes (clé ou nom de modèle à corriger : visible au journal)
//   autre 4xx       modèle suivant, sans repos
//
// Si toute la chaîne est saturée ou en repos, l'appel lève LimiteLLM : l'appelant réessaie plus tard
// (le cron du lot repasse toutes les 5 minutes) sans compter un échec. Toute autre panne lève Error.
//
// L'état de repos et le journal vivent dans chevet_llm_etat et chevet_llm_journal. Une panne de ces
// tables n'empêche jamais un appel.

// deno-lint-ignore no-explicit-any
type Json = any;

export type Role = "redaction" | "verification" | "leger";
type Fournisseur = "gemini" | "groq";

interface Candidat {
  fournisseur: Fournisseur;
  modele: string;
  delaiMs: number;
}

export interface DemandeLLM {
  role: Role;
  /** Nom de la fonction appelante, pour le journal. */
  fonction: string;
  systeme: string;
  utilisateur: string;
  maxTokens: number;
  temperature: number;
  /** Réponse attendue en JSON : renvoyée déjà décodée. */
  json: boolean;
  /** Temps total accordé à toute la chaîne (défaut 135 s : les fonctions coupent à 150 s). */
  budgetMs?: number;
}

export interface ReponseLLM {
  texte: string;
  json: Json;
  fournisseur: Fournisseur;
  modele: string;
  /** Place du modèle dans la chaîne : 1 = modèle préféré. */
  rang: number;
}

export class LimiteLLM extends Error {}

type Resultat = "ok" | "repos" | "limite" | "indisponible" | "erreur" | "inutilisable";

interface Essai {
  rang: number;
  essai: number;
  candidat: Candidat;
  resultat: Resultat;
  statut: number | null;
  dureeMs: number | null;
  detail?: string;
}

const GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
const MODELE_GEMINI_PAR_DEFAUT = "gemini-3.8-flash";
const GROQ_120B = "openai/gpt-oss-120b";
const GROQ_20B = "openai/gpt-oss-20b";
const BUDGET_PAR_DEFAUT_MS = 135_000;
const MARGE_MS = 2_000;
const MINIMUM_UTILE_MS = 8_000;

// ------------------------------------------------------------------ Configuration

function lire(nom: string): string | undefined {
  // deno-lint-ignore no-explicit-any
  const g = globalThis as any;
  return g.Deno?.env?.get(nom) ?? g.process?.env?.[nom] ?? undefined;
}

function cleDe(f: Fournisseur): string | undefined {
  return lire(f === "gemini" ? "CHEVET_GEMINI_API_KEY" : "CHEVET_GROQ_API_KEY") || undefined;
}

export function chaine(role: Role): Candidat[] {
  const gemini = (delaiMs: number): Candidat => ({
    fournisseur: "gemini",
    modele: lire("CHEVET_GEMINI_MODELE") || MODELE_GEMINI_PAR_DEFAUT,
    delaiMs,
  });
  const groq = (modele: string, delaiMs: number): Candidat => ({ fournisseur: "groq", modele, delaiMs });
  switch (role) {
    case "redaction":
      return [gemini(70_000), groq(GROQ_120B, 60_000)];
    case "verification":
      return [groq(GROQ_120B, 60_000), gemini(70_000)];
    case "leger":
      return [groq(GROQ_20B, 30_000), gemini(20_000)];
  }
}

const cleRepos = (c: Candidat) => `${c.fournisseur}:${c.modele}`;

// ------------------------------------------------------------------ État et journal (REST)

function acces(): { url: string; entetes: Record<string, string> } | null {
  const url = lire("SUPABASE_URL");
  const cle = lire("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !cle) return null;
  return { url, entetes: { apikey: cle, Authorization: `Bearer ${cle}`, "Content-Type": "application/json" } };
}

async function lireRepos(): Promise<Map<string, number>> {
  const repos = new Map<string, number>();
  const a = acces();
  if (!a) return repos;
  try {
    const maintenant = encodeURIComponent(new Date().toISOString());
    const r = await fetch(
      `${a.url}/rest/v1/chevet_llm_etat?select=modele,repos_jusqua&repos_jusqua=gt.${maintenant}`,
      { headers: a.entetes, signal: AbortSignal.timeout(4000) },
    );
    if (!r.ok) return repos;
    for (const ligne of await r.json()) repos.set(String(ligne.modele), Date.parse(ligne.repos_jusqua));
  } catch (_) {
    // Sans l'état, on essaie simplement les modèles dans l'ordre.
  }
  return repos;
}

async function poserRepos(c: Candidat, secondes: number, statut: number, motif: string): Promise<void> {
  const a = acces();
  if (!a || secondes <= 0) return;
  try {
    await fetch(`${a.url}/rest/v1/chevet_llm_etat?on_conflict=modele`, {
      method: "POST",
      headers: { ...a.entetes, Prefer: "resolution=merge-duplicates,return=minimal" },
      body: JSON.stringify({
        modele: cleRepos(c),
        repos_jusqua: new Date(Date.now() + secondes * 1000).toISOString(),
        dernier_statut: statut,
        motif: motif.slice(0, 300),
        mis_a_jour_le: new Date().toISOString(),
      }),
      signal: AbortSignal.timeout(4000),
    });
  } catch (_) {
    // idem
  }
}

async function journaliser(d: DemandeLLM, essais: Essai[]): Promise<void> {
  const a = acces();
  if (!a || essais.length === 0) return;
  try {
    await fetch(`${a.url}/rest/v1/chevet_llm_journal`, {
      method: "POST",
      headers: { ...a.entetes, Prefer: "return=minimal" },
      body: JSON.stringify(essais.map((e) => ({
        fonction: d.fonction,
        role: d.role,
        fournisseur: e.candidat.fournisseur,
        modele: e.candidat.modele,
        rang: e.rang,
        essai: e.essai,
        resultat: e.resultat,
        statut: e.statut,
        duree_ms: e.dureeMs,
      }))),
      signal: AbortSignal.timeout(4000),
    });
  } catch (_) {
    // idem
  }
}

// ------------------------------------------------------------------ Un appel

function corps(c: Candidat, d: DemandeLLM): string {
  const messages = [
    { role: "system", content: d.systeme },
    { role: "user", content: d.utilisateur },
  ];
  const format = d.json ? { response_format: { type: "json_object" } } : {};
  if (c.fournisseur === "gemini") {
    // max_tokens couvre aussi la réflexion du modèle : on lui laisse de la marge.
    return JSON.stringify({
      model: c.modele,
      messages,
      temperature: d.temperature,
      max_tokens: Math.min(20000, Math.max(d.maxTokens * 3, 6000)),
      reasoning_effort: "low",
      ...format,
    });
  }
  return JSON.stringify({
    model: c.modele,
    messages,
    temperature: d.temperature,
    max_completion_tokens: d.maxTokens,
    reasoning_effort: "low",
    ...format,
  });
}

interface Reception {
  statut: number;
  retryApresS: number | null;
  corps: string;
}

async function envoyer(c: Candidat, d: DemandeLLM, delaiMs: number): Promise<Reception> {
  const cle = cleDe(c.fournisseur)!;
  try {
    const r = await fetch(c.fournisseur === "gemini" ? GEMINI_URL : GROQ_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${cle}`, "Content-Type": "application/json" },
      body: corps(c, d),
      signal: AbortSignal.timeout(delaiMs),
    });
    const retry = Number(r.headers.get("retry-after"));
    return {
      statut: r.status,
      retryApresS: Number.isFinite(retry) && r.headers.get("retry-after") !== null ? retry : null,
      corps: await r.text(),
    };
  } catch (e) {
    // Délai dépassé ou réseau coupé.
    return { statut: 0, retryApresS: null, corps: String((e as Error)?.message ?? e) };
  }
}

/** Texte de la réponse et, si demandé, son JSON ; null si la réponse est inutilisable. */
function extraire(brut: string, json: boolean): { texte: string; json: Json } | null {
  let donnees: Json;
  try {
    donnees = JSON.parse(brut);
  } catch {
    return null;
  }
  const texte = String(donnees?.choices?.[0]?.message?.content ?? "").replace(/<think>[\s\S]*?<\/think>/g, "").trim();
  if (!texte) return null;
  if (!json) return { texte, json: null };
  try {
    return { texte, json: JSON.parse(texte) };
  } catch {
    const bloc = texte.match(/\{[\s\S]*\}/);
    if (!bloc) return null;
    try {
      return { texte, json: JSON.parse(bloc[0]) };
    } catch {
      return null;
    }
  }
}

const attendre = (ms: number) => new Promise((ok) => setTimeout(ok, ms));
const borner = (x: number, min: number, max: number) => Math.max(min, Math.min(max, x));

// ------------------------------------------------------------------ Appel avec bascule

export async function appeler(d: DemandeLLM): Promise<ReponseLLM> {
  const debut = Date.now();
  const budget = d.budgetMs ?? BUDGET_PAR_DEFAUT_MS;
  const reste = () => budget - (Date.now() - debut);

  const candidats = chaine(d.role).filter((c) => cleDe(c.fournisseur));
  if (candidats.length === 0) throw new Error("Aucune clé d'API pour ce rôle (CHEVET_GEMINI_API_KEY, CHEVET_GROQ_API_KEY)");

  const repos = await lireRepos();
  const essais: Essai[] = [];
  let toutTransitoire = true;
  let rang = 0;

  for (const c of candidats) {
    rang++;
    if ((repos.get(cleRepos(c)) ?? 0) > Date.now()) {
      essais.push({ rang, essai: 1, candidat: c, resultat: "repos", statut: null, dureeMs: null });
      continue;
    }
    for (let essai = 1; essai <= 2; essai++) {
      if (reste() < MINIMUM_UTILE_MS) break;
      const delai = Math.min(c.delaiMs, reste() - MARGE_MS);
      const t0 = Date.now();
      const rec = await envoyer(c, d, delai);
      const dureeMs = Date.now() - t0;
      const noter = (resultat: Resultat, detail?: string) =>
        essais.push({ rang, essai, candidat: c, resultat, statut: rec.statut, dureeMs, detail });

      if (rec.statut >= 200 && rec.statut < 300) {
        const extrait = extraire(rec.corps, d.json);
        if (extrait) {
          noter("ok");
          await journaliser(d, essais);
          return { ...extrait, fournisseur: c.fournisseur, modele: c.modele, rang };
        }
        noter("inutilisable", rec.corps.slice(0, 200));
        toutTransitoire = false;
        break;
      }
      if (rec.statut === 429) {
        const attenteS = rec.retryApresS;
        if (essai === 1 && attenteS !== null && attenteS <= 3 && reste() > MINIMUM_UTILE_MS + 4000) {
          noter("limite", "retry-after court, nouvel essai");
          await attendre(attenteS * 1000 + 200);
          continue;
        }
        noter("limite", rec.corps.slice(0, 200));
        await poserRepos(c, borner(attenteS ?? 60, 30, 1800), 429, rec.corps);
        break;
      }
      if (rec.statut === 0 || rec.statut >= 500) {
        noter("indisponible", rec.corps.slice(0, 200));
        await poserRepos(c, rec.statut === 0 ? 90 : 120, rec.statut, rec.corps);
        break;
      }
      noter("erreur", rec.corps.slice(0, 200));
      toutTransitoire = false;
      if ([401, 403, 404].includes(rec.statut)) await poserRepos(c, 600, rec.statut, rec.corps);
      break;
    }
  }

  await journaliser(d, essais);
  const resume = essais.map((e) => `${e.candidat.fournisseur}:${e.candidat.modele} ${e.resultat}${e.statut ? ` ${e.statut}` : ""}${e.detail ? ` (${e.detail.replace(/\s+/g, " ").slice(0, 120)})` : ""}`).join(" ; ");
  if (essais.length === 0 || (toutTransitoire && essais.every((e) => ["repos", "limite", "indisponible"].includes(e.resultat)))) {
    throw new LimiteLLM(`Modèles saturés ou indisponibles, réessayer plus tard : ${resume || "budget épuisé"}`);
  }
  throw new Error(`Aucun modèle n'a répondu : ${resume}`);
}

/** Réponse JSON déjà décodée. */
export async function appelerJson(d: Omit<DemandeLLM, "json">): Promise<Json> {
  return (await appeler({ ...d, json: true })).json;
}

/** Réponse en texte libre. */
export async function appelerTexte(d: Omit<DemandeLLM, "json">): Promise<string> {
  return (await appeler({ ...d, json: false })).texte;
}
