// Max ke bolte-bolte user ne jo bola (phone ne record kiya), use Roman Hinglish text mein likhna.
// Phone ka apna speech recognizer us waqt nahi chal sakta (Max ki aawaz bhi sun leta), isliye
// phone echo-cancel ki hui recording bhejta hai aur Gemini likhta hai.
import { ApiError, GoogleGenAI, Type } from "@google/genai";
import { isTimeout } from "./brain.js";
import { config } from "./config.js";
import { groqEnabled, groqTranscribe } from "./groq.js";

const ai = new GoogleGenAI({ apiKey: config.GEMINI_API_KEY });

// Dhyan: prompt mein "Max" ya command ke examples mat do — khaali aawaz par AI wahi bana deta tha ("Max next song").
const PROMPT =
  "Transcribe exactly the human speech in this audio, in Roman script (Hinglish/English — never Devanagari). " +
  "Write only words that are clearly spoken. Never guess or invent words. " +
  "If there is no clear human speech (silence, noise, music, only a machine voice), reply with exactly: <none>";

const NO_SPEECH = /^<?none>?\.?$/i;

/** Recording mein sabse tez aawaz ka level (dBFS). -45 se kam = asal mein koi bola hi nahi. */
function peakDb(wav: Buffer): number {
  let peak = 0;
  for (let i = 44; i + 1 < wav.length; i += 2) peak = Math.max(peak, Math.abs(wav.readInt16LE(i)));
  return peak === 0 ? -100 : 20 * Math.log10(peak / 32768);
}

// Whisper shor/chup par aksar ye "sunta" hai — inhe asli baat mat maano.
const WHISPER_HALLUCINATIONS = /^(thank you|thanks for watching|you|bye|subtitles?.*|please subscribe.*|धन्यवाद|शुक्रिया)[.!\s]*$/i;

export async function transcribe(wav: Buffer): Promise<string> {
  if (peakDb(wav) < -45) return "";
  // Pehle Groq Whisper (free, tez); fail ho to Gemini.
  if (groqEnabled()) {
    try {
      const text = await groqTranscribe(wav);
      return WHISPER_HALLUCINATIONS.test(text) ? "" : text;
    } catch (err) {
      console.warn(`  🎙️ Whisper fail (${err instanceof Error ? err.message.slice(0, 60) : err}), Gemini try...`);
    }
  }
  const models = [...config.GEMINI_FALLBACK_MODELS, config.GEMINI_MODEL];
  for (const model of models) {
    try {
      const res = await ai.models.generateContent({
        model,
        contents: [
          {
            role: "user",
            parts: [{ inlineData: { mimeType: "audio/wav", data: wav.toString("base64") } }, { text: PROMPT }],
          },
        ],
        config: {
          abortSignal: AbortSignal.timeout(12_000),
          // Pehle "kya sach mein koi insaan bola?" — shor par AI apne se shabd bana deta tha.
          responseMimeType: "application/json",
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              human_speech: { type: Type.BOOLEAN, description: "Kya recording mein saaf insaani bolna hai?" },
              text: { type: Type.STRING, description: "Bole gaye shabd Roman mein, warna khaali" },
            },
            required: ["human_speech", "text"],
          },
        },
      });
      const parsed = JSON.parse(res.text ?? "{}") as { human_speech?: boolean; text?: string };
      const text = (parsed.text ?? "").trim();
      return parsed.human_speech && !NO_SPEECH.test(text) ? text : "";
    } catch (err) {
      if (isTimeout(err) || err instanceof ApiError) {
        console.warn(`  🎙️ transcribe ${model} fail, agla try...`);
        continue;
      }
      throw err;
    }
  }
  return "";
}
