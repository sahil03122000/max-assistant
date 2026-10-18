// Max ka DIMAAG. Flow:
//
//   phone: "papa ko call karo aur 7 baje ka alarm lagao"
//     → AI sochta hai → call_contact("Papa") + set_alarm(7, 0) maangta hai
//     → server phone ko bolta hai "ye 2 kaam karo" (status: needs_action)
//     → phone karta hai, results bhejta hai (/v1/chat/actions)
//     → AI results dekh kar jawab banata hai: "Papa ko call laga di aur 7 baje ka alarm set hai"
//
// Server tools (memory) beech mein khud chal jaate hain, phone ko pata bhi nahi chalta.
import { randomUUID } from "node:crypto";
import { ApiError, GoogleGenAI, type Content, type FunctionCall, type FunctionDeclaration, type Part } from "@google/genai";
import { config } from "./config.js";
import { memoryStore } from "./memory.js";
import { groqEnabled, GroqError, groqThink } from "./groq.js";
import { clarifyMessage } from "./clarify.js";
import { maskPrivate } from "./privacy.js";
import { buildSystemPrompt } from "./prompt.js";
import { trimHistory, type PendingAction, type PendingSlot, type Session, type UserProfile } from "./sessions.js";
import { ALL_TOOLS, isDeviceTool, runServerTool } from "./tools.js";
import { searchYouTube } from "./youtube.js";

const ai = new GoogleGenAI({ apiKey: config.GEMINI_API_KEY });
const MAX_STEPS = 8;
const STALE_LOCK_MS = 90_000;
// Free limit khatam hone wale model ko 1 ghante tak chhod do — har request par pehle use try karna time barbaad karta tha.
const LIMIT_COOLDOWN_MS = 60 * 60 * 1000;
const limitHitUntil = new Map<string, number>();

export type TurnResult =
  | { status: "done"; reply: string }
  | { status: "needs_action"; reply: string; actions: PendingAction[] };

export interface ActionResult {
  id: string;
  ok: boolean;
  result?: unknown;
  error?: string;
}

export class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export function handleUserMessage(session: Session, rawText: string, profile?: UserProfile): Promise<TurnResult> {
  if (profile) session.profile = profile;
  const { text: masked, hidden } = maskPrivate(rawText);
  const text = clarifyMessage(masked);
  if (hidden.length) console.log(`   🔒 private chhupaya: ${hidden.join(", ")}`);
  return withSessionLock(session, () => {
    const parts: Part[] = [];
    // User ne naya command de diya jab pichhle kaam pending the — unhe cancel maan lo.
    if (session.pending) {
      parts.push(...resolveSlots(session.pending, () => ({ ok: false, error: "Cancelled: user ne naya command de diya" })));
      session.pending = null;
    }
    parts.push({ text });
    trimHistory(session.history);
    session.history.push({ role: "user", parts });
    return runLoop(session);
  });
}

export function handleActionResults(session: Session, results: ActionResult[]): Promise<TurnResult> {
  return withSessionLock(session, () => {
    if (!session.pending) throw new HttpError(409, "Is session mein koi pending action nahi hai");
    const byId = new Map(results.map((r) => [r.id, r]));
    const parts = resolveSlots(session.pending, (action) => {
      const r = byId.get(action.id);
      if (!r) return { ok: false, error: "Phone ne is kaam ka result nahi bheja" };
      return r.ok ? { ok: true, result: r.result ?? null } : { ok: false, error: r.error ?? "Failed" };
    });
    session.pending = null;
    session.history.push({ role: "user", parts });
    return runLoop(session);
  });
}

// Ek session par ek time pe ek hi kaam — nayi baat aaye (user ne sochte waqt baat badli) to mana nahi,
// line mein lago: pichhla khatam hote hi nayi chalegi (aur use pichhli baat bhi history mein dikhegi).
// Error aaye to history wapas pehle jaisi, taaki adhoori baat-cheet se agli request kharab na ho.
async function withSessionLock(session: Session, fn: () => Promise<TurnResult>): Promise<TurnResult> {
  const previous = session.lock;
  let release!: () => void;
  session.lock = new Promise<void>((r) => (release = r));
  // Pichhla kaam atak gaya ho to zyada se zyada 90 sec ruko — warna session hamesha ke liye ruk jaata.
  await Promise.race([previous, new Promise((r) => setTimeout(r, STALE_LOCK_MS))]);
  session.busy = true;
  session.busySince = Date.now();
  const historyLength = session.history.length;
  const pending = session.pending;
  try {
    return await fn();
  } catch (err) {
    session.history.length = Math.min(session.history.length, historyLength);
    session.pending = pending;
    throw err;
  } finally {
    session.busy = false;
    release();
  }
}

function resolveSlots(slots: PendingSlot[], resultFor: (action: PendingAction) => Record<string, unknown>): Part[] {
  return slots.map((slot) =>
    "response" in slot ? slot.response : functionResponse(slot.callId, slot.action.name, resultFor(slot.action)),
  );
}

function functionResponse(id: string | undefined, name: string, response: Record<string, unknown>): Part {
  return { functionResponse: { id, name, response } };
}

async function runLoop(session: Session): Promise<TurnResult> {
  for (let step = 0; step < MAX_STEPS; step++) {
    const memories = await memoryStore.list(session.userId);
    const content = await think(session.history, buildSystemPrompt(memories, new Date(), session.profile));
    session.history.push(content);

    const reply = spokenText(content);
    const calls = (content.parts ?? []).flatMap((p) => (p.functionCall ? [p.functionCall] : []));
    if (calls.length === 0) return { status: "done", reply: reply || "Hmm, ek baar phir se bolo?" };

    const slots: PendingSlot[] = [];
    for (const call of calls) slots.push(await toSlot(session.userId, call));

    const actions = slots.flatMap((s) => ("action" in s ? [s.action] : []));
    if (actions.length > 0) {
      session.pending = slots;
      return { status: "needs_action", reply, actions };
    }
    // Sirf server tools the (jaise remember) — results AI ko do aur agla step chalao.
    session.history.push({ role: "user", parts: resolveSlots(slots, () => ({})) });
  }
  return { status: "done", reply: "Ye thoda complicated ho gaya, ek-ek karke bologe?" };
}

async function toSlot(userId: string, call: FunctionCall): Promise<PendingSlot> {
  const name = call.name ?? "";
  const args = call.args ?? {};
  if (isDeviceTool(name)) {
    // Gemini kabhi id deta hai, kabhi nahi. Phone ko hamesha ek id chahiye result match karne ke liye.
    return { action: { id: call.id ?? randomUUID(), name, args: await enrichDeviceArgs(name, args) }, callId: call.id };
  }
  return { response: functionResponse(call.id, name, await runServerTool(userId, name, args)) };
}

// Google ke models par kabhi-kabhi load zyada hota hai (503). Main model busy ho to backup models
// ek-ek karke try karo, aur aakhir mein thoda ruk kar main model ek baar aur.
const ATTEMPTS = [
  { model: config.GEMINI_MODEL, delayMs: 0 },
  ...config.GEMINI_FALLBACK_MODELS.map((model) => ({ model, delayMs: 0 })),
  { model: config.GEMINI_MODEL, delayMs: 1500 },
];

// Kuch kaam phone se behtar server kar sakta hai — phone ko bhejne se pehle extra info jod do.
async function enrichDeviceArgs(name: string, args: Record<string, unknown>): Promise<Record<string, unknown>> {
  // Specific gaana bola tha (AI ne video_id nahi diya) — sabse upar wala video chala do.
  if (name === "play_on_youtube" && !args.video_id && typeof args.query === "string") {
    const [top] = await searchYouTube(args.query, 1);
    // Title bhi — Max bataye ki asal mein kya chalaya ("Shree Hanuman Chalisa"), galat ho to user turant bol sake.
    if (top) return { ...args, video_id: top.videoId, video_title: top.title };
  }
  return args;
}

/** [tools] khaali = sirf baat (jaise call par Max ki line), koi kaam nahi. */
export async function think(history: Content[], systemInstruction: string, tools: FunctionDeclaration[] = ALL_TOOLS): Promise<Content> {
  // Pehle Groq (free, ~1 sec). Limit/busy ho to Gemini.
  if (groqEnabled()) {
    for (const model of config.GROQ_MODELS) {
      const key = `groq:${model}`;
      if ((limitHitUntil.get(key) ?? 0) > Date.now()) continue;
      try {
        return await groqThink(model, history, systemInstruction, tools);
      } catch (err) {
        if (err instanceof GroqError && err.status === 429) {
          // Groq free: ~8000 tokens/minute (jaldi khulti hai) aur ~1000 requests/din. Message mein
          // "Upgrade ... today" bhi likha hota hai — isliye sirf asli "per day" ko din wali limit maano.
          const daily = /per day|\(RPD\)|\(TPD\)/i.test(err.message);
          const waitSec = Number(err.message.match(/try again in ([\d.]+)s/i)?.[1] ?? 20);
          limitHitUntil.set(key, Date.now() + (daily ? LIMIT_COOLDOWN_MS : Math.ceil(waitSec * 1000)));
          console.warn(`  🚫 Groq ${model} ki limit (${daily ? "aaj ki" : `${Math.ceil(waitSec)} sec`}), agla...`);
        } else {
          console.warn(`  ⚡ Groq ${model} fail (${err instanceof Error ? err.message.slice(0, 80) : err}), agla...`);
        }
      }
    }
  }
  let lastError: unknown;
  const triedThisTurn = new Set<string>();
  for (const { model, delayMs } of ATTEMPTS) {
    // Har model ki free limit alag hai — jiski khatam ho chuki (is ya pichhli request mein) use abhi mat try karo.
    if ((limitHitUntil.get(model) ?? 0) > Date.now()) continue;
    if (triedThisTurn.has(model) && delayMs === 0) continue;
    triedThisTurn.add(model);
    if (delayMs) await new Promise((r) => setTimeout(r, delayMs));
    try {
      const res = await ai.models.generateContent({
        model,
        contents: history,
        // Google ka call kabhi atak jaata hai — 15 sec mein jawab na aaye to chhodo, agla model.
        config: {
          systemInstruction,
          ...(tools.length ? { tools: [{ functionDeclarations: tools }] } : {}),
          abortSignal: AbortSignal.timeout(15_000),
        },
      });
      const content = res.candidates?.[0]?.content;
      // Kaam (jaise gaana) ke baad AI kabhi kuch bolna zaroori nahi samajhta — error nahi, chhota sa "Theek hai".
      if (!content?.parts?.length) return { role: "model", parts: [{ text: "Theek hai." }] };
      return { role: "model", parts: content.parts };
    } catch (err) {
      lastError = err;
      if (isTimeout(err)) {
        console.warn(`  ⌛ ${model} ne 15 sec mein jawab nahi diya, agla model...`);
        continue;
      }
      if (!(err instanceof ApiError)) throw err;
      if (err.status === 429) {
        limitHitUntil.set(model, Date.now() + LIMIT_COOLDOWN_MS);
        console.warn(`  🚫 ${model} ki free limit khatam, agla model try kar raha hoon...`);
        continue;
      }
      if (err.status !== 500 && err.status !== 503) {
        console.error(`Gemini error ${err.status} (${model}): ${err.message.slice(0, 200)}`);
        break;
      }
      console.warn(`  ⏳ ${model} par load hai, dobara try kar raha hoon...`);
    }
  }
  if (lastError instanceof ApiError && lastError.status === 429) {
    throw new HttpError(429, "Max abhi thoda busy hai (free limit khatam). Thodi der baad try karo.");
  }
  if (isTimeout(lastError)) throw new HttpError(504, "AI abhi bahut slow hai, ek baar phir bolo");
  throw new HttpError(502, "AI se baat nahi ho paayi");
}

/** AbortSignal.timeout se ruka hua call (Node mein TimeoutError / AbortError). */
export function isTimeout(err: unknown): boolean {
  const name = (err as { name?: string } | null)?.name;
  return name === "TimeoutError" || name === "AbortError";
}

function spokenText(content: Content): string {
  const text = (content.parts ?? [])
    .filter((p) => p.text && !p.thought)
    .map((p) => p.text)
    .join("")
    .trim();
  return toSpokenLine(stripLeakedReasoning(text));
}

// Jawab bola jaata hai — list ("1. Sitaare\n2. Shayad") ko ek saaf vaakya mein: "Sitaare, Shayad. Kaunsa?"
function toSpokenLine(text: string): string {
  const lines = text
    .split(/\n+/)
    .map((l) => l.replace(/^\s*(?:\d+[.)]|[-*•])\s*/, "").replace(/\*\*/g, "").trim())
    .filter(Boolean);
  return lines.reduce((acc, line) => (!acc ? line : /[.?!,:]$/.test(acc) ? `${acc} ${line}` : `${acc}, ${line}`), "");
}

// Kuch (khaas kar backup) models kabhi-kabhi apni "andar ki soch" bhi normal text mein likh dete hain
// ("The user spoke in Hindi... Wait, the prompt says..."), jo phir Max zor se bol deta tha.
const REASONING_MARKERS = /\b(the user|user (?:said|wrote|spoke|used)|the prompt|system prompt|let'?s (?:respond|reply)|wait,|i should|i will reply|my reply)\b/i;

export function stripLeakedReasoning(text: string): string {
  if (!REASONING_MARKERS.test(text)) return text;
  const lines = text.split("\n").map((l) => l.trim()).filter(Boolean);
  // Asli jawab aksar aakhri line hoti hai — aur kabhi quotes ke baad chipka hota hai: ...: "x" or "y"Jawab
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i]!;
    const afterQuotes = line.includes('"') ? line.slice(line.lastIndexOf('"') + 1).trim() : line;
    const candidate = afterQuotes || line;
    if (candidate && !REASONING_MARKERS.test(candidate)) {
      console.warn("   ✂️ AI ki andar ki soch hata di");
      return candidate.replace(/^["'“”]+|["'“”]+$/g, "").trim();
    }
  }
  return text;
}
