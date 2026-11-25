// Chevet : préparation du lot du jour, une étape par appel.
//
// Étapes : 0 composer les 3 portes (profil + actu RSS + terrier Wikipédia + curiosités)
//          1-3 rédiger les cartes de chaque porte à partir des articles Wikipédia
//          4-6 vérification factuelle de chaque porte contre les articles (sans réécriture)
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
//                                         (rédaction, vérification, assemblage) ; elle rejoint le lot.
//   {"action":"voisins","url":"...","titre":"...","slug":"..."}
//                                         articles Wikipédia liés à un concept, pas encore explorés,
//                                         choisis par l'IA pour le graphe du terrier (gardés en cache).
//
// Actions de l'exploration (avec le JWT de l'utilisateur) :
//   {"action":"crans","porte":{titre,accroche},"depuis":{id,titre,url,theme,profondeur},"chemin":[...],"court":false}
//                                         « plus loin » et « à côté » depuis un concept, une étape par
//                                         appel ; les titres arrivent dès la première étape.
//   {"action":"marque_page","porteTitre":"...","dernier":{id,titre,url},"suite":[concepts]}
//                                         garde l'endroit où le lecteur s'arrête ; {"effacer":true} l'oublie.
//
// Le lot du matin tient compte du marque-page (porte « reprise »), des portes notées 8 ou plus
// (leur suite) et, environ un jour sur trois, de l'éphéméride de Wikipédia. Chaque carte garde la
// phrase de l'article qui la prouve ; un contre-point et un pont s'ajoutent quand l'article les porte.
//
// Tout est idempotent : un lot prêt ne bouge plus, donc un appel en trop ne coûte rien.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { appelerJson, LimiteLLM, type Role } from "../_shared/llm.ts";

// Les modèles se choisissent par rôle, avec bascule automatique Gemini / Groq (voir _shared/llm.ts).
const MODELE_REDACTION: Role = "redaction";
const MODELE_RELECTURE: Role = "leger";
const MODELE_VERIFICATION: Role = "verification";
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

function ia(
  role: Role,
  systeme: string,
  utilisateur: string,
  maxTokens = 3000,
  temperature = 0.7,
): Promise<Json> {
  return appelerJson({ role, fonction: "chevet-lot", systeme, utilisateur, maxTokens, temperature });
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

/**
 * Vrai pour une ligne de bibliographie ou de navigation (ouvrage, article cité, portail) :
 * le modèle prend sinon un titre d'ouvrage pour un fait (« la pénurie jusqu'en 2030 »).
 */
function estReference(p: string): boolean {
  return /\b(ISBN|ISSN|DOI|doi:|OCLC|PMID|JSTOR)\b|lire en ligne|consulté le|retrieved|archived from|\bvol\.\s*\d|\bno\s+\d+\s*,|\bp\.\s*\d+|\bpp\.\s*\d+|^Portail\b|^Portal\b|\b\d{2,4}\s*p\.\s*\(/i
    .test(p);
}

async function extrait(titre: string, langue: string, motsCles: string[], longueur = 3000): Promise<string> {
  const d = await wiki(
    { action: "query", prop: "extracts", explaintext: "1", titles: titre, redirects: "1" },
    langue,
  );
  const texte = ((d.query?.pages?.[0]?.extract as string) ?? "").replace(/\n={2,}[^=\n]+={2,}\n/g, "\n");
  const paragraphes = texte.split(/\n+/).map((p) => p.trim().slice(0, 900))
    .filter((p) => p.length > 60 && !estReference(p));
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

/** Liens internes d'un article. [limite] : "120" par défaut (ordre alphabétique), "max" pour tous (500). */
async function liensSortants(titre: string, langue: string, limite = "120"): Promise<string[]> {
  const d = await wiki(
    { action: "query", prop: "links", plnamespace: "0", pllimit: limite, titles: titre, redirects: "1" },
    langue,
  );
  return (d.query?.pages?.[0]?.links ?? []).map((l: Json) => l.title as string);
}

// ------------------------------------------------------------------ Preuve en un tap

/**
 * Retrouve dans l'article la phrase (ou les deux phrases) qui correspond le mieux à la citation
 * donnée par le vérificateur, et le paragraphe autour, coupé à environ 600 caractères.
 * Le passage est toujours une sous-chaîne exacte du contexte : l'appli le surligne tel quel.
 */
function localiser(preuve: string | undefined, source: string): { passage: string; contexte: string } | null {
  if (!preuve || !source) return null;
  const mots = new Set(normaliser(preuve).split(" ").filter((m) => m.length > 3));
  if (mots.size === 0) return null;
  let meilleur: { score: number; bloc: string; p: string } | null = null;
  for (const p of source.split(/\n+/).map((x) => x.trim()).filter(Boolean)) {
    const phrases = p.match(/[^.!?…]+[.!?…]+[»")\]]*\s*|[^.!?…]+$/g) ?? [p];
    for (let i = 0; i < phrases.length; i++) {
      for (const n of [1, 2]) {
        if (i + n > phrases.length) continue;
        const bloc = phrases.slice(i, i + n).join("").trim();
        if (!bloc) continue;
        const motsBloc = new Set(normaliser(bloc).split(" "));
        let communs = 0;
        for (const m of mots) if (motsBloc.has(m)) communs++;
        // Deux phrases ne gagnent que si elles couvrent nettement mieux la citation.
        const score = communs / mots.size - (n === 2 ? 0.08 : 0);
        if (!meilleur || score > meilleur.score) meilleur = { score, bloc, p };
      }
    }
  }
  if (!meilleur || meilleur.score < 0.55) return null;
  const { bloc, p } = meilleur;
  const i = p.indexOf(bloc);
  if (i < 0) return null;
  let debut = Math.max(0, i - 260);
  let fin = Math.min(p.length, i + bloc.length + 260);
  if (debut > 0) {
    const espace = p.indexOf(" ", debut);
    if (espace >= 0 && espace < i) debut = espace + 1;
  }
  if (fin < p.length) {
    const espace = p.lastIndexOf(" ", fin);
    if (espace > i + bloc.length) fin = espace;
  }
  const contexte = (debut > 0 ? "…" : "") + p.slice(debut, fin).trim() + (fin < p.length ? "…" : "");
  return { passage: bloc, contexte };
}

// ------------------------------------------------------------------ Ponts

/** Un concept vu il y a plus d'une semaine, que l'article d'un concept du soir rappelle. */
type Pont = { id: string; titre: string; le: string; wiki: string };

/**
 * Cherche, pour chaque concept, un concept du terrier vu il y a plus de 7 jours dont l'article est
 * lié depuis le sien (ou cité dans son extrait, si [extraits] est fourni). Un seul pont par porte :
 * le premier concept qui en trouve un le garde, les autres n'en ont pas.
 */
async function pontsPossibles(userId: string | null, concepts: Json[]): Promise<(Pont | null)[]> {
  const aucun = concepts.map(() => null);
  if (!userId || concepts.length === 0) return aucun;
  const limite = new Date(Date.now() - 7 * 86_400_000).toISOString();
  const { data: anciens } = await admin.from("chevet_concepts")
    .select("slug, titre, wikipedia_url, decouvert_le")
    .eq("user_id", userId).lt("decouvert_le", limite)
    .order("decouvert_le", { ascending: false }).limit(400);
  if (!anciens?.length) return aucun;
  const parArticle = new Map<string, Json>();
  for (const a of anciens) {
    const t = titreDepuisUrl(a.wikipedia_url);
    if (t) parArticle.set(normaliser(t), a);
  }
  const resultat: (Pont | null)[] = [...aucun];
  for (let k = 0; k < concepts.length; k++) {
    const c = concepts[k];
    if (!c?.wiki) continue;
    const ici = slug(c.wiki.titre);
    let trouve: Json = null;
    try {
      const liens = await liensSortants(c.wiki.titre, c.wiki.langue, "max");
      for (const l of liens) {
        const a = parArticle.get(normaliser(l));
        if (a && a.slug !== ici) {
          trouve = a;
          break;
        }
      }
    } catch (_) { /* pas de pont pour ce concept */ }
    if (trouve) {
      resultat[k] = {
        id: trouve.slug,
        titre: trouve.titre,
        le: String(trouve.decouvert_le ?? "").slice(0, 10),
        wiki: titreDepuisUrl(trouve.wikipedia_url),
      };
      break;
    }
  }
  return resultat;
}

function joursDepuis(date: string): number {
  const t = Date.parse(date);
  return Number.isFinite(t) ? Math.max(1, Math.round((Date.now() - t) / 86_400_000)) : 7;
}

const MOTS_CRITIQUE = ["critique", "critiques", "critiqué", "objection", "détracteurs", "opposants", "controverse", "contesté"];

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

// ------------------------------------------------------------------ Éphéméride

type Evenement = { annee: number; texte: string; articles: string[] };

/** Les événements du jour (« ce jour-là ») d'après Wikipédia, en français si possible. */
async function evenementsDuJour(jour: string): Promise<Evenement[]> {
  const [, mm, dd] = jour.split("-");
  const adresses = [
    `https://fr.wikipedia.org/api/rest_v1/feed/onthisday/events/${mm}/${dd}`,
    `https://api.wikimedia.org/feed/v1/wikipedia/fr/onthisday/events/${mm}/${dd}`,
    `https://api.wikimedia.org/feed/v1/wikipedia/en/onthisday/events/${mm}/${dd}`,
  ];
  for (const adresse of adresses) {
    try {
      const r = await fetch(adresse, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(8000) });
      if (!r.ok) continue;
      const d = await r.json();
      const evenements: Evenement[] = ((d.events ?? []) as Json[])
        .map((e) => ({
          annee: Number(e.year),
          texte: String(e.text ?? "").replace(/\s+/g, " ").trim(),
          articles: ((e.pages ?? []) as Json[])
            .map((p) => String(p.titles?.normalized ?? p.normalizedtitle ?? p.title ?? "").replaceAll("_", " "))
            .filter(Boolean)
            .slice(0, 3),
        }))
        .filter((e) => Number.isInteger(e.annee) && e.texte);
      if (evenements.length) return evenements;
    } catch (_) {
      // adresse suivante
    }
  }
  return [];
}

/** Environ un lot sur trois, jamais deux éphémérides à moins de 3 jours d'écart. */
function eligibleEphemeride(anciens: Json[], jour: string): boolean {
  const limite = Date.parse(jour) - 3 * 86_400_000;
  const recente = anciens.some((l: Json) =>
    Date.parse(l.jour) > limite && (l.contenu?.portes ?? []).some((p: Json) => p.type === "ephemeride")
  );
  return !recente && Math.random() < 0.7;
}

/** Date ISO (AAAA-MM-JJ) décalée de [jours] par rapport à [jour]. */
function decaler(jour: string, jours: number): string {
  return new Date(Date.parse(jour) + jours * 86_400_000).toISOString().slice(0, 10);
}

// ------------------------------------------------------------------ Étape 0 : composer

/** Ce qu'une composition de portes bonus ne doit pas répéter : les portes et concepts de ce soir. */
type Exclusions = { portes: string[]; concepts: string[]; graines: number[] };

/** Un concept écrit, tel que l'appli le renvoie dans un marque-page : assez complet pour être relu. */
function conceptValide(c: Json): boolean {
  return !!c && typeof c === "object" && typeof c.id === "string" && typeof c.titre === "string" &&
    Array.isArray(c.cartes) && c.cartes.length > 0 && c.source && typeof c.source.url === "string" &&
    c.qcm && Array.isArray(c.qcm.choix);
}

// ------------------------------------------------------------------ Score d'intérêt (notes de porte)

type Cote = { nom: string; moyenne: number; n: number };
type Appris = { themes: Cote[]; types: Cote[]; total: number; mauvaises: string[] };

const NOTE_NEUTRE = 6;
const POIDS_A_PRIORI = 2;

/**
 * Cote de chaque thème et de chaque type de porte, apprise des notes de 1 à 10 des 90 derniers jours.
 * La moyenne est tirée vers une note neutre tant que les notes sont rares (2 notes « virtuelles » à 6),
 * pour qu'une seule note ne décide de rien.
 */
async function interetAppris(userId: string | null): Promise<Appris | null> {
  if (!userId) return null;
  const { data } = await admin.from("chevet_notes_portes").select("porte_titre, theme, type, note, jour")
    .eq("user_id", userId).gte("jour", decaler(jourParis(), -90)).order("jour", { ascending: false }).limit(300);
  const notes: Json[] = data ?? [];
  if (notes.length === 0) return null;
  const cotes = (cle: "theme" | "type"): Cote[] => {
    const groupes = new Map<string, number[]>();
    for (const n of notes) {
      const nom = String(n[cle] ?? "").trim();
      if (nom) groupes.set(nom, [...(groupes.get(nom) ?? []), Number(n.note)]);
    }
    return [...groupes].map(([nom, v]) => ({
      nom,
      n: v.length,
      moyenne: (v.reduce((a, b) => a + b, 0) + NOTE_NEUTRE * POIDS_A_PRIORI) / (v.length + POIDS_A_PRIORI),
    })).sort((a, b) => b.moyenne - a.moyenne);
  };
  return {
    themes: cotes("theme"),
    types: cotes("type"),
    total: notes.length,
    mauvaises: notes.filter((n) => Number(n.note) <= 4).slice(0, 4).map((n) => String(n.porte_titre)),
  };
}

const arrondi1 = (x: number) => (Math.round(x * 10) / 10).toString().replace(".", ",");

/** Ce que les notes ont appris, en consigne pour la composition. Vide tant que les notes sont trop rares. */
function consigneInteret(appris: Appris | null): string {
  if (!appris || appris.total < 3) return "";
  const ligne = (c: Cote[]) => c.map((x) => `${x.nom} ${arrondi1(x.moyenne)}/10 (${x.n} note${x.n > 1 ? "s" : ""})`).join(" ; ");
  return `Ce que les notes de porte du lecteur ont appris (moyennes prudentes, sur 10) :
- Thèmes : ${ligne(appris.themes) || "pas assez de notes"}
- Types de porte : ${ligne(appris.types) || "pas assez de notes"}
${appris.mauvaises.length ? `- Portes mal notées (4 ou moins), à ne pas refaire sous le même angle : ${appris.mauvaises.map((t) => `« ${t} »`).join(" ; ")}\n` : ""}Penche vers les thèmes et les types les mieux notés, mais garde une porte sur les trois pour explorer un thème ou un type peu noté ou jamais vu. Les poids de thèmes réglés par le lecteur restent prioritaires : un thème à 0 n'apparaît jamais.`;
}

/** Le dernier check-in du dimanche (14 jours au plus), en consigne pour la composition. */
async function consigneCheckin(userId: string | null, jour: string): Promise<string> {
  if (!userId) return "";
  const { data } = await admin.from("chevet_checkins").select("semaine, ressenti, raisons")
    .eq("user_id", userId).gte("semaine", decaler(jour, -14)).order("semaine", { ascending: false }).limit(1).maybeSingle();
  if (!data) return "";
  const raisons = (data.raisons ?? []).join(" ; ");
  const conseil: Record<string, string> = {
    top: "Continue dans cette veine, sans te répéter.",
    bof: "Apporte plus de nouveauté et change de ton ou de format sur au moins une porte.",
    lassant: "Varie nettement les thèmes et les formats, sois plus léger et plus court, évite ce qui a lassé.",
  };
  return `Check-in du dimanche (semaine du ${data.semaine}) : le lecteur a trouvé la semaine « ${data.ressenti} »${
    raisons ? `, parce que : ${raisons}` : ""
  }. ${conseil[data.ressenti] ?? ""}`;
}

/**
 * Score attendu d'une porte : moyenne apprise de son thème et de son type, plus un bonus d'exploration
 * (type bandit) pour ce qui a été peu noté. Une porte sans historique reçoit la note neutre plus le bonus.
 */
function scorePorte(porte: Json, appris: Appris): number {
  const themes: string[] = (porte.concepts ?? []).map((c: Json) => String(c.theme ?? "")).filter(Boolean);
  const theme = [...new Set(themes)].sort((a, b) => themes.filter((t) => t === b).length - themes.filter((t) => t === a).length)[0];
  const part = (liste: Cote[], nom: string | undefined) => {
    const c = liste.find((x) => x.nom === nom);
    const n = c?.n ?? 0;
    const moyenne = c?.moyenne ?? NOTE_NEUTRE;
    return moyenne + Math.sqrt(Math.log(appris.total + 1) / (n + 1)) * 1.2;
  };
  return (part(appris.themes, theme) + part(appris.types, porte.type)) / 2;
}

/**
 * Compose 3 portes. Sans [bonus], c'est le lot du matin ; avec, ce sont des portes en plus
 * pour ce soir (« d'autres portes »), qui ne touchent pas aux concepts à prolonger demain.
 */
async function composer(lot: Json, profil: Json, bonus?: Exclusions) {
  const userId = lot.user_id;
  const appris = await interetAppris(userId);
  const semaineRessentie = bonus ? "" : await consigneCheckin(userId, lot.jour);
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

  // Marque-page d'hier : une porte « reprise », toute prête si les concepts suivants étaient écrits,
  // sinon composée ici comme les autres. Elle s'affiche en tête et ne compte pas dans le plafond.
  let repriseFaite: Json = null;
  let repriseAComposer: Json = null;
  let marquePage = false;
  // Portes notées 8 ou plus ces 7 derniers jours : leur suite est proposée.
  let adorees: Json[] = [];
  let evenements: Evenement[] = [];
  if (userId && !bonus) {
    const { data: mp } = await admin.from("chevet_marque_page").select("*")
      .eq("user_id", userId).eq("utilise", false).maybeSingle();
    if (mp && Date.parse(mp.cree_le) > Date.now() - 3 * 86_400_000) {
      marquePage = true;
      const suite: Json[] = (Array.isArray(mp.suite) ? mp.suite : []).filter(conceptValide).slice(0, 3);
      const apres = mp.dernier?.titre ? `après « ${mp.dernier.titre} »` : "en chemin";
      if (suite.length) {
        repriseFaite = {
          id: `reprise-${lot.jour}`,
          titre: suite[0].titre,
          accroche: `Hier, tu t'es arrêté ${apres}${mp.porte_titre ? `, dans « ${mp.porte_titre} »` : ""}.`,
          mystere: false,
          indice: null,
          type: "reprise",
          reprise: true,
          concepts: suite,
        };
      } else if (mp.dernier?.titre) {
        repriseAComposer = { dernier: mp.dernier.titre, porte: mp.porte_titre ?? "" };
      }
    }
    const { data: notes } = await admin.from("chevet_notes_portes").select("jour, porte_id, porte_titre, note")
      .eq("user_id", userId).gte("note", 8).eq("suite_servie", false).gte("jour", decaler(lot.jour, -7))
      .order("note", { ascending: false }).limit(2);
    adorees = notes ?? [];
  }
  if (!bonus && eligibleEphemeride(anciens ?? [], lot.jour)) {
    evenements = melanger(await evenementsDuJour(lot.jour)).slice(0, 40);
  }
  const anneeCourante = Number(lot.jour.slice(0, 4));

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
${consigneInteret(appris)}
${semaineRessentie}
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
${adorees.length ? `\nPortes que le lecteur a adorées (8/10 ou plus) : ${adorees.map((n) => `« ${n.porte_titre} » (${n.note}/10)`).join(" ; ")}` : ""}
${repriseAComposer ? `\nHier soir, le lecteur s'est arrêté après « ${repriseAComposer.dernier} »${repriseAComposer.porte ? ` (porte « ${repriseAComposer.porte} »)` : ""}.` : ""}
${evenements.length ? `\nÉphéméride : ce qui s'est passé un ${Number(lot.jour.slice(8, 10))}/${Number(lot.jour.slice(5, 7))}, avec l'année :\n${evenements.map((e) => `- ${e.annee} : ${e.texte}${e.articles.length ? ` (articles : ${e.articles.join(", ")})` : ""}`).join("\n")}` : ""}

Actu du jour (sources choisies par le lecteur) :
${actu || "indisponible"}

${bonus
    ? "Le lecteur a déjà lu une porte ce soir et en veut d'autres : compose exactement 3 portes neuves, chacune ouvrant sur un tunnel de 3 concepts. Aucune ne reprend une porte ni un concept de ce soir."
    : "Compose le lot de ce soir : exactement 3 portes, chacune ouvrant sur un tunnel de 3 concepts."}
Règles :
- ${premierSoir ? "C'est le tout premier soir : fais un best of, au moins 2 portes issues des graines." : "Si des graines restent, une porte au moins en est issue."}
${prolonges.length ? "- Une porte au moins prolonge un concept que le lecteur a demandé à prolonger (type terrier), en partant de lui ou du voisin indiqué.\n" : ""}${adorees.length ? "- Une porte au moins propose la suite d'une porte adorée (type terrier), en partant de ses derniers concepts, sans les répéter.\n" : ""}${repriseAComposer ? `- Une porte est de type "reprise" : elle reprend là où le lecteur s'est arrêté, en creusant un aspect plus précis de « ${repriseAComposer.dernier} » ; son titre est celui de son premier concept et son accroche commence par « Hier, tu t'es arrêté après « ${repriseAComposer.dernier} » ».\n` : ""}${evenements.length ? `- Une porte est de type "ephemeride" : elle part de l'événement de l'éphéméride qui touche le plus les centres d'intérêt du lecteur, survenu il y a au moins 50 ans ; indique "ephemeride":{"annee":…,"evenement":"l'événement en une phrase courte"}. Ses concepts sont des articles Wikipédia comme les autres, le premier au plus près de l'événement.\n` : ""}- Les portes qui prolongent le terrier partent de préférence des concepts aimés.
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
{"portes":[{"type":"graine|actu|terrier|curiosite|saut${repriseAComposer ? "|reprise" : ""}${evenements.length ? "|ephemeride" : ""}","graine":null,"titre":"...","accroche":"une phrase qui intrigue","mystere":false,"indice":null,
"actu":{"media":"...","titre":"...","lien":"..."} ou null,${evenements.length ? `\n"ephemeride":{"annee":1871,"evenement":"..."} ou null,` : ""}
"concepts":[{"titre":"titre court du concept","wikipedia":"Titre exact de l'article français","wikipedia_en":"Exact English article title","theme":"${Object.keys(poidsThemes(profil)).join("|")}"}]}],
"teaserDemain":"..."}`;

  const resultat = await ia(MODELE_REDACTION, SYSTEME_CHEVET, consigne, 2800);
  const portes = (resultat.portes ?? []).slice(0, 3);
  if (portes.length === 0) throw new Error("Composition vide");

  // Vérifie que chaque article existe vraiment.
  const annees = new Set(evenements.map((e) => e.annee));
  for (const porte of portes) {
    const resolus = await Promise.all(
      (porte.concepts ?? []).slice(0, 3).map(async (c: Json) => ({
        ...c,
        wiki: await resoudreArticle(c.wikipedia ?? c.titre, c.wikipedia_en),
      })),
    );
    porte.concepts = resolus.filter((c) => c.wiki);
    porte.id = slug(porte.titre ?? "porte");
    // Une éphéméride n'est gardée que si son année vient bien de la liste du jour.
    const e = porte.ephemeride;
    const annee = Number(e?.annee);
    if (porte.type === "ephemeride" && Number.isInteger(annee) && annees.has(annee) && annee <= anneeCourante - 20) {
      porte.ephemeride = { annee, evenement: String(e.evenement ?? "").slice(0, 200) };
    } else {
      if (porte.type === "ephemeride") porte.type = "saut";
      porte.ephemeride = null;
    }
    if (porte.type === "reprise" && !repriseAComposer) porte.type = "terrier";
  }

  // Tri par score d'intérêt : les portes les mieux attendues passent d'abord (la reprise garde la tête, voir finaliser).
  const gardees = portes.filter((p: Json) => p.concepts.length > 0);
  if (appris && appris.total >= 3) {
    gardees.sort((x: Json, y: Json) => scorePorte(y, appris) - scorePorte(x, appris));
  }

  return {
    portes: gardees,
    graines: portes.map((p: Json) => p.graine).filter((g: Json) => Number.isInteger(g)),
    teaserDemain: resultat.teaserDemain ?? "",
    curiosites: curiosites.map((c) => c.id),
    prolonges: prolonges.map((c) => c.id),
    flux: flux.etat,
    userId,
    repriseFaite,
    marquePage,
    adorees: adorees.map((n) => ({ jour: n.jour, porte_id: n.porte_id })),
  };
}

// ------------------------------------------------------------------ Étapes 1-3 : rédiger une porte

/**
 * Options de rédaction. [crans] : les concepts sont des suites possibles et indépendantes du dernier
 * lu (« plus loin », « à côté »), pas un tunnel. [court] : après l'heure de coucher, cartes courtes.
 */
type OptionsRedaction = { userId?: string | null; crans?: boolean; court?: boolean };

async function rediger(travail: Json, i: number, profil: Json, options: OptionsRedaction = {}) {
  const porte = travail.portes?.[i];
  if (!porte) return;

  // Un seul pont possible par porte, cherché avant l'extrait pour que le paragraphe qui en parle soit gardé.
  if (porte.ponts === undefined) {
    porte.ponts = await pontsPossibles(options.userId ?? travail.userId ?? null, porte.concepts);
  }
  const ponts: (Pont | null)[] = porte.ponts ?? [];

  const cles = motsDe(porte.titre, porte.accroche, porte.actu?.titre);
  porte.concepts.forEach((c: Json, k: number) => {
    c.critique = /^(Philo|Histoire)/.test(String(c.theme ?? ""));
    c.motsPont = ponts[k] ? motsDe(ponts[k]!.wiki) : [];
  });
  for (const c of porte.concepts) {
    if (!c.extrait) {
      c.extrait = await extrait(
        c.wiki.titre,
        c.wiki.langue,
        [...cles, ...motsDe(c.titre), ...(c.critique ? MOTS_CRITIQUE : []), ...(c.motsPont ?? [])],
      );
    }
  }

  // Longueur réglée par le lecteur ; la vérification en a besoin pour borner les cartes gardées.
  const reglee = longueurCartes(profil);
  const longueur = options.court
    ? { mots: 25, min: 1, max: 2 }
    : options.crans
    ? { mots: reglee.mots, min: Math.min(2, reglee.min), max: 3 }
    : reglee;
  porte.cartesMin = longueur.min;
  porte.cartesMax = longueur.max;

  // Réécriture de la suite d'une porte : le lecteur a déjà lu le début du tunnel.
  const dejaLus: string[] = porte.dejaLus ?? [];
  const lignePont = ponts.map((p, k) =>
    p
      ? `Pont possible : le concept ${k + 1} « ${porte.concepts[k].titre} » peut rappeler « ${p.titre} », que le lecteur a exploré il y a ${joursDepuis(p.le)} jours (article « ${p.wiki} »).`
      : ""
  ).filter(Boolean).join("\n");
  const consigne = `${portraitLecteur(profil)}

Porte : « ${porte.titre} » — ${porte.accroche}
${porte.actu ? `Elle part de cette actu : [${porte.actu.media}] ${porte.actu.titre}` : ""}
${
    options.crans
      ? `Le lecteur vient de lire « ${dejaLus.at(-1) ?? porte.titre} ». Les concepts ci-dessous sont des suites possibles et indépendantes : chacun se lit seul, juste après lui ; ils ne s'enchaînent pas entre eux.`
      : dejaLus.length
      ? `Le lecteur a déjà lu, dans cette porte : ${dejaLus.map((t) => `« ${t} »`).join(", ")}. Les concepts ci-dessous en sont la suite.`
      : ""
  }
${lignePont}

${options.crans ? "Concepts, chacun avec l'article Wikipédia qui fait foi :" : "Concepts du tunnel, dans l'ordre, avec l'article Wikipédia qui fait foi :"}
${porte.concepts.map((c: Json, k: number) => `### Concept ${k + 1} : ${c.titre} (thème ${c.theme})\n${c.extrait}`).join("\n\n")}

Écris les cartes de chaque concept.
Règles :
- N'utilise que des faits présents dans l'article fourni. Pas de chiffre, de date ni de nom qui n'y figure pas.
- ${longueur.min} à ${longueur.max} cartes par concept selon sa complexité, ${longueur.mots} mots au plus par carte, une idée par carte.
- Raconte : la première carte pose une scène datée, un lieu ou une personne réelle de l'article ; pas de définition de dictionnaire, pas de liste, pas de jargon.
- Au moins une carte par concept contient une date ou un chiffre de l'article.
- Ne relie le concept à la question de la porte que si l'article fait lui-même ce lien. Sinon, pose les faits de l'article sans conclure à sa place : le lecteur fera le lien.
- N'écris jamais qu'un fait cause, aggrave, pousse, oblige, incite ou explique autre chose si l'article ne le dit pas en toutes lettres.
- "question", "aRetenir", "aRaconter" et "qcm" suivent les mêmes règles : aucun d'eux ne présuppose un lien que l'article n'établit pas.
- N'invente jamais de scène, de personnage, de dialogue ni d'exemple : tout vient de l'article. S'il ne raconte pas d'histoire, pars d'un fait daté.
- Si l'article ne parle pas vraiment du concept ou de la porte, renvoie pour ce concept seulement {"titre":"...","horsSujet":true}.
- "question" : la question qui ouvre le concept et donne envie de glisser.
- ${
    options.crans
      ? `Chaque concept prend la suite de « ${dejaLus.at(-1) ?? porte.titre} » : sa question part de ce que le lecteur vient de lire.`
      : `${dejaLus.length ? `Le premier concept prend la suite de « ${dejaLus.at(-1)} »` : "Le premier concept accroche la porte"} ; chaque concept suivant s'enchaîne avec le précédent.`
  }
- "contrePoint" : seulement si le concept est une idée politique, économique, sociale ou philosophique discutée ET que son article rapporte une critique, une objection ou une position adverse. Écris alors la meilleure objection, en ${longueur.mots} mots au plus, attribuée à ceux qui la portent dans l'article (« Ses critiques répondent que… », « Pour les économistes libéraux… »), sans la réfuter ni conclure. Sinon null.
- "pont" : ${
    lignePont
      ? `seulement pour le concept du pont possible indiqué plus haut, et seulement si son article parle lui-même de l'autre concept : une carte de ${longueur.mots} mots au plus qui commence par « Tu te souviens » et dit, avec les seuls faits de l'article, ce qui relie les deux. Sinon null.`
      : "toujours null."
  }
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
"contrePoint":"..." ou null,"pont":"..." ou null,
"qcm":{"question":"...","choix":["bonne","fausse","fausse"],"autreAngle":"..."},"creuser":["...","...","..."]}]}`;

  const resultat = await ia(MODELE_REDACTION, SYSTEME_CHEVET, consigne, 3600, 0.5);
  porte.redaction = resultat.concepts ?? [];
}

// ------------------------------------------------------------------ Étapes 4-6 : vérification factuelle

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

/** Vrai si une phrase corrigée parle de l'article au lieu du sujet (« l'article ne dit pas… »). */
function estMeta(texte: string): boolean {
  return /\b(l['’]article|la source|le texte source|l['’]extrait)\b/i.test(texte);
}

/**
 * Vérification factuelle sans réécriture : le modèle juge chaque carte telle quelle (verdict + citation),
 * le code garde le texte d'origine des cartes jugées justes dont la citation est retrouvée dans l'article,
 * et retire les autres. Seules la phrase à retenir et la version à raconter peuvent être corrigées.
 */
async function relire(travail: Json, i: number) {
  const porte = travail.portes?.[i];
  if (!porte?.redaction?.length) return;
  const valides = porte.redaction
    .map((r: Json, k: number) => ({ r, k }))
    .filter(({ r }: Json) => r && !r.horsSujet);
  if (valides.length === 0) return;

  const consigne = `Tu es vérificateur factuel. Tu ne réécris RIEN : tu juges chaque carte telle quelle contre son article source.
Pour chaque carte :
- "preuve" : recopie mot pour mot la phrase de l'article qui la prouve, ou null ;
- "verdict" : "ok" si tout ce qu'affirme la carte est dans l'article, sinon "faux".
Une carte est "faux" si elle contient : un sens inversé (« avec » au lieu de « sans », négation perdue), un lien de cause à effet que l'article n'établit pas, une scène, un personnage, un détail ou un chiffre absent de l'article, un rôle inexact d'une institution ou d'une personne.
Les reformulations, images et transitions de style sont permises tant qu'elles n'ajoutent aucun fait.
"aRetenir" et "aRaconter" : verdict "ok" ou "faux" ; si "faux", donne une version corrigée de même longueur, dans le même ton, qui ne dit que ce que l'article prouve et parle du sujet, jamais de l'article.
"ordreDeGrandeur" : sa preuve exacte si le chiffre vient de l'article et concerne la porte « ${porte.titre} », sinon null.
"contrePoint" et "pont", s'ils existent, se jugent comme des cartes (verdict + preuve) ; un contre-point doit rapporter une objection que l'article attribue bien à ses auteurs, un pont un lien que l'article fait lui-même. Absents : null.

${valides.map(({ r, k }: Json) => `### Concept ${k + 1} : ${r.titre}
Article source :
${porte.concepts[k]?.extrait ?? ""}
Cartes à juger :
${(r.cartes ?? []).map((c: string, n: number) => `${n + 1}. ${c}`).join("\n")}
aRetenir : ${r.aRetenir ?? ""}
aRaconter : ${r.aRaconter ?? ""}
ordreDeGrandeur : ${r.ordreDeGrandeur ?? "null"}
contrePoint : ${typeof r.contrePoint === "string" && r.contrePoint ? r.contrePoint : "null"}
pont : ${typeof r.pont === "string" && r.pont ? r.pont : "null"}`).join("\n\n")}

Réponds dans le même ordre, un objet par concept, une entrée par carte dans l'ordre :
{"concepts":[{"cartes":[{"verdict":"ok","preuve":"citation exacte"},{"verdict":"faux","preuve":null,"raison":"..."}],
"aRetenir":{"verdict":"ok"},"aRaconter":{"verdict":"faux","corrige":"..."},"ordreDeGrandeur":{"preuve":"citation exacte"} ou null,
"contrePoint":{"verdict":"ok","preuve":"citation exacte"} ou null,"pont":{"verdict":"ok","preuve":"citation exacte"} ou null}]}`;

  const resultat = await ia(MODELE_VERIFICATION, SYSTEME_CHEVET, consigne, 4500, 0.1);
  const verifs: Json[] = Array.isArray(resultat.concepts) ? resultat.concepts : [];
  if (verifs.length !== valides.length) {
    // Sans vérification exploitable, les cartes restent mais aucun extra (contre-point, pont) n'est gardé.
    porte.corrections = ["Vérification inutilisable : texte d'origine conservé."];
    for (const { r } of valides) {
      r.contrePoint = null;
      r.pont = null;
    }
    return;
  }

  // Fourchette réglée par le lecteur (2 à 4 par défaut) : en dessous du minimum, le concept tombe.
  const minimum = Math.max(1, Math.min(2, Number(porte.cartesMin) || 2));
  const maximum = Math.max(minimum, Number(porte.cartesMax) || 5);
  const retraits: string[] = [];
  valides.forEach(({ r: original, k }: Json, n: number) => {
    const v = verifs[n] ?? {};
    const source: string = porte.concepts[k]?.extrait ?? "";
    const gardees: { texte: string; preuve: Json }[] = [];
    ((original.cartes ?? []) as Json[]).forEach((texte: Json, c: number) => {
      const j = v.cartes?.[c];
      const ok = typeof texte === "string" && j?.verdict === "ok" && estProuve(j?.preuve, source);
      if (!ok) {
        retraits.push(`${original.titre}, carte ${c + 1} : ${j?.raison ?? (j?.verdict === "ok" ? "citation introuvable" : "jugée fausse")}`);
        return;
      }
      gardees.push({ texte, preuve: localiser(j.preuve, source) });
    });
    const cartes = gardees.slice(0, maximum);
    // Contre-point et pont : gardés seulement s'ils sont jugés justes et prouvés, comme une carte.
    const extra = (champ: "contrePoint" | "pont") => {
      const texte = original[champ];
      if (typeof texte !== "string" || !texte.trim()) return null;
      const j = v[champ];
      if (j?.verdict === "ok" && estProuve(j?.preuve, source)) return { texte, preuve: localiser(j.preuve, source) };
      retraits.push(`${original.titre}, ${champ} retiré`);
      return null;
    };
    // Synthèse jugée fausse : la correction si elle parle du sujet, sinon une carte prouvée.
    const synthese = (champ: string, repli: string | undefined) => {
      const j = v[champ];
      if (!j || j.verdict !== "faux") return original[champ] || repli;
      retraits.push(`${original.titre}, ${champ} corrigé`);
      return typeof j.corrige === "string" && j.corrige && !estMeta(j.corrige) ? j.corrige : repli;
    };
    const odg = v.ordreDeGrandeur;
    // La vérification ne touche qu'aux cartes, aux synthèses et au chiffre ; le reste vient de la rédaction.
    porte.redaction[k] = cartes.length < minimum ? { titre: original.titre, horsSujet: true } : {
      ...original,
      cartes: cartes.map((c) => c.texte),
      preuves: cartes.map((c) => c.preuve),
      aRetenir: synthese("aRetenir", cartes.at(-1)?.texte),
      aRaconter: synthese("aRaconter", cartes[0]?.texte),
      ordreDeGrandeur: original.ordreDeGrandeur && odg && typeof odg === "object" && estProuve(odg.preuve, source)
        ? original.ordreDeGrandeur
        : null,
      contrePoint: extra("contrePoint"),
      pont: extra("pont"),
    };
  });
  porte.corrections = retraits.length ? retraits : ["Toutes les cartes sont prouvées."];
}

// ------------------------------------------------------------------ Étape 7 : pépite et assemblage

/** Une carte au format de l'appli, avec sa preuve quand la vérification l'a retrouvée. */
function carteAppli(texte: string, preuve: Json) {
  return preuve?.passage ? { texte, preuve: preuve.passage, contexte: preuve.contexte } : { texte };
}

/** Le concept k rédigé et relu d'une porte, au format de l'appli ; null s'il est tombé. */
function conceptRedige(p: Json, k: number): Json {
  const r = p.redaction?.[k];
  if (!r || r.horsSujet || !(r.cartes ?? []).length) return null;
  const source = p.concepts[k] ?? p.concepts[0];
  const choix: string[] = r.qcm?.choix ?? [];
  const ordre = melanger(choix.map((_, idx) => idx));
  const preuves: Json[] = r.preuves ?? [];
  // Contre-point et pont ne passent que vérifiés (objet {texte, preuve}) ; un texte brut est ignoré.
  const contre = r.contrePoint && typeof r.contrePoint === "object" ? r.contrePoint : null;
  const pont = r.pont && typeof r.pont === "object" ? r.pont : null;
  const avec: Pont | null = p.ponts?.[k] ?? null;
  return {
    id: slug(source.wiki.titre),
    titre: r.titre ?? source.titre,
    theme: source.theme ?? "Histoire & sociétés",
    question: r.question ?? "",
    cartes: (r.cartes ?? []).map((t: string, n: number) => carteAppli(t, preuves[n])),
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
    contrePoint: contre ? carteAppli(contre.texte, contre.preuve) : null,
    pont: pont && avec
      ? { ...carteAppli(pont.texte, pont.preuve), avecId: avec.id, avecTitre: avec.titre, avecLe: avec.le }
      : null,
    // Crans : d'où ils partent, dans quel sens, à quelle profondeur.
    ...(source.sens
      ? { sens: source.sens, depuis: source.depuis ?? null, depuisTitre: source.depuisTitre ?? null, profondeur: source.profondeur ?? 1 }
      : {}),
  };
}

/** Les concepts rédigés et relus d'une porte, au format de l'appli. */
function conceptsRediges(p: Json): Json[] {
  return (p.redaction ?? []).map((_: Json, k: number) => conceptRedige(p, k)).filter((c: Json) => c !== null);
}

async function finaliser(lot: Json, travail: Json) {
  const extraits = (travail.portes ?? []).flatMap((p: Json) => p.concepts.map((c: Json) => `### ${c.titre}\n${c.extrait?.slice(0, 900)}`));
  const pepite = await ia(
    MODELE_RELECTURE,
    SYSTEME_CHEVET,
    `Dans ces extraits d'articles, choisis le fait le plus renversant, et écris-le en 40 mots au plus,
comme une pépite qu'on a envie de raconter. Uniquement un fait présent dans les extraits.
${extraits.join("\n\n")}
Format JSON : {"texte":"..."}`,
    700,
    0.4,
  );

  const portes: Json[] = (travail.portes ?? [])
    .filter((p: Json) => p.redaction?.some((r: Json) => r && !r.horsSujet))
    .map((p: Json) => ({
      id: p.id,
      titre: p.titre,
      accroche: p.accroche,
      mystere: !!p.mystere && p.type !== "reprise",
      indice: p.indice ?? null,
      type: p.type ?? null,
      reprise: p.type === "reprise",
      ephemeride: p.ephemeride ?? null,
      concepts: conceptsRediges(p),
    }))
    .filter((p: Json) => p.concepts.length > 0);

  if (portes.length === 0) throw new Error("Aucune porte rédigée");

  // La reprise s'affiche en tête : toute prête depuis le marque-page, ou composée avec les autres.
  if (travail.repriseFaite) portes.unshift(travail.repriseFaite);
  portes.sort((a, b) => Number(!!b.reprise) - Number(!!a.reprise));

  // Les curiosités utilisées sortent de la boîte.
  if (travail.curiosites?.length) {
    await admin.from("chevet_curiosites").update({ statut: "porte_generee" }).in("id", travail.curiosites);
  }
  // Les concepts à prolonger ont nourri ce lot : la demande est servie.
  if (travail.prolonges?.length) {
    await admin.from("chevet_concepts").update({ prolonger: false, prolonger_vers: null }).in("id", travail.prolonges);
  }
  // Le marque-page et les portes adorées ont nourri ce lot aussi.
  if (travail.marquePage && lot.user_id) {
    await admin.from("chevet_marque_page").update({ utilise: true }).eq("user_id", lot.user_id);
  }
  for (const n of travail.adorees ?? []) {
    await admin.from("chevet_notes_portes").update({ suite_servie: true })
      .eq("user_id", lot.user_id).eq("jour", n.jour).eq("porte_id", n.porte_id);
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
      await rediger(travail, etape - 1, profil, { userId });
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
    if (!(e instanceof LimiteLLM)) {
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

  const resultat = await ia(MODELE_REDACTION, SYSTEME_CHEVET, consigne, 1500);
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
      await rediger({ portes: [s.pt] }, 0, profil, { userId });
    } else if (s.etape === 2) {
      await relire({ portes: [s.pt] }, 0);
    } else {
      const nouveaux = conceptsRediges(s.pt);
      if (nouveaux.length === 0) throw new Error("Suite vide après vérification");
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
    if (!(e instanceof LimiteLLM)) {
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
        await rediger({ portes: [pt] }, 0, profil, { userId });
      } else if (etape === 1) {
        await relire({ portes: [pt] }, 0);
      } else {
        const concepts = conceptsRediges(pt);
        if (concepts.length === 0) throw new Error("Porte vide après vérification");
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
    if (!(e instanceof LimiteLLM)) {
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
    const r = await ia(
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

// ------------------------------------------------------------------ Exploration : plus loin, à côté

const NB_ETAPES_CRANS = 4;
const SENS = ["plus_loin", "a_cote"] as const;

/** État public d'une ligne de crans : titres dès la composition, concepts quand ils sont prêts. */
function etatCrans(ligne: Json, extra: Json = {}) {
  const c = ligne?.contenu ?? {};
  return {
    statut: ligne?.statut ?? "indisponible",
    etape: ligne?.etape ?? 0,
    total: NB_ETAPES_CRANS,
    erreur: ligne?.erreur ?? null,
    titres: c.titres ?? null,
    plus_loin: c.plus_loin ?? null,
    a_cote: c.a_cote ?? null,
    ...extra,
  };
}

/**
 * Étape 0 des crans : choisit deux suites au concept [depuis] — « plus loin » (le même sujet, un cran
 * plus profond) et « à côté » (une autre facette de la question de la porte) — parmi ses liens Wikipédia.
 */
async function composerCrans(userId: string, porte: Json, depuis: Json, chemin: string[], profil: Json) {
  const m = String(depuis.url ?? "").match(/https:\/\/(\w+)\.wikipedia\.org\/wiki\/(.+)$/);
  let liens: string[] = [];
  if (m) {
    try {
      liens = await liensSortants(decodeURIComponent(m[2]).replaceAll("_", " "), m[1], "max");
    } catch (_) { /* tant pis : le modèle propose sans liste */ }
  }
  const { data: vus } = await admin.from("chevet_concepts").select("titre, wikipedia_url")
    .eq("user_id", userId).order("decouvert_le", { ascending: false }).limit(200);
  const dejaVus = new Set([
    ...(vus ?? []).flatMap((c: Json) => [normaliser(c.titre ?? ""), normaliser(titreDepuisUrl(c.wikipedia_url))]),
    ...chemin.map(normaliser),
    normaliser(String(depuis.titre ?? "")),
    normaliser(titreDepuisUrl(depuis.url)),
  ].filter(Boolean));
  const candidats = melanger(
    liens.filter((t) => !dejaVus.has(normaliser(t)) && !/^(Liste|Chronologie|Portail|Catégorie|Aide|Modèle|\d)/.test(t)),
  ).slice(0, 70);

  const consigne = `${portraitLecteur(profil)}

Porte : « ${porte.titre ?? ""} » — ${porte.accroche ?? ""}
Le lecteur vient de finir « ${depuis.titre} ».
Chemin déjà lu : ${[...chemin, depuis.titre].map((t) => `« ${t} »`).join(" → ")}
Articles Wikipédia liés à « ${depuis.titre} » : ${candidats.join(" ; ") || "aucun"}

Propose deux suites possibles, chacune un article Wikipédia qui existe :
- "plus_loin" : creuse le même sujet un cran plus profond : un mécanisme précis, un cas, une personne, un épisode ou une notion plus fine de « ${depuis.titre} » lui-même.
- "a_cote" : reste dans la question de la porte mais change d'angle : une autre facette, un sujet voisin au même niveau, qui éclaire la porte autrement.
Règles :
- De préférence parmi les articles liés ci-dessus ; titres exacts en français et en anglais, canoniques et précis.
- Jamais un article déjà lu, jamais une liste, une année, un pays ou une ville trop générale.
- "titre" : le titre court du concept ; "accroche" : 12 mots au plus, qui tutoie le lecteur et donne envie de glisser.

Format JSON :
{"plus_loin":{"titre":"...","wikipedia":"Titre exact français","wikipedia_en":"Exact English title","theme":"${Object.keys(poidsThemes(profil)).join("|")}","accroche":"..."},
"a_cote":{"titre":"...","wikipedia":"...","wikipedia_en":"...","theme":"...","accroche":"..."}}`;

  const resultat = await ia(MODELE_RELECTURE, SYSTEME_CHEVET, consigne, 1200, 0.5);
  const profondeur = Math.max(0, Math.floor(Number(depuis.profondeur) || 0)) + 1;
  const concepts: Json[] = [];
  const titres: Json = {};
  for (const sens of SENS) {
    const c = resultat[sens];
    if (!c?.wikipedia && !c?.titre) continue;
    const w = await resoudreArticle(String(c.wikipedia ?? c.titre), c.wikipedia_en ? String(c.wikipedia_en) : undefined);
    if (!w || dejaVus.has(normaliser(w.titre)) || concepts.some((x) => x.wiki.titre === w.titre)) continue;
    const titre = String(c.titre ?? w.titre).slice(0, 120);
    concepts.push({
      titre,
      theme: c.theme ?? depuis.theme ?? "Histoire & sociétés",
      wiki: w,
      sens,
      depuis: String(depuis.id ?? ""),
      depuisTitre: String(depuis.titre ?? ""),
      profondeur,
    });
    titres[sens] = { titre, accroche: String(c.accroche ?? "").slice(0, 140) };
  }
  if (concepts.length === 0) throw new Error("Aucune suite trouvée");
  return {
    pt: {
      titre: String(porte.titre ?? depuis.titre),
      accroche: String(porte.accroche ?? ""),
      actu: null,
      concepts,
      dejaLus: [...chemin, String(depuis.titre)],
    },
    titres,
  };
}

/**
 * « Plus loin » et « à côté » depuis un concept ou un cran, une étape par appel : composition (titres
 * renvoyés tout de suite pour les languettes), rédaction, vérification, assemblage. Une ligne par
 * concept de départ et par jour, avec son propre verrou : la préparation ne bloque pas le reste.
 */
async function crans(userId: string, corps: Json) {
  const jour = jourParis();
  const depuis = corps.depuis && typeof corps.depuis === "object" ? corps.depuis : {};
  const depuisId = String(depuis.id ?? "").slice(0, 120);
  if (!depuisId || !/^https:\/\/\w+\.wikipedia\.org\/wiki\//.test(String(depuis.url ?? ""))) {
    return etatCrans(null, { statut: "indisponible" });
  }
  const chercher = () =>
    admin.from("chevet_crans").select("*").eq("user_id", userId).eq("jour", jour).eq("depuis", depuisId).maybeSingle();
  let { data: ligne } = await chercher();
  if (!ligne) {
    await admin.from("chevet_crans").upsert(
      { user_id: userId, jour, depuis: depuisId, court: !!corps.court },
      { onConflict: "user_id,jour,depuis", ignoreDuplicates: true },
    );
    ({ data: ligne } = await chercher());
    if (!ligne) return etatCrans(null, { statut: "indisponible" });
  }
  if (ligne.statut === "pret" || ligne.statut === "erreur") return etatCrans(ligne);

  // Verrou court : une seule étape à la fois pour cette ligne.
  const maintenant = new Date();
  const { data: verrou } = await admin.from("chevet_crans")
    .update({ verrou_jusqua: new Date(maintenant.getTime() + 140_000).toISOString() })
    .eq("id", ligne.id).eq("etape", ligne.etape)
    .or(`verrou_jusqua.is.null,verrou_jusqua.lt."${maintenant.toISOString()}"`)
    .select().maybeSingle();
  if (!verrou) return etatCrans(ligne, { occupe: true });

  const profil = await profilDe(userId);
  const travail = ligne.travail ?? {};
  const contenu = ligne.contenu ?? {};
  const maj: Json = { verrou_jusqua: null };
  const chemin: string[] = (Array.isArray(corps.chemin) ? corps.chemin : []).map((t: unknown) => String(t).slice(0, 120)).slice(-8);
  try {
    if (ligne.etape === 0) {
      const { pt, titres } = await composerCrans(userId, corps.porte ?? {}, depuis, chemin, profil);
      travail.pt = pt;
      contenu.titres = titres;
    } else if (ligne.etape === 1) {
      await rediger({ portes: [travail.pt] }, 0, profil, { userId, crans: true, court: ligne.court });
    } else if (ligne.etape === 2) {
      await relire({ portes: [travail.pt] }, 0);
    } else {
      const pt = travail.pt;
      for (const sens of SENS) {
        const k = pt.concepts.findIndex((c: Json) => c.sens === sens);
        contenu[sens] = k >= 0 ? conceptRedige(pt, k) : null;
        if (!contenu[sens] && contenu.titres) delete contenu.titres[sens];
      }
      if (!contenu.plus_loin && !contenu.a_cote) throw new Error("Aucune suite après vérification");
      maj.statut = "pret";
      delete travail.pt;
    }
    maj.etape = ligne.etape + 1;
    maj.erreur = null;
    travail.tentatives = 0;
  } catch (e) {
    maj.erreur = String(e).slice(0, 500);
    if (!(e instanceof LimiteLLM)) {
      travail.tentatives = (travail.tentatives ?? 0) + 1;
      if (travail.tentatives >= 3 || String(e).includes("Aucune suite")) maj.statut = "erreur";
    }
  }
  maj.travail = travail;
  maj.contenu = contenu;
  const { data: apres } = await admin.from("chevet_crans").update(maj).eq("id", ligne.id).select().maybeSingle();
  return etatCrans(apres ?? { ...ligne, ...maj });
}

// ------------------------------------------------------------------ Marque-page

/**
 * Garde l'endroit où le lecteur s'est arrêté : le dernier concept lu et les concepts déjà écrits qui
 * suivaient. Le lot du lendemain en fait la porte « reprise ». {"effacer":true} : porte finie, rien à garder.
 */
async function enregistrerMarquePage(userId: string, corps: Json) {
  if (corps.effacer) {
    await admin.from("chevet_marque_page").delete().eq("user_id", userId);
    return { ok: true };
  }
  let suite: Json[] = (Array.isArray(corps.suite) ? corps.suite : []).filter(conceptValide).slice(0, 3);
  if (JSON.stringify(suite).length > 80_000) suite = suite.slice(0, 1);
  const d = corps.dernier && typeof corps.dernier === "object" ? corps.dernier : null;
  const dernier = d
    ? { id: String(d.id ?? "").slice(0, 120), titre: String(d.titre ?? "").slice(0, 200), url: String(d.url ?? "").slice(0, 500) }
    : null;
  if (!dernier?.titre && suite.length === 0) return { ok: false };
  const { error } = await admin.from("chevet_marque_page").upsert({
    user_id: userId,
    porte_titre: String(corps.porteTitre ?? "").slice(0, 200),
    dernier,
    suite,
    utilise: false,
    cree_le: new Date().toISOString(),
  }, { onConflict: "user_id" });
  return { ok: !error };
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
      case "crans":
        return reponse(await crans(userId, corps));
      case "marque_page":
        return reponse(await enregistrerMarquePage(userId, corps));
      default:
        return reponse({ erreur: `Action inconnue : ${action}` }, 400);
    }
  } catch (e) {
    return reponse({ erreur: String(e) }, 500);
  }
});
