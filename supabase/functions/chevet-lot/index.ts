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
// Tout est idempotent : un lot prêt ne bouge plus, donc un appel en trop ne coûte rien.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
const MODELE_REDACTION = "openai/gpt-oss-120b";
const MODELE_RELECTURE = "openai/gpt-oss-20b";
const UA = "Chevet/0.1 (https://github.com/dash52000/anti_scroll)";
const NB_ETAPES = 8;

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

async function groq(modele: string, systeme: string, utilisateur: string, maxTokens = 3000): Promise<Json> {
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
      temperature: 0.7,
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

async function lireFlux(sources: { nom: string; url: string }[]) {
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

async function resoudreArticle(titre: string) {
  for (const langue of ["fr", "en"]) {
    try {
      const d = await wiki({ action: "query", titles: titre, redirects: "1", prop: "pageprops" }, langue);
      const page = d.query?.pages?.[0];
      if (page && !page.missing && !page.invalid && !page.pageprops?.disambiguation) {
        return { titre: page.title as string, langue, url: urlArticle(page.title, langue) };
      }
      const s = await wiki({ action: "query", list: "search", srsearch: titre, srlimit: "1" }, langue);
      const trouve = s.query?.search?.[0];
      if (trouve) return { titre: trouve.title as string, langue, url: urlArticle(trouve.title, langue) };
    } catch (_) {
      // on tente la langue suivante
    }
  }
  return null;
}

async function extrait(titre: string, langue: string, longueur = 2500): Promise<string> {
  const d = await wiki(
    { action: "query", prop: "extracts", explaintext: "1", titles: titre, redirects: "1" },
    langue,
  );
  return ((d.query?.pages?.[0]?.extract as string) ?? "").slice(0, longueur);
}

async function liensSortants(titre: string, langue: string): Promise<string[]> {
  const d = await wiki(
    { action: "query", prop: "links", plnamespace: "0", pllimit: "120", titles: titre, redirects: "1" },
    langue,
  );
  return (d.query?.pages?.[0]?.links ?? []).map((l: Json) => l.title as string);
}

// ------------------------------------------------------------------ Consignes communes

function portraitLecteur(profil: Json): string {
  return [
    `Lecteur : ${profil.interets?.resume ?? ""}`,
    `Niveaux : ${JSON.stringify(profil.niveaux)}`,
    `Style attendu : ${JSON.stringify(profil.style)}`,
    `À éviter : ${(profil.a_eviter ?? []).join(", ")}`,
  ].join("\n");
}

const SYSTEME_CHEVET =
  "Tu prépares le lot du soir de Chevet, une appli qui remplace le scroll du soir au lit par de petites leçons. " +
  "Le lecteur est fatigué : tout doit être clair, concret, raconté comme une histoire vraie, sans jargon et sans morale finale. " +
  "Tu écris en français. Tu réponds uniquement par un objet JSON valide.";

// ------------------------------------------------------------------ Étape 0 : composer

async function composer(lot: Json, profil: Json) {
  const userId = lot.user_id;
  const filtreUser = <T extends { is: Json; eq: Json }>(q: T) => (userId ? q.eq("user_id", userId) : q.is("user_id", null));

  const { data: anciens } = await filtreUser(
    admin.from("chevet_lots").select("jour, contenu").neq("id", lot.id).order("jour", { ascending: false }).limit(14),
  );
  const portesPassees = (anciens ?? []).flatMap((l: Json) => (l.contenu?.portes ?? []).map((p: Json) => p.titre));

  const { data: vus } = await filtreUser(
    admin.from("chevet_concepts").select("titre, wikipedia_url").order("decouvert_le", { ascending: false }).limit(30),
  );
  const conceptsVus = (vus ?? []).map((c: Json) => c.titre);

  // Candidats du terrier : liens sortants des 3 derniers concepts explorés.
  const candidatsTerrier: string[] = [];
  for (const c of (vus ?? []).slice(0, 3)) {
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

  const graines = (profil.graines ?? []).filter((g: Json) => !portesPassees.includes(g.porte));
  const premierSoir = portesPassees.length === 0;

  const consigne = `${portraitLecteur(profil)}

Thèmes : ${(profil.interets?.themes ?? []).join(", ")}
Centres d'intérêt détaillés : ${JSON.stringify(profil.interets)}

Graines (questions posées par le lecteur, pas encore traitées) :
${graines.map((g: Json) => `- ${g.question} → porte suggérée « ${g.porte} » (pistes : ${g.pistes})`).join("\n") || "aucune"}

Portes déjà proposées ces derniers jours (ne pas répéter) : ${portesPassees.join(" ; ") || "aucune"}
Concepts déjà explorés (ne pas répéter) : ${conceptsVus.join(" ; ") || "aucun"}

Pistes du terrier (articles Wikipédia liés aux derniers concepts) :
${candidatsTerrier.join("\n") || "aucune"}

Curiosités notées par le lecteur aujourd'hui : ${curiosites.map((c) => c.texte || c.url).join(" ; ") || "aucune"}

Actu du jour (sources choisies par le lecteur) :
${actu || "indisponible"}

Compose le lot de ce soir : exactement 3 portes, chacune ouvrant sur un tunnel de 3 concepts.
Règles :
- ${premierSoir ? "C'est le tout premier soir : fais un best of, au moins 2 portes issues des graines." : "Si des graines restent, une porte au moins en est issue."}
- Si une curiosité existe, une porte en part.
- Au plus une porte « actu » : un article du jour ouvre sur un concept de fond qui l'éclaire. Jamais de faits divers.
- Une porte peut prolonger le terrier ; une autre peut faire un saut vers un thème différent.
- Une seule porte au plus est « mystère » : son titre reste caché, on montre seulement un indice intrigant.
- Chaque concept correspond à un article Wikipédia qui existe (donne son titre exact, en français de préférence).
- Les 3 concepts d'une porte s'enchaînent de proche en proche, du plus concret au plus large.
- Le teaser annonce en une phrase intrigante une porte possible pour demain (une graine restante si possible).

Format JSON :
{"portes":[{"type":"graine|actu|terrier|curiosite|saut","titre":"...","accroche":"une phrase qui intrigue","mystere":false,"indice":null,
"actu":{"media":"...","titre":"...","lien":"..."} ou null,
"concepts":[{"titre":"titre court du concept","wikipedia":"Titre exact de l'article","theme":"Sciences & techno|Histoire & sociétés|Stats, maths, data|Philo & idées"}]}],
"teaserDemain":"..."}`;

  const resultat = await groq(MODELE_REDACTION, SYSTEME_CHEVET, consigne, 2500);
  const portes = (resultat.portes ?? []).slice(0, 3);
  if (portes.length === 0) throw new Error("Composition vide");

  // Vérifie que chaque article existe vraiment.
  for (const porte of portes) {
    const resolus = await Promise.all(
      (porte.concepts ?? []).slice(0, 3).map(async (c: Json) => ({ ...c, wiki: await resoudreArticle(c.wikipedia ?? c.titre) })),
    );
    porte.concepts = resolus.filter((c) => c.wiki);
    porte.id = slug(porte.titre ?? "porte");
  }

  return {
    portes: portes.filter((p: Json) => p.concepts.length > 0),
    teaserDemain: resultat.teaserDemain ?? "",
    curiosites: curiosites.map((c) => c.id),
    flux: flux.etat,
  };
}

// ------------------------------------------------------------------ Étapes 1-3 : rédiger une porte

async function rediger(travail: Json, i: number, profil: Json) {
  const porte = travail.portes?.[i];
  if (!porte) return;
  for (const c of porte.concepts) {
    if (!c.extrait) c.extrait = await extrait(c.wiki.titre, c.wiki.langue);
  }

  const consigne = `${portraitLecteur(profil)}

Porte : « ${porte.titre} » — ${porte.accroche}
${porte.actu ? `Elle part de cette actu : [${porte.actu.media}] ${porte.actu.titre}` : ""}

Concepts du tunnel, dans l'ordre, avec l'article Wikipédia qui fait foi :
${porte.concepts.map((c: Json, k: number) => `### Concept ${k + 1} : ${c.titre} (thème ${c.theme})\n${c.extrait}`).join("\n\n")}

Écris les cartes de chaque concept.
Règles :
- N'utilise que des faits présents dans l'article fourni. Pas de chiffre, de date ni de nom qui n'y figure pas.
- 2 à 5 cartes par concept, 40 mots au plus par carte, une idée par carte.
- Raconte : une scène, une personne, une anecdote vraie de l'article ; pas de liste, pas de jargon.
- "question" : la question qui ouvre le concept et donne envie de glisser.
- Le premier concept accroche la porte ; chaque concept suivant s'enchaîne avec le précédent.
- "aRetenir" : une phrase à retenir, 25 mots au plus.
- "aRaconter" : la version à placer dans une conversation, 35 mots au plus.
- "ordreDeGrandeur" : un chiffre de l'article qui ancre l'idée, ou null.
- "qcm" : une question de révision, 3 choix, la bonne en premier, et "autreAngle" qui réexplique autrement en 30 mots.
- "creuser" : 3 questions courtes qu'on aurait envie de poser après cette carte.

Format JSON :
{"concepts":[{"titre":"...","question":"...","cartes":["...","..."],"aRetenir":"...","aRaconter":"...","ordreDeGrandeur":"..." ou null,
"qcm":{"question":"...","choix":["bonne","fausse","fausse"],"autreAngle":"..."},"creuser":["...","...","..."]}]}`;

  const resultat = await groq(MODELE_REDACTION, SYSTEME_CHEVET, consigne, 3200);
  porte.redaction = resultat.concepts ?? [];
}

// ------------------------------------------------------------------ Étapes 4-6 : relecture factuelle

async function relire(travail: Json, i: number) {
  const porte = travail.portes?.[i];
  if (!porte?.redaction?.length) return;

  const consigne = `Tu es relecteur factuel. Pour chaque concept, compare chaque carte, "aRetenir", "aRaconter",
"ordreDeGrandeur" et le QCM à l'article source. Corrige toute affirmation absente de l'article ou déformée,
ou supprime la carte si elle ne peut pas être sauvée (garde au moins 2 cartes). Ne change pas le style ni la structure.

Articles sources :
${porte.concepts.map((c: Json, k: number) => `### Concept ${k + 1} : ${c.titre}\n${c.extrait}`).join("\n\n")}

Texte à relire :
${JSON.stringify({ concepts: porte.redaction })}

Réponds avec le même JSON corrigé, plus un champ "corrections" listant brièvement ce que tu as changé :
{"concepts":[...],"corrections":["..."]}`;

  const resultat = await groq(MODELE_RELECTURE, SYSTEME_CHEVET, consigne, 3200);
  if (Array.isArray(resultat.concepts) && resultat.concepts.length === porte.redaction.length) {
    porte.redaction = resultat.concepts;
  }
  porte.corrections = resultat.corrections ?? [];
}

// ------------------------------------------------------------------ Étape 7 : pépite et assemblage

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
  );

  const portes = (travail.portes ?? [])
    .filter((p: Json) => p.redaction?.length)
    .map((p: Json) => ({
      id: p.id,
      titre: p.titre,
      accroche: p.accroche,
      mystere: !!p.mystere,
      indice: p.indice ?? null,
      concepts: p.redaction.map((r: Json, k: number) => {
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
      }),
    }));

  if (portes.length === 0) throw new Error("Aucune porte rédigée");

  // Les curiosités utilisées sortent de la boîte.
  if (travail.curiosites?.length) {
    await admin.from("chevet_curiosites").update({ statut: "porte_generee" }).in("id", travail.curiosites);
  }

  return {
    jour: lot.jour,
    portes,
    pepite: { texte: pepite.texte ?? "" },
    teaserDemain: travail.teaserDemain ?? "",
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

/** Fait avancer le lot d'une étape. Renvoie l'état public du lot. */
async function avancer(userId: string | null) {
  const lot = await lotDuJour(userId);
  if (lot.statut === "pret" || lot.statut === "erreur") return etatPublic(lot);

  // Verrou : une seule étape à la fois par lot.
  const maintenant = new Date();
  const { data: verrouille } = await admin.from("chevet_lots")
    .update({ verrou_jusqua: new Date(maintenant.getTime() + 140_000).toISOString() })
    .eq("id", lot.id)
    .eq("etape", lot.etape)
    .or(`verrou_jusqua.is.null,verrou_jusqua.lt."${maintenant.toISOString()}"`)
    .select().maybeSingle();
  if (!verrouille) return { ...etatPublic(lot), occupe: true };

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

  const { data: apres } = await admin.from("chevet_lots").update(maj).eq("id", lot.id).select().single();
  return etatPublic(apres ?? { ...lot, ...maj });
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

    // action "avancer" : l'utilisateur connecté.
    const jeton = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const { data: utilisateur } = await admin.auth.getUser(jeton);
    if (!utilisateur?.user) return reponse({ erreur: "Utilisateur non connecté" }, 401);
    return reponse(await avancer(utilisateur.user.id));
  } catch (e) {
    return reponse({ erreur: String(e) }, 500);
  }
});
