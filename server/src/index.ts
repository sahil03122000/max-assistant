// HTTP API jise Android app (aur baad mein laptop app) call karega.
//
//   POST /v1/chat           { sessionId, message }            → Max ka jawab / phone ke liye kaam
//   POST /v1/chat/actions   { sessionId, results: [...] }     → phone ne kaam karke result bheja
//   GET  /v1/memories                                         → Max ko kya-kya yaad hai
//   DELETE /v1/memories/:id                                   → koi memory hatao
//
// Har request mein header chahiye:  Authorization: Bearer <MAX_API_TOKEN>
import { timingSafeEqual } from "node:crypto";
import express, { type NextFunction, type Request, type Response } from "express";
import { z } from "zod";
import { handleActionResults, handleUserMessage, HttpError, type TurnResult } from "./brain.js";
import { apkDownloadUrl, latestRelease, saveLog } from "./appUpdate.js";
import { nextCallLine } from "./callTalk.js";
import { config } from "./config.js";
import { memoryBackend, memoryStore } from "./memory.js";
import { getSession } from "./sessions.js";
import { transcribe } from "./transcribe.js";
import { synthesize } from "./tts.js";

// Abhi ek token = ek user. Startup ke time yahan proper login (Supabase Auth) aayega
// aur userId token se nikalega.
const DEFAULT_USER = "default";

const app = express();
app.use(express.json({ limit: "100kb" }));

app.get("/health", (_req, res) => {
  res.json({ ok: true });
});

app.use("/v1", (req, _res, next) => {
  const expected = Buffer.from(`Bearer ${config.MAX_API_TOKEN}`);
  const given = Buffer.from(req.header("authorization") ?? "");
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) throw new HttpError(401, "Unauthorized");
  next();
});

const short = z.string().trim().max(200).optional();
const ProfileBody = z.object({
  callingName: z.string().trim().max(60).optional(),
  gender: z.enum(["male", "female", "unspecified"]).optional(),
  addressStyle: z.enum(["aap", "tum"]).optional(),
  city: short,
  profession: short,
  interests: z.string().trim().max(500).optional(),
  birthdayToday: z.boolean().optional(),
  musicApps: z.array(z.string().trim().max(40)).max(10).optional(),
  musicApp: z.string().trim().max(40).optional(),
});

const ChatBody = z.object({
  sessionId: z.string().min(1).max(100),
  message: z.string().trim().min(1).max(2000),
  profile: ProfileBody.optional(),
});

app.post("/v1/chat", async (req, res) => {
  const body = ChatBody.parse(req.body);
  console.log(`🗣️  ${body.message}`);
  res.json(logTurn(await handleUserMessage(getSession(DEFAULT_USER, body.sessionId), body.message, body.profile)));
});

const ActionsBody = z.object({
  sessionId: z.string().min(1).max(100),
  results: z
    .array(
      z.object({
        id: z.string(),
        ok: z.boolean(),
        result: z.unknown().optional(),
        error: z.string().max(500).optional(),
      }),
    )
    .max(20),
});

app.post("/v1/chat/actions", async (req, res) => {
  const body = ActionsBody.parse(req.body);
  for (const r of body.results) console.log(`   📱 ${r.ok ? "ok" : `FAIL: ${r.error}`}`);
  res.json(logTurn(await handleActionResults(getSession(DEFAULT_USER, body.sessionId), body.results)));
});

function logTurn(turn: TurnResult): TurnResult {
  if (turn.status === "needs_action") {
    for (const a of turn.actions) console.log(`   → ${a.name} ${JSON.stringify(a.args)}`);
  }
  if (turn.reply) console.log(`🤖 ${turn.reply}`);
  return turn;
}

const CallTurnBody = z.object({
  callee: z.string().trim().min(1).max(80),
  owner: z.string().trim().min(1).max(60).default("Sahil"),
  goal: z.string().trim().min(1).max(500),
  transcript: z.array(z.object({ who: z.enum(["max", "them"]), text: z.string().trim().max(1000) })).max(30),
});

// EXPERIMENT: Max call par khud baat kar raha hai — agli line kya bole.
app.post("/v1/call/turn", async (req, res) => {
  const body = CallTurnBody.parse(req.body);
  const last = body.transcript.at(-1);
  if (last?.who === "them") console.log(`📞 ${body.callee}: ${last.text}`);
  const turn = await nextCallLine(body);
  console.log(`📞 Max: ${turn.say}${turn.done ? "  [END]" : ""}`);
  res.json(turn);
});

const TtsBody = z.object({
  text: z.string().trim().min(1).max(1500),
  voice: z.string().max(40).optional(),
});

// Text → WAV audio (Max ki natural aawaz).
app.post("/v1/tts", async (req, res) => {
  const body = TtsBody.parse(req.body);
  const audio = await synthesize(body.text, body.voice ?? config.GEMINI_TTS_VOICE);
  res.type("audio/wav").send(audio);
});

// Max ke bolte waqt user ne beech mein jo bola — WAV recording → Roman Hinglish text.
app.post("/v1/transcribe", express.raw({ type: "audio/*", limit: "3mb" }), async (req, res) => {
  if (!Buffer.isBuffer(req.body) || req.body.length < 1000) throw new HttpError(400, "Audio nahi mila");
  const text = await transcribe(req.body);
  console.log(`🎙️  (beech mein) ${text || "(kuch saaf nahi)"}`);
  res.json({ text });
});

// Phone app ka naya version (GitHub Release se).
app.get("/v1/app/latest", async (_req, res) => {
  res.json({ release: await latestRelease() });
});

app.get("/v1/app/download", async (_req, res) => {
  res.json({ url: await apkDownloadUrl() });
});

// "Logs bhejo" — phone ke debug logs (private, Supabase Storage mein).
app.post("/v1/logs", express.text({ type: "text/*", limit: "2mb" }), async (req, res) => {
  if (typeof req.body !== "string" || !req.body.trim()) throw new HttpError(400, "Log khaali hai");
  const name = await saveLog(req.body);
  console.log(`📤 Phone ke logs aaye: ${name}`);
  res.json({ ok: true, name });
});

app.get("/v1/memories", async (_req, res) => {
  res.json({ memories: await memoryStore.list(DEFAULT_USER) });
});

app.delete("/v1/memories/:id", async (req, res) => {
  const removed = await memoryStore.remove(DEFAULT_USER, req.params.id);
  if (!removed) throw new HttpError(404, "Memory nahi mili");
  res.json({ ok: true });
});

app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
  if (err instanceof HttpError) {
    res.status(err.status).json({ error: err.message });
  } else if (err instanceof z.ZodError) {
    res.status(400).json({ error: "Invalid request", details: err.issues });
  } else {
    console.error(err);
    res.status(500).json({ error: "Server mein kuch gadbad ho gayi" });
  }
});

// 0.0.0.0 taaki same WiFi par phone bhi laptop ke server tak pahunch sake.
app.listen(config.PORT, "0.0.0.0", () => {
  console.log(`🤖 Max server chal raha hai: http://localhost:${config.PORT}  (model: ${config.GEMINI_MODEL}, memory: ${memoryBackend})`);
});
