// Saari settings .env file se aati hain. Agar kuch missing hai to server start hi nahi hoga
// aur clear error dikhayega (React mein jaise env vars VITE_* se aate hain, waise hi).
import "dotenv/config";
import { z } from "zod";

const Env = z.object({
  GEMINI_API_KEY: z.string().min(1, "GEMINI_API_KEY .env mein nahi mili"),
  // Free tier wala fast model. Google naye model laata rehta hai, isliye .env se badal sakte ho.
  GEMINI_MODEL: z.string().default("gemini-3.8-flash"),
  // Main model par zyada load ho (503) to Max in models ko ek-ek karke try karega (comma se alag).
  GEMINI_FALLBACK_MODELS: z
    .string()
    .default("gemini-3.5-flash-lite,gemini-flash-lite-latest,gemini-3.1-flash-lite,gemini-3.7-flash,gemini-3.6-flash,gemini-3-flash-preview,gemini-3.5-flash")
    .transform((s) => s.split(",").map((m) => m.trim()).filter(Boolean)),
  // Groq (free, bahut tez) — ho to Max pehle yahi use karta hai, phir Gemini. https://console.groq.com
  GROQ_API_KEY: z.string().min(1).optional(),
  GROQ_MODELS: z
    .string()
    .default("openai/gpt-oss-120b,qwen/qwen3.8-27b,openai/gpt-oss-20b")
    .transform((s) => s.split(",").map((m) => m.trim()).filter(Boolean)),
  // Natural aawaz (text-to-speech). Pehla busy ho to agla.
  GEMINI_TTS_MODELS: z
    .string()
    .default("gemini-3.8-flash-lite-tts,gemini-3.1-flash-tts-preview,gemini-2.5-flash-preview-tts,gemini-3.8-flash-tts")
    .transform((s) => s.split(",").map((m) => m.trim()).filter(Boolean)),
  GEMINI_TTS_VOICE: z.string().default("Orus"),
  // Live (aawaz se aawaz) model — free tier par sabse tez mila. Badalna ho: gemini-3.8-live, gemini-2.5-flash-native-audio-latest
  LIVE_MODEL: z.string().default("gemini-3.1-flash-live-preview"),
  // Android app har request mein ye token bhejega, taaki koi aur aapka server use na kare.
  MAX_API_TOKEN: z.string().min(16, "MAX_API_TOKEN kam se kam 16 characters ka hona chahiye"),
  PORT: z.coerce.number().default(3000),
  TIMEZONE: z.string().default("Asia/Kolkata"),
  DATA_DIR: z.string().default("./data"),
  // Memory database (cloud). Dono na hon to memory local JSON file mein save hoti hai.
  SUPABASE_URL: z.string().url().optional(),
  SUPABASE_SERVICE_KEY: z.string().min(1).optional(),
  // App update: GitHub ka fine-grained token (sirf is repo, Contents: Read-only) — nayi APK Release se dene ke liye.
  GITHUB_TOKEN: z.string().min(1).optional(),
  GITHUB_REPO: z.string().default("sahil03122000/max-assistant"),
});

const parsed = Env.safeParse(process.env);
if (!parsed.success) {
  console.error("❌ .env mein gadbad hai:");
  for (const issue of parsed.error.issues) console.error(`   - ${issue.path.join(".")}: ${issue.message}`);
  console.error("   .env.example dekh kar .env file banao.");
  process.exit(1);
}

export const config = parsed.data;
