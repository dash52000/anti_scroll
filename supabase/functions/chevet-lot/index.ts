// Chevet : préparation du lot du jour, une étape par appel.
//
// Étapes : 0 composer les 3 portes (profil + actu RSS + terrier Wikipédia + curiosités)
//          1-3 rédiger les cartes de chaque porte à partir des articles Wikipédia
//          4-6 relecture factuelle de chaque porte contre les articles
//          7 pépite, assemblage, lot prêt
//
// Actions (POST JSON) :
//   {"action":"avancer"}  avec le JWT de l'utilisateur : fait avancer son lot du jour d'une étape
//                          et le renvoie quand il est prêt.
//   {"action":"cron"}     fait avancer d'une étape le lot de chaque utilisateur (appelé par pg_cron).
//   {"action":"modele"}   fait avancer le lot de test, construit sur le profil modèle (sans utilisateur).
//
// Actions des réglages (avec le JWT de l'utilisateur) :
//   {"action":"profil"}      renvoie le profil réglable, en créant la fiche au premier passage.
//   {"action":"enregistrer_profil","profil":{...}}  enregistre les champs réglés, bornés et nettoyés.
//   {"action":"regenerer"}   remet le lot du jour à zéro : « avancer » le recompose avec le profil à jour.
//   {"action":"suite","porte":"id","gardes":2,"demande":"..."}
//                            réécrit les concepts d'une porte après les « gardes » premiers, une étape
//                            par appel ; « demande » identifie la demande (une nouvelle repart de zéro).
//   {"action":"tester_flux","url":"..."}  vérifie qu'une URL donne un flux RSS lisible.
//
// Actions du menu (avec le JWT de l'utilisateur) :
//   {"action":"bonus","demande":"..."}   « d'autres portes » : compose 3 portes neuves pour ce soir
//                                         (sans répéter celles du lot) et renvoie leurs titres.
//   {"action":"bonus","demande":"...","porte":"id"}
//                                         écrit la porte bonus choisie, une étape par appel
//                                         (rédaction, relecture, assemblage) ; elle rejoint le lot.
//   {"action":"voisins","url":"...","titre":"...","slug":"..."}
//                                         articles Wikipédia liés à un concept, pas encore explorés,
//                                         choisis par l'IA pour le graphe du terrier (gardés en cache).
//
// Tout est idempotent : un lot prêt ne bouge plus, donc un appel en trop ne coûte rien.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
const MODELE_REDACTION = "openai/gpt-oss-120b";
const MODELE_RELECTURE = "openai/gpt-oss-20b";
const MODELE_VERIFICATION = "openai/gpt-oss-120b";
const UA = "Chevet/0.1 (https://github.com/dash52000/anti_scroll)";
const NB_ETAPES = 8;
const NB_ETAPES_SUITE = 4;
const NB_ETAPES_BONUS = 3;
const CONCEPTS_PAR_PORTE = 3;

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

// ------------------------------------------------------------------ Outils

function reponse(corps: Json, statut = 200): Response {
  return new Response(JSON.stringify(corps), {
    status: statut,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function jourParis(): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Europe/Paris",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

function slug(texte: string): string {
  return texte
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "")
    .slice(0, 60);
}

function melanger<T>(tableau: T[]): T[] {
  const copie = [...tableau];
  for (let i = copie.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copie[i], copie[j]] = [copie[j], copie[i]];
  }
  return copie;
}

async function groq(
  modele: string,
  systeme: string,
  utilisateur: string,
  maxTokens = 3000,
  temperature = 0.7,
): Promise<Json> {
  const cle = Deno.env.get("CHEVET_GROQ_API_KEY");
  if (!cle) throw new Error("Secret CHEVET_GROQ_API_KEY absent");
  const r = await fetch(GROQ_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${cle}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      model: modele,
      messages: [
        { role: "system", content: systeme },
        { role: "user", content: utilisateur },
      ],
      temperature,
      max_completion_tokens: maxTokens,
      reasoning_effort: "low",
      response_format: { type: "json_object" },
    }),
  });
  if (r.status === 429) throw new LimiteGroq(`Groq limite atteinte, réessayer dans ${r.headers.get("retry-after") ?? "?"} s`);
  if (!r.ok) throw new Error(`Groq ${r.status} : ${(await r.text()).slice(0, 400)}`);
  const donnees = await r.json();
  const texte: string = donnees.choices?.[0]?.message?.content ?? "";
  try {
    return JSON.parse(texte);
  } catch {
    const bloc = texte.match(/\{[\s\S]*\}/);
    if (bloc) return JSON.parse(bloc[0]);
    throw new Error("Réponse Groq non JSON : " + texte.slice(0, 200));
  }
}

// ------------------------------------------------------------------ Actu (RSS)

function decoder(texte: string): string {
  return texte
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;|&apos;/g, "'")
    .replace(/&nbsp;/g, " ")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&amp;/g, "&")
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function balise(bloc: string, nom: string): string {
  const m = bloc.match(new RegExp(`<${nom}[^>]*>([\\s\\S]*?)</${nom}>`));
  return m ? m[1] : "";
}

function lien(bloc: string): string {
  const simple = bloc.match(/<link>([\s\S]*?)<\/link>/);
  if (simple) return decoder(simple[1]);
  const atom = bloc.match(/<link[^>]*href="([^"]+)"/);
  return atom ? atom[1] : "";
}

async function lireFlux(toutes: { nom: string; url: string; actif?: boolean }[]) {
  // Une source coupée dans les réglages n'est plus lue.
  const sources = toutes.filter((s) => s.actif !== false);
  const resultats = await Promise.allSettled(
    sources.map(async (s) => {
      const r = await fetch(s.url, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(8000) });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const xml = await r.text();
      return [...xml.matchAll(/<(item|entry)[\s>][\s\S]*?<\/\1>/g)]
        .slice(0, 8)
        .map((m) => ({
          media: s.nom,
          titre: decoder(balise(m[0], "title")),
          lien: lien(m[0]),
          resume: decoder(balise(m[0], "description") || balise(m[0], "summary")).slice(0, 220),
        }))
        .filter((a) => a.titre);
    }),
  );
  const articles = resultats.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
  const etat = resultats.map((r, i) =>
    `${sources[i].nom} : ${r.status === "fulfilled" ? `${r.value.length} articles` : `échec (${r.reason})`}`
  );
  return { articles, etat };
}

// ------------------------------------------------------------------ Wikipédia

async function wiki(params: Record<string, string>, langue: string): Promise<Json> {
  const url = new URL(`https://${langue}.wikipedia.org/w/api.php`);
  for (const [k, v] of Object.entries({ format: "json", formatversion: "2", ...params })) {
    url.searchParams.set(k, v);
  }
  const r = await fetch(url, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(8000) });
  if (!r.ok) throw new Error(`Wikipédia ${r.status}`);
  return await r.json();
}

const urlArticle = (titre: string, langue: string) =>
  `https://${langue}.wikipedia.org/wiki/${encodeURIComponent(titre.replaceAll(" ", "_"))}`;

async function titreExact(titre: string, langue: string) {
  const d = await wiki({ action: "query", titles: titre, redirects: "1", prop: "pageprops" }, langue);
  const page = d.query?.pages?.[0];
  if (page && !page.missing && !page.invalid && !page.pageprops?.disambiguation) {
    return { titre: page.title as string, langue, url: urlArticle(page.title, langue) };
  }
  return null;
}

function motsSignificatifs(texte: string): Set<string> {
  return new Set(
    texte.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase()
      .split(/[^a-z0-9]+/).filter((m) => m.length > 3),
  );
}

/** Proportion des mots du titre demandé qu'on retrouve dans le titre trouvé. */
function ressemblance(demande: string, trouve: string): number {
  const a = motsSignificatifs(demande);
  const b = motsSignificatifs(trouve);
  if (a.size === 0) return 0;
  let communs = 0;
  for (const m of a) if (b.has(m)) communs++;
  return communs / Math.max(a.size, b.size);
}

async function recherche(titre: string, langue: string) {
  const s = await wiki({ action: "query", list: "search", srsearch: titre, srlimit: "5" }, langue);
  const meilleurs = ((s.query?.search ?? []) as Json[])
    .map((r) => ({ titre: r.title as string, note: ressemblance(titre, r.title) }))
    .filter((r) => r.note >= 0.5)
    .sort((a, b) => b.note - a.note);
  const trouve = meilleurs[0];
  return trouve ? { titre: trouve.titre, langue, url: urlArticle(trouve.titre, langue) } : null;
}

/** Article exact en français, sinon exact en anglais, sinon meilleure recherche. */
async function resoudreArticle(fr: string, en?: string) {
  const essais: Array<() => Promise<Json>> = [
    () => titreExact(fr, "fr"),
    () => (en ? titreExact(en, "en") : Promise.resolve(null)),
    () => recherche(fr, "fr"),
    () => recherche(en ?? fr, "en"),
  ];
  for (const essai of essais) {
    try {
      const r = await essai();
      if (r) return r;
    } catch (_) {
      // essai suivant
    }
  }
  return null;
}

async function extrait(titre: string, langue: string, motsCles: string[], longueur = 3000): Promise<string> {
  const d = await wiki(
    { action: "query", prop: "extracts", explaintext: "1", titles: titre, redirects: "1" },
    langue,
  );
  const texte = ((d.query?.pages?.[0]?.extract as string) ?? "").replace(/\n={2,}[^=\n]+={2,}\n/g, "\n");
  const paragraphes = texte.split(/\n+/).map((p) => p.trim().slice(0, 900)).filter((p) => p.length > 60);
  if (paragraphes.length === 0) return "";
  const intro = paragraphes[0].slice(0, 900);
  const cles = motsCles.map((m) => m.toLowerCase()).filter((m) => m.length > 4);
  const notes = paragraphes.slice(1).map((p, i) => {
    const bas = p.toLowerCase();
    const chiffres = (p.match(/\b(1[5-9]|20)\d{2}\b|\d+[ ,.]?\d*\s?(%|millions?|milliards?|euros?|habitants|morts|places)/g) ?? []).length;
    const pertinence = cles.filter((m) => bas.includes(m)).length;
    return { i, p, note: Math.min(chiffres, 4) * 2 + pertinence * 3 };
  });
  const retenus: { i: number; p: string }[] = [];
  let taille = intro.length;
  for (const n of [...notes].sort((a, b) => b.note - a.note)) {
    if (taille + n.p.length > longueur) continue;
    retenus.push(n);
    taille += n.p.length;
  }
  retenus.sort((a, b) => a.i - b.i);
  return [intro, ...retenus.map((r) => r.p)].join("\n");
}

function motsDe(...textes: (string | undefined)[]): string[] {
  return textes.join(" ").split(/[^\p{L}\p{N}]+/u).filter(Boolean);
}

async function liensSortants(titre: string, langue: string): Promise<string[]> {
  const d = await wiki(
    { action: "query", prop: "links", plnamespace: "0", pllimit: "120", titles: titre, redirects: "1" },
    langue,
  );
  return (d.query?.pages?.[0]?.links ?? []).map((l: Json) => l.title as string);
}

// ------------------------------------------------------------------ Consignes communes

/** La fiche « Ce que Chevet sait de moi » en texte, ou null si elle n'existe pas encore. */
function texteFiche(profil: Json): string | null {
  const fiche: Json[] = Array.isArray(profil.fiche) ? profil.fiche : [];
  const rubriques = fiche
    .map((r) => ({ titre: String(r?.titre ?? ""), puces: (r?.puces ?? []).map(String).filter(Boolean) }))
    .filter((r) => r.puces.length > 0);
  if (rubriques.length === 0) return null;
  return rubriques.map((r) => `${r.titre} :\n${r.puces.map((p: string) => `- ${p}`).join("\n")}`).join("\n");
}

function portraitLecteur(profil: Json): string {
  const fiche = texteFiche(profil);
  return [
    fiche ? `Ce que le lecteur dit de lui :\n${fiche}` : `Lecteur : ${profil.interets?.resume ?? ""}`,
    `Niveaux : ${JSON.stringify(profil.niveaux)}`,
    fiche ? null : `Style attendu : ${JSON.stringify(profil.style)}`,
    `À éviter : ${(profil.a_eviter ?? []).join(", ")}`,
  ].filter((l) => l !== null).join("\n");
}

const THEMES_PAR_DEFAUT = ["Sciences & techno", "Histoire & sociétés", "Stats, maths, data", "Philo & idées"];

/** Poids des thèmes réglés par le lecteur (0 à 100). Tout à zéro vaut tout égal. */
function poidsThemes(profil: Json): Record<string, number> {
  const regles = profil.poids_themes && typeof profil.poids_themes === "object" ? profil.poids_themes : {};
  const themes = Object.keys(regles).length ? Object.keys(regles) : (profil.interets?.themes ?? THEMES_PAR_DEFAUT);
  const poids: Record<string, number> = {};
  for (const t of themes) {
    const p = Number(regles[t]);
    poids[t] = Number.isFinite(p) ? Math.max(0, Math.min(100, Math.round(p))) : 50;
  }
  if (Object.values(poids).every((p) => p === 0)) for (const t of themes) poids[t] = 50;
  return poids;
}

const FOURCHETTES_CARTES: Record<string, [number, number]> = { "1-2": [1, 2], "2-4": [2, 4], "4-6": [4, 6] };

/** Longueur des cartes réglée par le lecteur : mots par carte et fourchette de cartes par concept. */
function longueurCartes(profil: Json): { mots: number; min: number; max: number } {
  const mots = [25, 40, 60].includes(Number(profil?.mots_par_carte)) ? Number(profil.mots_par_carte) : 40;
  const [min, max] = FOURCHETTES_CARTES[String(profil?.cartes_par_concept)] ?? [2, 4];
  return { mots, min, max };
}

function consigneThemes(profil: Json): string {
  const poids = poidsThemes(profil);
  return `Poids des thèmes réglés par le lecteur (0 = jamais, 100 = le plus souvent) : ${
    Object.entries(poids).map(([t, p]) => `${t} ${p}`).join(" ; ")
  }.
Répartis les concepts de la soirée selon ces poids : un thème à 0 n'apparaît jamais, un thème fort revient le plus souvent.`;
}

// ------------------------------------------------------------------ Fiche du lecteur

const LIBELLES: Record<string, string> = {
  resume: "En bref",
  foot: "Foot",
  histoire: "Histoire",
  philo: "Philo et idées",
  politique: "Politique",
  autres: "Autres intérêts",
  sport_pratique: "Sport pratiqué",
  youtube: "Sur YouTube",
  references_style: "Références de style",
  ton: "Ton",
  echelle: "Échelle",
  chiffres: "Chiffres",
  profondeur: "Profondeur",
  a_proscrire: "À proscrire",
  longueur_carte: "Longueur des cartes",
};

const majuscule = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const libelle = (cle: string) => LIBELLES[cle] ?? majuscule(cle.replaceAll("_", " "));

/** Découpe une énumération aux virgules et points-virgules, sans couper les parenthèses. */
function decouper(texte: string): string[] {
  const morceaux: string[] = [];
  let profondeur = 0;
  let courant = "";
  for (const c of texte) {
    if (c === "(") profondeur++;
    if (c === ")") profondeur = Math.max(0, profondeur - 1);
    if (profondeur === 0 && (c === "," || c === ";")) {
      morceaux.push(courant);
      courant = "";
    } else {
      courant += c;
    }
  }
  morceaux.push(courant);
  return morceaux.map((m) => m.trim().replace(/\.$/, "")).filter(Boolean).map(majuscule);
}

/** Première fiche, tirée des champs libres du profil de départ. */
function ficheDepuis(profil: Json): { titre: string; puces: string[] }[] {
  const rubriques: { titre: string; puces: string[] }[] = [];
  const interets = profil.interets ?? {};
  if (typeof interets.resume === "string" && interets.resume.trim()) {
    const phrases = interets.resume.split(/(?<=\.)\s+/).map((p: string) => p.trim().replace(/\.$/, "")).filter(Boolean);
    rubriques.push({ titre: libelle("resume"), puces: phrases });
  }
  for (const [cle, valeur] of Object.entries(interets)) {
    if (cle === "resume" || cle === "themes") continue;
    const puces = Array.isArray(valeur)
      ? valeur.map(String).filter(Boolean)
      : typeof valeur === "string" ? decouper(valeur) : [];
    if (puces.length) rubriques.push({ titre: libelle(cle), puces });
  }
  const style = Object.entries(profil.style ?? {})
    .filter(([, v]) => typeof v === "string" && v)
    .map(([k, v]) => `${libelle(k)} : ${v}`);
  if (style.length) rubriques.push({ titre: "Style attendu", puces: style });
  return rubriques;
}

const SYSTEME_CHEVET =
  "Tu prépares le lot du soir de Chevet, une appli qui remplace le scroll du soir au lit par de petites leçons. " +
  "Le lecteur est fatigué : tout doit être clair, concret, raconté comme une histoire vraie, sans jargon et sans morale finale. " +
  "Tu écris en français et tu tutoies le lecteur. Tu réponds uniquement par un objet JSON valide.";

// ------------------------------------------------------------------ Étape 0 : composer

/** Ce qu'une composition de portes bonus ne doit pas répéter : les portes et concepts de ce soir. */
type Exclusions = { portes: string[]; concepts: string[]; graines: number[] };

/**
 * Compose 3 portes. Sans [bonus], c'est le lot du matin ; avec, ce sont des portes en plus
 * pour ce soir (« d'autres portes »), qui ne touchent pas aux concepts à prolonger demain.
 */
async function composer(lot: Json, profil: Json, bonus?: Exclusions) {
  const userId = lot.user_id;
  const filtreUser = <T extends { is: Json; eq: Json }>(q: T) => (userId ? q.eq("user_id", userId) : q.is("user_id", null));

  const { data: anciens } = await filtreUser(
    admin.from("chevet_lots").select("jour, contenu").neq("id", lot.id).order("jour", { ascending: false }).limit(14),
  );
  const portesPassees = [
    ...(bonus?.portes ?? []),
    ...(anciens ?? []).flatMap((l: Json) => (l.contenu?.portes ?? []).map((p: Json) => p.titre)),
  ];

  const { data: vus } = await filtreUser(
    admin.from("chevet_concepts").select("titre, wikipedia_url").order("decouvert_le", { ascending: false }).limit(30),
  );
  const conceptsVus = [...(bonus?.concepts ?? []), ...(vus ?? []).map((c: Json) => c.titre)];

  // Concepts aimés (un cœur sur une carte) et concepts que le lecteur veut prolonger demain.
  const { data: aimesBruts } = await filtreUser(
    admin.from("chevet_concepts").select("titre, wikipedia_url").eq("aime", true)
      .order("decouvert_le", { ascending: false }).limit(20),
  );
  const aimes: Json[] = aimesBruts ?? [];
  let prolonges: Json[] = [];
  if (!bonus) {
    const { data } = await filtreUser(
      admin.from("chevet_concepts").select("id, titre, wikipedia_url, prolonger_vers").eq("prolonger", true)
        .order("decouvert_le", { ascending: false }).limit(3),
    );
    prolonges = data ?? [];
  }

  // Candidats du terrier : liens sortants des concepts à prolonger, de 2 concepts aimés et des derniers explorés.
  const departs: Json[] = [];
  for (const c of [...prolonges, ...melanger(aimes).slice(0, 2), ...(vus ?? []).slice(0, 3)]) {
    if (c.wikipedia_url && !departs.some((d) => d.wikipedia_url === c.wikipedia_url)) departs.push(c);
  }
  const candidatsTerrier: string[] = [];
  for (const c of departs.slice(0, 5)) {
    const m = (c.wikipedia_url ?? "").match(/https:\/\/(\w+)\.wikipedia\.org\/wiki\/(.+)$/);
    if (!m) continue;
    try {
      const liens = await liensSortants(decodeURIComponent(m[2]).replaceAll("_", " "), m[1]);
      candidatsTerrier.push(`Depuis « ${c.titre} » : ${melanger(liens).slice(0, 25).join(" ; ")}`);
    } catch (_) { /* tant pis */ }
  }

  let curiosites: Json[] = [];
  if (userId) {
    const { data } = await admin.from("chevet_curiosites").select("id, texte, url")
      .eq("user_id", userId).eq("statut", "en_attente").limit(3);
    curiosites = data ?? [];
  }

  const flux = await lireFlux(profil.sources_rss ?? []);
  const actu = flux.articles.slice(0, 25).map((a) => `- [${a.media}] ${a.titre} — ${a.resume.slice(0, 120)}`).join("\n");

  const grainesUtilisees = new Set<number>([
    ...(bonus?.graines ?? []),
    ...(anciens ?? []).flatMap((l: Json) => (l.contenu?.graines ?? []) as number[]),
  ]);
  const graines = (profil.graines ?? [])
    .map((g: Json, index: number) => ({ ...g, index }))
    .filter((g: Json) => !grainesUtilisees.has(g.index));
  const premierSoir = portesPassees.length === 0;

  const consigne = `${portraitLecteur(profil)}

${consigneThemes(profil)}
${texteFiche(profil) ? "" : `Centres d'intérêt détaillés : ${JSON.stringify(profil.interets)}`}

Graines (questions posées par le lecteur, pas encore traitées) :
${graines.map((g: Json) => `- graine n° ${g.index} : question « ${g.question} » → titre de porte imposé « ${g.porte} » (pistes : ${g.pistes})`).join("\n") || "aucune"}

Portes déjà proposées ces derniers jours (ne pas répéter) : ${portesPassees.join(" ; ") || "aucune"}
Concepts déjà explorés (ne pas répéter) : ${conceptsVus.join(" ; ") || "aucun"}

Pistes du terrier (articles Wikipédia liés à des concepts déjà explorés) :
${candidatsTerrier.join("\n") || "aucune"}

Concepts aimés par le lecteur (un cœur sur une de leurs cartes) : ${aimes.map((c) => `« ${c.titre} »`).join(" ; ") || "aucun"}
${prolonges.length ? `Le lecteur a demandé à prolonger : ${prolonges.map((c) => `« ${c.titre} »${c.prolonger_vers ? ` (vers « ${c.prolonger_vers} »)` : ""}`).join(" ; ")}` : ""}

Curiosités notées par le lecteur : ${curiosites.map((c) => c.texte || c.url).join(" ; ") || "aucune"}

Actu du jour (sources choisies par le lecteur) :
${actu || "indisponible"}

${bonus
    ? "Le lecteur a déjà lu une porte ce soir et en veut d'autres : compose exactement 3 portes neuves, chacune ouvrant sur un tunnel de 3 concepts. Aucune ne reprend une porte ni un concept de ce soir."
    : "Compose le lot de ce soir : exactement 3 portes, chacune ouvrant sur un tunnel de 3 concepts."}
Règles :
- ${premierSoir ? "C'est le tout premier soir : fais un best of, au moins 2 portes issues des graines." : "Si des graines restent, une porte au moins en est issue."}
${prolonges.length ? "- Une porte au moins prolonge un concept que le lecteur a demandé à prolonger (type terrier), en partant de lui ou du voisin indiqué.\n" : ""}- Les portes qui prolongent le terrier partent de préférence des concepts aimés.
- Si une curiosité existe, une porte en part.
- Au plus une porte « actu » : un article du jour ouvre sur un concept de fond qui l'éclaire. Jamais de faits divers.
- Une porte peut prolonger le terrier ; une autre peut faire un saut vers un thème différent.
- Une seule porte au plus est « mystère » : son titre reste caché, on montre seulement un indice intrigant.
- Une porte issue d'une graine reprend exactement le titre de porte imposé, jamais la question, et indique son numéro dans "graine".
- Chaque concept correspond à un article Wikipédia qui existe : donne son titre exact en français et son titre exact en anglais.
  Choisis des titres canoniques et précis (« Effet de cadrage » plutôt que « Cadrage »), jamais une œuvre ou un produit sans rapport.
- Dans les titres, accroches et teaser, n'écris jamais « graine » ni de numéro interne.
- Tutoie le lecteur dans les accroches et le teaser.
- Les 3 concepts d'une porte s'enchaînent de proche en proche, du plus concret au plus large.
- Le teaser annonce en une phrase intrigante une porte possible pour demain (une graine restante si possible).

Format JSON :
{"portes":[{"type":"graine|actu|terrier|curiosite|saut","graine":null,"titre":"...","accroche":"une phrase qui intrigue","mystere":false,"indice":null,
"actu":{"media":"...","titre":"...","lien":"..."} ou null,
"concepts":[{"titre":"titre court du concept","wikipedia":"Titre exact de l'article français","wikipedia_en":"Exact English article title","theme":"${Object.keys(poidsThemes(profil)).join("|")}"}]}],
"teaserDemain":"..."}`;

  const resultat = await groq(MODELE_REDACTION, SYSTEME_CHEVET, consigne, 2500);
  const portes = (resultat.portes ?? []).slice(0, 3);
  if (portes.length === 0) throw new Error("Composition vide");

  // Vérifie que chaque article existe vraiment.
  for (const porte of portes) {
    const resolus = await Promise.all(
      (porte.concepts ?? []).slice(0, 3).map(async (c: Json) => ({
        ...c,
        wiki: await resoudreArticle(c.wikipedia ?? c.titre, c.wikipedia_en),
      })),
    );
    porte.concepts = resolus.filter((c) => c.wiki);
    porte.id = slug(porte.titre ?? "porte");
  }

  return {
    portes: portes.filter((p: Json) => p.concepts.length > 0),
    graines: portes.map((p: Json) => p.graine).filter((g: Json) => Number.isInteger(g)),
    teaserDemain: resultat.teaserDemain ?? "",
    curiosites: curiosites.map((c) => c.id),
    prolonges: prolonges.map((c) => c.id),
    flux: flux.etat,
  };
}

// ------------------------------------------------------------------ Étapes 1-3 : rédiger une porte

async function rediger(travail: Json, i: number, profil: Json) {
  const porte = travail.portes?.[i];
  if (!porte) return;
  const cles = motsDe(porte.titre, porte.accroche, porte.actu?.titre);
  for (const c of porte.concepts) {
    if (!c.extrait) c.extrait = await extrait(c.wiki.titre, c.wiki.langue, [...cles, ...motsDe(c.titre)]);
  }

  // Longueur réglée par le lecteur ; la relecture en a besoin pour borner les cartes gardées.
  const longueur = longueurCartes(profil);
  porte.cartesMin = longueur.min;
  porte.cartesMax = longueur.max;

  // Réécriture de la suite d'une porte : le lecteur a déjà lu le début du tunnel.
  const dejaLus: string[] = porte.dejaLus ?? [];
  const consigne = `${portraitLecteur(profil)}

Porte : « ${porte.titre} » — ${porte.accroche}
${porte.actu ? `Elle part de cette actu : [${porte.actu.media}] ${porte.actu.titre}` : ""}
${dejaLus.length ? `Le lecteur a déjà lu, dans cette porte : ${dejaLus.map((t) => `« ${t} »`).join(", ")}. Les concepts ci-dessous en sont la suite.` : ""}

Concepts du tunnel, dans l'ordre, avec l'article Wikipédia qui fait foi :
${porte.concepts.map((c: Json, k: number) => `### Concept ${k + 1} : ${c.titre} (thème ${c.theme})\n${c.extrait}`).join("\n\n")}

Écris les cartes de chaque concept.
Règles :
- N'utilise que des faits présents dans l'article fourni. Pas de chiffre, de date ni de nom qui n'y figure pas.
- ${longueur.min} à ${longueur.max} cartes par concept selon sa complexité, ${longueur.mots} mots au plus par carte, une idée par carte.
- Raconte : la première carte pose une scène datée, un lieu ou une personne réelle de l'article ; pas de définition de dictionnaire, pas de liste, pas de jargon.
- Au moins une carte par concept contient une date ou un chiffre de l'article.
- Relie le concept à la question de la porte : explique le pourquoi, pas seulement le quoi.
- N'invente jamais de scène, de personnage, de dialogue ni d'exemple : tout vient de l'article. S'il ne raconte pas d'histoire, pars d'un fait daté.
- Si l'article ne parle pas vraiment du concept ou de la porte, renvoie pour ce concept seulement {"titre":"...","horsSujet":true}.
- "question" : la question qui ouvre le concept et donne envie de glisser.
- ${dejaLus.length ? `Le premier concept prend la suite de « ${dejaLus.at(-1)} »` : "Le premier concept accroche la porte"} ; chaque concept suivant s'enchaîne avec le précédent.
- "aRetenir" : une phrase à retenir, 25 mots au plus.
- "aRaconter" : la version à placer dans une conversation, 35 mots au plus.
- "ordreDeGrandeur" : une phrase courte qui donne un chiffre de l'article, en lien direct avec la porte, et ce qu'il mesure (ex. « 33 cahiers, près de 3 000 pages écrites en prison ») ; jamais un nombre ou une date seuls ; null si l'article n'a aucun chiffre parlant.
- Varie les débuts de carte : au plus une carte par concept commence par une date.
- Pas de lien de cause à effet que l'article n'établit pas lui-même.
- "qcm" : une question de révision, 3 choix, la bonne en premier, et "autreAngle" qui réexplique autrement en 30 mots.
- "creuser" : 3 questions courtes qu'on aurait envie de poser après cette carte.

Exemple du ton attendu (sur un autre sujet) :
{"titre":"L'hégémonie culturelle","question":"Comment un système tient-il debout, s'il ne repose pas seulement sur la force ?",
"cartes":["1929, prison de Turi, en Italie. Antonio Gramsci, dirigeant communiste emprisonné par le régime de Mussolini, commence à remplir des cahiers d'écolier.",
"Sa question : pourquoi la révolution a-t-elle eu lieu en Russie, mais pas dans l'Europe de l'Ouest industrialisée ?",
"Sa réponse : à l'Ouest, le pouvoir ne tient pas que par la police. Il tient par le consentement : l'école, la presse, l'Église diffusent une vision du monde qui devient le « bon sens »."],
"aRetenir":"Un pouvoir solide ne se contente pas de contraindre : il rend son ordre évident.",
"ordreDeGrandeur":"33 cahiers, près de 3 000 pages écrites en prison."}

Format JSON :
{"concepts":[{"titre":"...","question":"...","cartes":["...","..."],"aRetenir":"...","aRaconter":"...","ordreDeGrandeur":"..." ou null,
"qcm":{"question":"...","choix":["bonne","fausse","fausse"],"autreAngle":"..."},"creuser":["...","...","..."]}]}`;

  const resultat = await groq(MODELE_REDACTION, SYSTEME_CHEVET, consigne, 3200, 0.5);
  porte.redaction = resultat.concepts ?? [];
}

// ------------------------------------------------------------------ Étapes 4-6 : relecture factuelle

function normaliser(texte: string): string {
  return texte
    .normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

/** Vrai si la citation vient bien de l'article (à la ponctuation près). */
function estProuve(preuve: string | undefined, source: string): boolean {
  if (!preuve) return false;
  const p = normaliser(preuve);
  const s = normaliser(source);
  if (p.length >= 25 && s.includes(p.slice(0, 120))) return true;
  const mots = p.split(" ").filter((m) => m.length > 3);
  if (mots.length < 5) return false;
  return mots.filter((m) => s.includes(m)).length / mots.length >= 0.85;
}

async function relire(travail: Json, i: number) {
  const porte = travail.portes?.[i];
  if (!porte?.redaction?.length) return;
  const valides = porte.redaction
    .map((r: Json, k: number) => ({ r, k }))
    .filter(({ r }: Json) => r && !r.horsSujet);
  if (valides.length === 0) return;

  const consigne = `Tu es relecteur factuel. Pour chaque concept, compare le texte à son article source.
Pour chaque carte, recopie mot pour mot dans "preuve" la phrase de l'article qui la prouve.
Si aucune phrase ne la prouve, réécris la carte pour qu'elle dise exactement ce que dit une phrase de l'article, ou supprime-la.
Sois intraitable sur :
- le sens exact (une négation inversée, « avec » au lieu de « sans », est une erreur grave) ;
- les liens de cause à effet que l'article n'établit pas ;
- les scènes, personnages ou exemples inventés ;
- le rôle exact des institutions et des personnes.
"ordreDeGrandeur" : garde-le seulement s'il vient de l'article et concerne la porte « ${porte.titre} », avec sa preuve ; sinon null.
"aRetenir" et "aRaconter" : retire toute affirmation que les cartes prouvées ne soutiennent pas.
Ne change pas le style.

${valides.map(({ r, k }: Json) => `### Concept ${k + 1} : ${r.titre}
Article source :
${porte.concepts[k]?.extrait ?? ""}
Texte à relire :
${JSON.stringify(r)}`).join("\n\n")}

Réponds dans le même ordre, un objet par concept relu :
{"concepts":[{"titre":"...","question":"...","cartes":[{"texte":"...","preuve":"citation exacte"}],"aRetenir":"...","aRaconter":"...",
"ordreDeGrandeur":{"texte":"...","preuve":"citation exacte"} ou null,
"qcm":{"question":"...","choix":["bonne","fausse","fausse"],"autreAngle":"..."},"creuser":["...","...","..."]}],
"corrections":["..."]}`;

  const resultat = await groq(MODELE_VERIFICATION, SYSTEME_CHEVET, consigne, 3400, 0.2);
  const relus: Json[] = Array.isArray(resultat.concepts) ? resultat.concepts : [];
  if (relus.length !== valides.length) {
    porte.corrections = ["Relecture inutilisable : texte d'origine conservé."];
    return;
  }

  // Fourchette réglée par le lecteur (2 à 4 par défaut) : en dessous du minimum, le concept tombe.
  const minimum = Math.max(1, Math.min(2, Number(porte.cartesMin) || 2));
  const maximum = Math.max(minimum, Number(porte.cartesMax) || 5);
  let retirees = 0;
  valides.forEach(({ r: original, k }: Json, n: number) => {
    const relu = relus[n];
    const source: string = porte.concepts[k]?.extrait ?? "";
    const cartes = (relu.cartes ?? []).filter((c: Json) => {
      const ok = typeof c === "object" && c.texte && estProuve(c.preuve, source);
      if (!ok) retirees++;
      return ok;
    }).map((c: Json) => c.texte).slice(0, maximum);
    const odg = relu.ordreDeGrandeur;
    // La relecture ne touche qu'aux cartes, aux synthèses et au chiffre ; le reste vient de la rédaction.
    porte.redaction[k] = cartes.length < minimum ? { titre: original.titre, horsSujet: true } : {
      ...original,
      cartes,
      aRetenir: relu.aRetenir || cartes.at(-1),
      aRaconter: relu.aRaconter || relu.aRetenir || cartes[0],
      ordreDeGrandeur: odg && typeof odg === "object" && estProuve(odg.preuve, source) ? odg.texte : null,
    };
  });
  porte.corrections = [...(resultat.corrections ?? []), `${retirees} carte(s) sans preuve retirée(s)`];
}

// ------------------------------------------------------------------ Étape 7 : pépite et assemblage

/** Les concepts rédigés et relus d'une porte, au format de l'appli. */
function conceptsRediges(p: Json): Json[] {
  return (p.redaction ?? []).map((r: Json, k: number) => {
    if (!r || r.horsSujet || !(r.cartes ?? []).length) return null;
    const source = p.concepts[k] ?? p.concepts[0];
    const choix: string[] = r.qcm?.choix ?? [];
    const ordre = melanger(choix.map((_, idx) => idx));
    return {
      id: slug(source.wiki.titre),
      titre: r.titre ?? source.titre,
      theme: source.theme ?? "Histoire & sociétés",
      question: r.question ?? "",
      cartes: (r.cartes ?? []).map((t: string) => ({ texte: t })),
      aRetenir: r.aRetenir ?? "",
      aRaconter: r.aRaconter ?? "",
      ordreDeGrandeur: r.ordreDeGrandeur ?? null,
      source: { titre: `${source.wiki.titre}, Wikipédia`, url: source.wiki.url },
      qcm: {
        question: r.qcm?.question ?? "",
        choix: ordre.map((idx) => choix[idx]),
        bonne: ordre.indexOf(0),
        autreAngle: r.qcm?.autreAngle ?? "",
      },
      creuser: (r.creuser ?? []).slice(0, 3),
    };
  }).filter((c: Json) => c !== null);
}

async function finaliser(lot: Json, travail: Json) {
  const extraits = (travail.portes ?? []).flatMap((p: Json) => p.concepts.map((c: Json) => `### ${c.titre}\n${c.extrait?.slice(0, 900)}`));
  const pepite = await groq(
    MODELE_RELECTURE,
    SYSTEME_CHEVET,
    `Dans ces extraits d'articles, choisis le fait le plus renversant, et écris-le en 40 mots au plus,
comme une pépite qu'on a envie de raconter. Uniquement un fait présent dans les extraits.
${extraits.join("\n\n")}
Format JSON : {"texte":"..."}`,
    700,
    0.4,
  );

  const portes = (travail.portes ?? [])
    .filter((p: Json) => p.redaction?.some((r: Json) => r && !r.horsSujet))
    .map((p: Json) => ({
      id: p.id,
      titre: p.titre,
      accroche: p.accroche,
      mystere: !!p.mystere,
      indice: p.indice ?? null,
      concepts: conceptsRediges(p),
    }));

  if (portes.length === 0) throw new Error("Aucune porte rédigée");

  // Les curiosités utilisées sortent de la boîte.
  if (travail.curiosites?.length) {
    await admin.from("chevet_curiosites").update({ statut: "porte_generee" }).in("id", travail.curiosites);
  }
  // Les concepts à prolonger ont nourri ce lot : la demande est servie.
  if (travail.prolonges?.length) {
    await admin.from("chevet_concepts").update({ prolonger: false, prolonger_vers: null }).in("id", travail.prolonges);
  }

  return {
    jour: lot.jour,
    portes,
    pepite: { texte: pepite.texte ?? "" },
    teaserDemain: travail.teaserDemain ?? "",
    graines: travail.graines ?? [],
  };
}

// ------------------------------------------------------------------ Moteur

async function profilDe(userId: string | null): Promise<Json> {
  const { data: modele } = await admin.from("chevet_profil").select("*").is("user_id", null).limit(1).single();
  if (!userId) return modele;
  const { data: perso } = await admin.from("chevet_profil").select("*").eq("user_id", userId).maybeSingle();
  if (perso) return perso;
  // Premier passage : le profil de l'utilisateur part du modèle.
  const { id: _id, user_id: _u, mis_a_jour_le: _m, ...reste } = modele;
  const { data: cree } = await admin.from("chevet_profil").insert({ ...reste, user_id: userId }).select().single();
  return cree ?? modele;
}

async function lotDuJour(userId: string | null): Promise<Json> {
  const jour = jourParis();
  const requete = admin.from("chevet_lots").select("*").eq("jour", jour);
  const { data } = await (userId ? requete.eq("user_id", userId) : requete.is("user_id", null)).maybeSingle();
  if (data) return data;
  const { data: cree, error } = await admin.from("chevet_lots")
    .insert({ user_id: userId, jour, statut: "composition", etape: 0 })
    .select().single();
  if (error) {
    // Un autre appel l'a créé entre-temps.
    const { data: existant } = await (userId
      ? admin.from("chevet_lots").select("*").eq("jour", jour).eq("user_id", userId)
      : admin.from("chevet_lots").select("*").eq("jour", jour).is("user_id", null)).single();
    return existant;
  }
  return cree;
}

/** Pose le verrou court d'un lot : une seule étape à la fois. Faux si une étape tourne déjà. */
async function verrouiller(lot: Json): Promise<boolean> {
  const maintenant = new Date();
  const { data } = await admin.from("chevet_lots")
    .update({ verrou_jusqua: new Date(maintenant.getTime() + 140_000).toISOString() })
    .eq("id", lot.id)
    .eq("etape", lot.etape)
    .or(`verrou_jusqua.is.null,verrou_jusqua.lt."${maintenant.toISOString()}"`)
    .select().maybeSingle();
  return !!data;
}

/** Fait avancer le lot d'une étape. Renvoie l'état public du lot. */
async function avancer(userId: string | null) {
  const lot = await lotDuJour(userId);
  if (lot.statut === "pret" || lot.statut === "erreur") return etatPublic(lot);
  if (!(await verrouiller(lot))) return { ...etatPublic(lot), occupe: true };

  const profil = await profilDe(userId);
  const travail = lot.travail ?? {};
  const etape = lot.etape;
  const maj: Json = { verrou_jusqua: null };

  try {
    if (etape === 0) {
      Object.assign(travail, await composer(lot, profil));
      maj.statut = "en_cours";
    } else if (etape <= 3) {
      await rediger(travail, etape - 1, profil);
    } else if (etape <= 6) {
      await relire(travail, etape - 4);
    } else {
      maj.contenu = await finaliser(lot, travail);
      maj.statut = "pret";
      maj.pret_le = new Date().toISOString();
    }
    maj.etape = etape + 1;
    maj.erreur = null;
    travail.tentatives = 0;
  } catch (e) {
    maj.erreur = String(e).slice(0, 500);
    if (!(e instanceof LimiteGroq)) {
      travail.tentatives = (travail.tentatives ?? 0) + 1;
      if (travail.tentatives >= 4) maj.statut = "erreur";
    }
  }
  maj.travail = travail;

  // Si le lot a été remis à zéro pendant l'étape (réglages modifiés), le résultat est jeté.
  const { data: apres } = await admin.from("chevet_lots").update(maj)
    .eq("id", lot.id).eq("genere_le", lot.genere_le).select().maybeSingle();
  if (!apres) return { ...etatPublic(lot), occupe: true };
  return etatPublic(apres);
}

function etatPublic(lot: Json) {
  return {
    jour: lot.jour,
    statut: lot.statut,
    etape: lot.etape,
    total: NB_ETAPES,
    erreur: lot.erreur ?? null,
    lot: lot.statut === "pret" ? lot.contenu : null,
  };
}

// ------------------------------------------------------------------ Réglages : profil

const CHAMPS_REGLABLES = [
  "niveaux", "a_eviter", "sources_rss", "poids_themes", "fiche", "creusages_par_soir", "declencheur", "heure_coucher",
  "mots_par_carte", "cartes_par_concept",
];

/** Le profil réglable de l'utilisateur. La fiche est tirée du profil de départ au premier passage. */
async function profilPublic(userId: string) {
  const profil = await profilDe(userId);
  if (!Array.isArray(profil.fiche) || profil.fiche.length === 0) {
    profil.fiche = ficheDepuis(profil);
    await admin.from("chevet_profil").update({ fiche: profil.fiche }).eq("id", profil.id);
  }
  profil.poids_themes = poidsThemes(profil);
  return { profil: Object.fromEntries(CHAMPS_REGLABLES.map((c) => [c, profil[c] ?? null])) };
}

/** Enregistre les champs réglés dans l'appli, bornés et nettoyés. */
async function enregistrerProfil(userId: string, valeurs: Json) {
  await profilDe(userId);
  const v = valeurs && typeof valeurs === "object" ? valeurs : {};
  const texte = (x: unknown, max: number) => String(x ?? "").trim().slice(0, max);
  const borne = (x: unknown, min: number, max: number, defaut: number) => {
    const n = Math.round(Number(x));
    return Number.isFinite(n) ? Math.max(min, Math.min(max, n)) : defaut;
  };
  const maj: Json = { mis_a_jour_le: new Date().toISOString() };

  if (v.niveaux && typeof v.niveaux === "object") {
    maj.niveaux = Object.fromEntries(
      Object.entries(v.niveaux).map(([k, n]) => [texte(k, 60), texte(n, 300)]).filter(([k]) => k),
    );
  }
  if (Array.isArray(v.a_eviter)) {
    maj.a_eviter = v.a_eviter.map((x: unknown) => texte(x, 120)).filter(Boolean).slice(0, 40);
  }
  if (Array.isArray(v.sources_rss)) {
    maj.sources_rss = v.sources_rss
      .filter((s: Json) => s && /^https?:\/\//.test(String(s.url ?? "")))
      .map((s: Json) => ({
        nom: texte(s.nom || s.url, 80),
        url: texte(s.url, 500),
        actif: s.actif !== false,
        ajoutee: !!s.ajoutee,
      }))
      .slice(0, 30);
  }
  if (v.poids_themes && typeof v.poids_themes === "object") {
    maj.poids_themes = Object.fromEntries(
      Object.entries(v.poids_themes).map(([t, p]) => [texte(t, 60), borne(p, 0, 100, 50)]).filter(([t]) => t),
    );
  }
  if (Array.isArray(v.fiche)) {
    maj.fiche = v.fiche
      .map((r: Json) => ({
        titre: texte(r?.titre, 80),
        puces: (Array.isArray(r?.puces) ? r.puces : []).map((p: unknown) => texte(p, 400)).filter(Boolean).slice(0, 40),
      }))
      .filter((r: Json) => r.titre || r.puces.length)
      .slice(0, 30);
  }
  if (v.creusages_par_soir !== undefined) maj.creusages_par_soir = borne(v.creusages_par_soir, 1, 10, 3);
  if ([25, 40, 60].includes(Number(v.mots_par_carte))) maj.mots_par_carte = Number(v.mots_par_carte);
  if (typeof v.cartes_par_concept === "string" && v.cartes_par_concept in FOURCHETTES_CARTES) {
    maj.cartes_par_concept = v.cartes_par_concept;
  }
  if (typeof v.declencheur === "string") maj.declencheur = texte(v.declencheur, 200);
  if (typeof v.heure_coucher === "string" && /^\d{2}:\d{2}/.test(v.heure_coucher)) {
    maj.heure_coucher = v.heure_coucher.slice(0, 5);
  }

  const { error } = await admin.from("chevet_profil").update(maj).eq("user_id", userId);
  if (error) throw new Error(error.message);
  return await profilPublic(userId);
}

// ------------------------------------------------------------------ Réglages : lot entier

/** Remet le lot du jour à zéro ; les appels « avancer » le recomposent avec le profil à jour. */
async function regenerer(userId: string) {
  const lot = await lotDuJour(userId);
  const anciennes: string[] = lot.travail?.curiosites ?? [];
  if (anciennes.length) {
    // Les curiosités de l'ancien lot retournent dans la boîte pour le nouveau.
    await admin.from("chevet_curiosites").update({ statut: "en_attente" })
      .in("id", anciennes).eq("statut", "porte_generee");
  }
  const { data } = await admin.from("chevet_lots").update({
    statut: "composition",
    etape: 0,
    travail: {},
    contenu: {},
    erreur: null,
    verrou_jusqua: null,
    genere_le: new Date().toISOString(),
    pret_le: null,
  }).eq("id", lot.id).select().single();
  return etatPublic(data ?? lot);
}

// ------------------------------------------------------------------ Réglages : suite d'une porte

/** Étape 0 de la suite : choisit les concepts qui prolongent ceux déjà lus. */
async function composerSuite(lot: Json, porte: Json, gardes: Json[], profil: Json) {
  const aEcrire = CONCEPTS_PAR_PORTE - gardes.length;
  const dernier = gardes[gardes.length - 1];

  const { data: vus } = await admin.from("chevet_concepts").select("titre")
    .eq("user_id", lot.user_id).order("decouvert_le", { ascending: false }).limit(30);

  let pistes: string[] = [];
  const m = (dernier.source?.url ?? "").match(/https:\/\/(\w+)\.wikipedia\.org\/wiki\/(.+)$/);
  if (m) {
    try {
      pistes = melanger(await liensSortants(decodeURIComponent(m[2]).replaceAll("_", " "), m[1])).slice(0, 40);
    } catch (_) { /* tant pis */ }
  }

  const consigne = `${portraitLecteur(profil)}

${consigneThemes(profil)}

Porte : « ${porte.titre} » — ${porte.accroche}
Le lecteur a déjà lu, dans l'ordre : ${gardes.map((c) => `« ${c.titre} »`).join(", ")}.
Concepts explorés les autres soirs (ne pas répéter) : ${(vus ?? []).map((c: Json) => c.titre).join(" ; ") || "aucun"}
Articles Wikipédia liés à « ${dernier.titre} » : ${pistes.join(" ; ") || "aucun"}

Le lecteur vient de changer ses réglages en pleine lecture. Propose ${aEcrire} concept(s) pour la suite du tunnel, qui tiennent compte du portrait ci-dessus.
Règles :
- Ils s'enchaînent de proche en proche à partir de « ${dernier.titre} », du plus concret au plus large, et restent dans la question de la porte.
- Chaque concept correspond à un article Wikipédia qui existe : donne son titre exact en français et son titre exact en anglais, canonique et précis.
- Aucun concept déjà lu.

Format JSON :
{"concepts":[{"titre":"titre court du concept","wikipedia":"Titre exact de l'article français","wikipedia_en":"Exact English article title","theme":"${Object.keys(poidsThemes(profil)).join("|")}"}]}`;

  const resultat = await groq(MODELE_REDACTION, SYSTEME_CHEVET, consigne, 1500);
  const dejaLa = new Set(gardes.map((c) => c.id));
  const resolus = await Promise.all(
    (resultat.concepts ?? []).slice(0, aEcrire).map(async (c: Json) => ({
      ...c,
      wiki: await resoudreArticle(c.wikipedia ?? c.titre, c.wikipedia_en),
    })),
  );
  const concepts = resolus.filter((c) => c.wiki && !dejaLa.has(slug(c.wiki.titre)));
  if (concepts.length === 0) throw new Error("Aucun concept trouvé pour la suite");

  return {
    titre: porte.titre,
    accroche: porte.accroche,
    actu: null,
    concepts,
    dejaLus: gardes.map((c) => c.titre),
  };
}

/** Avance d'une étape la réécriture des concepts d'une porte après les « gardes » premiers. */
async function suite(userId: string, porteId: string, nbGardes: number, demande: string) {
  const lot = await lotDuJour(userId);
  if (lot.statut !== "pret") return { statut: "indisponible", total: NB_ETAPES_SUITE };
  const porte = (lot.contenu?.portes ?? []).find((p: Json) => p.id === porteId);
  if (!porte) return { statut: "indisponible", total: NB_ETAPES_SUITE };

  const n = Math.max(1, Math.min(Math.floor(nbGardes) || 1, porte.concepts.length));
  if (n >= CONCEPTS_PAR_PORTE) return { statut: "rien", total: NB_ETAPES_SUITE };

  const travail = lot.travail ?? {};
  const cle = `${porteId}:${n}:${demande}`;
  if (travail.suite?.cle !== cle) travail.suite = { cle, etape: 0, tentatives: 0 };
  const s = travail.suite;
  const etat = (statut: string) => ({
    statut,
    etape: s.etape,
    total: NB_ETAPES_SUITE,
    erreur: s.erreur ?? null,
    porte: statut === "pret" ? s.porte : null,
  });
  if (s.fini) return etat("pret");
  if (s.abandon) return etat("erreur");
  if (!(await verrouiller(lot))) return { ...etat("en_cours"), occupe: true };

  const profil = await profilDe(userId);
  const maj: Json = { verrou_jusqua: null };
  try {
    if (s.etape === 0) {
      s.pt = await composerSuite(lot, porte, porte.concepts.slice(0, n), profil);
    } else if (s.etape === 1) {
      await rediger({ portes: [s.pt] }, 0, profil);
    } else if (s.etape === 2) {
      await relire({ portes: [s.pt] }, 0);
    } else {
      const nouveaux = conceptsRediges(s.pt);
      if (nouveaux.length === 0) throw new Error("Suite vide après relecture");
      const contenu = lot.contenu;
      const cible = contenu.portes.find((p: Json) => p.id === porteId);
      cible.concepts = [...cible.concepts.slice(0, n), ...nouveaux];
      maj.contenu = contenu;
      s.porte = cible;
      s.fini = true;
      delete s.pt;
    }
    s.etape += 1;
    s.erreur = null;
    s.tentatives = 0;
  } catch (e) {
    s.erreur = String(e).slice(0, 500);
    if (!(e instanceof LimiteGroq)) {
      s.tentatives = (s.tentatives ?? 0) + 1;
      if (s.tentatives >= 3) s.abandon = true;
    }
  }
  maj.travail = travail;

  const { data: apres } = await admin.from("chevet_lots").update(maj)
    .eq("id", lot.id).eq("genere_le", lot.genere_le).select("id").maybeSingle();
  if (!apres) return { statut: "indisponible", total: NB_ETAPES_SUITE };
  return etat(s.fini ? "pret" : s.abandon ? "erreur" : "en_cours");
}

// ------------------------------------------------------------------ Menu : d'autres portes

/** Ce que les portes bonus ne doivent pas répéter : les portes, concepts et graines de ce soir. */
function exclusionsDuSoir(lot: Json): Exclusions {
  const portes: Json[] = lot.contenu?.portes ?? [];
  return {
    portes: portes.map((p) => p.titre),
    concepts: portes.flatMap((p) => (p.concepts ?? []).map((c: Json) => c.titre)),
    graines: (lot.contenu?.graines ?? []) as number[],
  };
}

/**
 * « D'autres portes », une étape par appel. Sans [porteId] : compose 3 portes neuves et renvoie
 * leurs titres. Avec : rédige, relit puis ajoute au lot la porte choisie, et la renvoie.
 * [demande] identifie la demande : une nouvelle recompose de zéro.
 */
async function bonus(userId: string, demande: string, porteId: string | null) {
  const lot = await lotDuJour(userId);
  if (lot.statut !== "pret") return { statut: "indisponible", total: NB_ETAPES_BONUS };

  const travail = lot.travail ?? {};
  if (travail.bonus?.cle !== demande) travail.bonus = { cle: demande, tentatives: 0, etapes: {}, ecrites: {} };
  const b = travail.bonus;
  const resume = (p: Json) => ({
    id: p.id,
    titre: p.titre,
    accroche: p.accroche,
    mystere: !!p.mystere,
    indice: p.indice ?? null,
  });
  const etat = (statut: string, extra: Json = {}) => ({
    statut,
    etape: porteId ? (b.etapes?.[porteId] ?? 0) : 0,
    total: NB_ETAPES_BONUS,
    erreur: b.erreur ?? null,
    ...extra,
  });

  // Déjà fait : rien à recalculer.
  if (!porteId && b.portes) return etat("choix", { portes: b.portes.map(resume) });
  if (porteId && b.ecrites?.[porteId]) return etat("pret", { porte: b.ecrites[porteId] });
  if (b.abandon) return etat("erreur");
  if (porteId && !b.portes?.some((p: Json) => p.id === porteId)) return etat("indisponible");
  if (!(await verrouiller(lot))) return { ...etat("en_cours"), occupe: true };

  const profil = await profilDe(userId);
  const maj: Json = { verrou_jusqua: null };
  let resultat: Json;
  try {
    if (!porteId) {
      const compo = await composer(lot, profil, exclusionsDuSoir(lot));
      if (compo.portes.length === 0) throw new Error("Aucune porte composée");
      b.portes = compo.portes;
      if (compo.curiosites.length) {
        await admin.from("chevet_curiosites").update({ statut: "porte_generee" }).in("id", compo.curiosites);
      }
      resultat = etat("choix", { portes: b.portes.map(resume) });
    } else {
      const pt = b.portes.find((p: Json) => p.id === porteId);
      const etape = b.etapes[porteId] ?? 0;
      if (etape === 0) {
        await rediger({ portes: [pt] }, 0, profil);
      } else if (etape === 1) {
        await relire({ portes: [pt] }, 0);
      } else {
        const concepts = conceptsRediges(pt);
        if (concepts.length === 0) throw new Error("Porte vide après relecture");
        const contenu = lot.contenu;
        let id = pt.id;
        while ((contenu.portes ?? []).some((p: Json) => p.id === id)) id += "-b";
        const porte = { ...resume(pt), id, concepts, bonus: true };
        contenu.portes = [...(contenu.portes ?? []), porte];
        maj.contenu = contenu;
        b.ecrites[porteId] = porte;
      }
      b.etapes[porteId] = etape + 1;
      resultat = b.ecrites[porteId] ? etat("pret", { porte: b.ecrites[porteId] }) : etat("en_cours");
    }
    b.erreur = null;
    b.tentatives = 0;
  } catch (e) {
    b.erreur = String(e).slice(0, 500);
    if (!(e instanceof LimiteGroq)) {
      b.tentatives = (b.tentatives ?? 0) + 1;
      if (b.tentatives >= 3) b.abandon = true;
    }
    resultat = etat(b.abandon ? "erreur" : "en_cours");
  }
  maj.travail = travail;

  const { data: apres } = await admin.from("chevet_lots").update(maj)
    .eq("id", lot.id).eq("genere_le", lot.genere_le).select("id").maybeSingle();
  if (!apres) return etat("indisponible");
  return resultat;
}

// ------------------------------------------------------------------ Menu : voisins d'un concept

function titreDepuisUrl(url: string | null | undefined): string {
  const m = (url ?? "").match(/\/wiki\/(.+)$/);
  return m ? decodeURIComponent(m[1]).replaceAll("_", " ") : "";
}

/** Articles liés à un concept, pas encore explorés, choisis par l'IA ; gardés en cache par concept. */
async function voisins(userId: string, url: string, titre: string, slugConcept: string) {
  if (slugConcept) {
    const { data } = await admin.from("chevet_concepts").select("voisins")
      .eq("user_id", userId).eq("slug", slugConcept).maybeSingle();
    if (Array.isArray(data?.voisins) && data.voisins.length) return { voisins: data.voisins };
  }
  const m = url.match(/https:\/\/(\w+)\.wikipedia\.org\/wiki\/(.+)$/);
  if (!m) return { voisins: [] };
  const langue = m[1];
  const liens = await liensSortants(decodeURIComponent(m[2]).replaceAll("_", " "), langue);

  const { data: vus } = await admin.from("chevet_concepts").select("titre, wikipedia_url").eq("user_id", userId);
  const dejaVus = new Set(
    (vus ?? []).flatMap((c: Json) => [normaliser(c.titre ?? ""), normaliser(titreDepuisUrl(c.wikipedia_url))]),
  );
  const candidats = liens.filter((t) =>
    !dejaVus.has(normaliser(t)) && !/^(Liste|Chronologie|Portail|Catégorie|Aide|Modèle|\d)/.test(t)
  );
  if (candidats.length === 0) return { voisins: [] };

  const profil = await profilDe(userId);
  let choisis: { titre: string; accroche: string }[] = [];
  try {
    const r = await groq(
      MODELE_RELECTURE,
      SYSTEME_CHEVET,
      `${portraitLecteur(profil)}

Le lecteur a exploré « ${titre} ». Voici des articles Wikipédia liés : ${melanger(candidats).slice(0, 80).join(" ; ")}
Choisis les 6 qui lui donneraient le plus envie de continuer : des sujets concrets, des histoires, des mécanismes ;
pas de listes, ni de pays, de villes ou d'années trop générales. Recopie chaque titre exactement.
Pour chacun, une accroche de 12 mots au plus qui tutoie le lecteur.
Format JSON : {"voisins":[{"titre":"titre exact","accroche":"..."}]}`,
      900,
      0.4,
    );
    choisis = (r.voisins ?? []).filter((v: Json) => v?.titre && candidats.includes(v.titre));
  } catch (_) {
    // Sans l'IA : un tirage au hasard parmi les liens.
  }
  if (choisis.length === 0) choisis = melanger(candidats).slice(0, 6).map((t) => ({ titre: t, accroche: "" }));

  const resultat = choisis.slice(0, 6).map((v) => ({
    titre: v.titre,
    accroche: String(v.accroche ?? "").slice(0, 140),
    url: urlArticle(v.titre, langue),
  }));
  if (slugConcept && resultat.length) {
    await admin.from("chevet_concepts").update({ voisins: resultat }).eq("user_id", userId).eq("slug", slugConcept);
  }
  return { voisins: resultat };
}

// ------------------------------------------------------------------ Réglages : nouvelle source

/** Vérifie qu'une URL donne un flux RSS ou Atom lisible, et propose un nom. */
async function testerFlux(url: string) {
  let adresse: URL;
  try {
    adresse = new URL(url.trim());
  } catch (_) {
    return { ok: false, erreur: "Adresse invalide" };
  }
  if (adresse.protocol !== "https:" && adresse.protocol !== "http:") return { ok: false, erreur: "Adresse invalide" };
  try {
    const r = await fetch(adresse, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(8000) });
    if (!r.ok) return { ok: false, erreur: `Le site répond ${r.status}` };
    const xml = await r.text();
    const articles = [...xml.matchAll(/<(item|entry)[\s>][\s\S]*?<\/\1>/g)];
    if (articles.length === 0) return { ok: false, erreur: "Pas de flux RSS lisible à cette adresse" };
    const entete = xml.slice(0, articles[0].index ?? 0);
    return {
      ok: true,
      nom: decoder(balise(entete, "title")) || adresse.hostname.replace(/^www\./, ""),
      url: adresse.toString(),
      articles: articles.length,
      dernier: decoder(balise(articles[0][0], "title")),
    };
  } catch (e) {
    return { ok: false, erreur: `Flux injoignable (${String(e).slice(0, 120)})` };
  }
}

// ------------------------------------------------------------------ Point d'entrée

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const corps = await req.json().catch(() => ({}));
    const action = corps.action ?? "avancer";

    if (action === "cron") {
      const { data: profils } = await admin.from("chevet_profil").select("user_id").not("user_id", "is", null);
      const etats = [];
      for (const p of profils ?? []) etats.push(await avancer(p.user_id));
      return reponse({ utilisateurs: etats.length, etats });
    }

    if (action === "modele") {
      return reponse(await avancer(null));
    }

    // Les autres actions concernent l'utilisateur connecté.
    const jeton = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const { data: utilisateur } = await admin.auth.getUser(jeton);
    if (!utilisateur?.user) return reponse({ erreur: "Utilisateur non connecté" }, 401);
    const userId = utilisateur.user.id;

    switch (action) {
      case "avancer":
        return reponse(await avancer(userId));
      case "profil":
        return reponse(await profilPublic(userId));
      case "enregistrer_profil":
        return reponse(await enregistrerProfil(userId, corps.profil));
      case "regenerer":
        return reponse(await regenerer(userId));
      case "suite":
        return reponse(await suite(userId, String(corps.porte ?? ""), Number(corps.gardes ?? 1), String(corps.demande ?? "")));
      case "tester_flux":
        return reponse(await testerFlux(String(corps.url ?? "")));
      case "bonus":
        return reponse(await bonus(userId, String(corps.demande ?? ""), corps.porte ? String(corps.porte) : null));
      case "voisins":
        return reponse(await voisins(
          userId,
          String(corps.url ?? ""),
          String(corps.titre ?? ""),
          String(corps.slug ?? ""),
        ));
      default:
        return reponse({ erreur: `Action inconnue : ${action}` }, 400);
    }
  } catch (e) {
    return reponse({ erreur: String(e) }, 500);
  }
});
