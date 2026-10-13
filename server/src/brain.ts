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
import { ApiError, GoogleGenAI, type Content, type FunctionCall, type Part } from "@google/genai";
import { config } from "./config.js";
import { memoryStore } from "./memory.js";
import { buildSystemPrompt } from "./prompt.js";
import { trimHistory, type PendingAction, type PendingSlot, type Session } from "./sessions.js";
import { ALL_TOOLS, isDeviceTool, runServerTool } from "./tools.js";
import { searchYouTube } from "./youtube.js";

const ai = new GoogleGenAI({ apiKey: config.GEMINI_API_KEY });
const MAX_STEPS = 8;

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

export function handleUserMessage(session: Session, text: string): Promise<TurnResult> {
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

// Ek session par ek time pe ek hi request. Error aaye to history wapas pehle jaisi kar do,
// taaki adhoori baat-cheet se agli request kharab na ho.
async function withSessionLock(session: Session, fn: () => Promise<TurnResult>): Promise<TurnResult> {
  if (session.busy) throw new HttpError(409, "Max abhi pichhli baat process kar raha hai");
  session.busy = true;
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
    const content = await think(session.history, buildSystemPrompt(memories, new Date()));
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
    if (top) return { ...args, video_id: top.videoId };
  }
  return args;
}

async function think(history: Content[], systemInstruction: string): Promise<Content> {
  let lastError: unknown;
  const quotaExhausted = new Set<string>();
  for (const { model, delayMs } of ATTEMPTS) {
    // Har model ki free limit alag hai — jiski khatam ho gayi use dobara mat try karo.
    if (quotaExhausted.has(model)) continue;
    if (delayMs) await new Promise((r) => setTimeout(r, delayMs));
    try {
      const res = await ai.models.generateContent({
        model,
        contents: history,
        config: { systemInstruction, tools: [{ functionDeclarations: ALL_TOOLS }] },
      });
      const content = res.candidates?.[0]?.content;
      if (!content?.parts?.length) throw new HttpError(502, "AI ne khaali jawab diya");
      return { role: "model", parts: content.parts };
    } catch (err) {
      lastError = err;
      if (!(err instanceof ApiError)) throw err;
      if (err.status === 429) {
        quotaExhausted.add(model);
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
  throw new HttpError(502, "AI se baat nahi ho paayi");
}

function spokenText(content: Content): string {
  return (content.parts ?? [])
    .filter((p) => p.text && !p.thought)
    .map((p) => p.text)
    .join("")
    .trim();
}
