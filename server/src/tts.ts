// Max ki natural aawaz: Gemini TTS text ko insaan jaisi aawaz (WAV) mein badalta hai.
// Free tier par chalta hai, lekin limit kam hai — fail ho to phone apni offline aawaz use kar leta hai.
import { ApiError, GoogleGenAI } from "@google/genai";
import { HttpError, isTimeout } from "./brain.js";
import { config } from "./config.js";

const ai = new GoogleGenAI({ apiKey: config.GEMINI_API_KEY });

// Male aur female dono tarah ki aawazein — app ki Settings mein chun sakte ho.
export const VOICES = ["Puck", "Charon", "Orus", "Fenrir", "Kore", "Aoede", "Leda", "Zephyr"] as const;

export async function synthesize(text: string, voice: string): Promise<Buffer> {
  const voiceName = (VOICES as readonly string[]).includes(voice) ? voice : config.GEMINI_TTS_VOICE;
  // Sirf wahi text bhejo jo bolna hai. Saath mein style instruction bhejne par TTS kabhi-kabhi
  // instruction bhi zor se bol deta tha. Hinglish text par Indian accent waise bhi apne aap aata hai.
  const prompt = text;

  for (const model of config.GEMINI_TTS_MODELS) {
    try {
      const res = await ai.models.generateContent({
        model,
        contents: prompt,
        config: {
          responseModalities: ["AUDIO"],
          speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName } } },
          abortSignal: AbortSignal.timeout(12_000),
        },
      });
      const audio = res.candidates?.[0]?.content?.parts?.find((p) => p.inlineData?.data)?.inlineData;
      if (!audio?.data) continue;
      const bytes = Buffer.from(audio.data, "base64");
      const mime = (audio.mimeType ?? "").toLowerCase();
      if (mime.includes("wav")) return bytes;
      // Kuch models raw PCM dete hain ("audio/L16;rate=24000") — usme WAV header jodna padta hai.
      const rate = Number(mime.match(/rate=(\d+)/)?.[1] ?? 24000);
      return pcmToWav(bytes, rate);
    } catch (err) {
      if (isTimeout(err)) {
        console.warn(`  🔇 TTS ${model} slow (12 sec), agla try...`);
        continue;
      }
      if (!(err instanceof ApiError)) throw err;
      console.warn(`  🔇 TTS ${model} fail (${err.status}), agla try...`);
    }
  }
  throw new HttpError(503, "Aawaz nahi ban paayi");
}

function pcmToWav(pcm: Buffer, sampleRate: number): Buffer {
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16); // fmt chunk size
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28); // byte rate (16-bit mono)
  header.writeUInt16LE(2, 32); // block align
  header.writeUInt16LE(16, 34); // bits per sample
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}
