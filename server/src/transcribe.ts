// Max ke bolte-bolte user ne jo bola (phone ne record kiya), use Roman Hinglish text mein likhna.
// Phone ka apna speech recognizer us waqt nahi chal sakta (Max ki aawaz bhi sun leta), isliye
// phone echo-cancel ki hui recording bhejta hai aur Gemini likhta hai.
import { ApiError, GoogleGenAI } from "@google/genai";
import { isTimeout } from "./brain.js";
import { config } from "./config.js";

const ai = new GoogleGenAI({ apiKey: config.GEMINI_API_KEY });

const PROMPT =
  "Transcribe exactly what the person says in this audio, in Roman script (Hinglish/English — never Devanagari). " +
  "It is a short voice command or reply to an assistant named Max. Ignore any background voice of the assistant itself. " +
  "Output ONLY the spoken words, nothing else. If there is no clear human speech, output nothing.";

export async function transcribe(wav: Buffer): Promise<string> {
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
        config: { abortSignal: AbortSignal.timeout(12_000) },
      });
      return (res.text ?? "").trim().replace(/^["']|["']$/g, "");
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
