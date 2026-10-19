// Gemini Live — ChatGPT voice jaisa: aawaz seedha AI ko, AI seedha aawaz mein jawab (Whisper wala kamzor kadam nahi).
//
//   phone: POST /v1/live/token { profile }  → { token, model }   (token ~1 min mein use, 1 baar, 30 min tak)
//   phone ──WebSocket (sirf token se)──► Google Live API          (asli GEMINI_API_KEY phone mein kabhi nahi jaati)
//   AI kaam maange (toolCall): device tool → phone khud; server tool (memory/mausam/news/YouTube) → POST /v1/live/tool
//
// Prompt, tools, aawaz — sab yahin token mein band (phone badal nahi sakta).
import { GoogleGenAI, Modality, type FunctionDeclaration } from "@google/genai";
import { config } from "./config.js";
import { memoryStore } from "./memory.js";
import { buildSystemPrompt } from "./prompt.js";
import type { UserProfile } from "./sessions.js";
import { ALL_TOOLS } from "./tools.js";

const ai = new GoogleGenAI({ apiKey: config.GEMINI_API_KEY, httpOptions: { apiVersion: "v1alpha" } });

const END_TOOL: FunctionDeclaration = {
  name: "end_conversation",
  description: "Baat-cheet band karo — jab user 'Max stop', 'bas', 'bye', 'theek hai bas' jaisa bole ya kaam poora ho gaya aur user kuch nahi chahta.",
  parameters: { type: "OBJECT" as never, properties: {} },
};

const LIVE_RULES = `

LIVE VOICE MODE (tum seedha user ki aawaz sun rahe ho, aur tumhari baat aawaz mein jaati hai):
- Insaan ki tarah natural, garam aur chhota bolo — 1-2 vaakya. Lambi list kabhi nahi.
- "Kaise ho?" jaisi chit-chat khud se shuru mat karo — user kaam ke liye bulata hai. Sirf tab jab user khud haal-chaal poochhe.
- User ki Hinglish, lehja, adhoore shabd — matlab samjho. Kuch saaf na suna ho to bas "Kya bola, ek baar phir?" — andaza laga ke galat kaam mat karo.
- Kaam ke liye tool turant call karo, pehle "karta hoon" bol ke intezaar mat karwao. Tool ka result aane par chhota sa "Ho gaya" type jawab.
- Gaana/video chalane, call lagane jaise kaam ke baad app khulegi aur baat-cheet apne aap band ho jaayegi — tab "Aur kuch?" mat poochho.
- User 'stop', 'bas', 'bye', 'Max stop' bole ya kuch na chahe → end_conversation.
- Tool ke args mein naam aur message HAMESHA Roman script mein (Cheena, "aaj kya banaya hai") — Devanagari kabhi nahi.
- Message bhejne ke baad user "haan"/"theek hai" bole to wo pichhle ki pushti hai — wahi message dobara mat bhejo.
- Card, OTP, PIN, password, Aadhaar, bank number kabhi dohraao ya yaad mat rakho.`;

export async function createLiveToken(userId: string, profile?: UserProfile): Promise<{ token: string; model: string }> {
  const memories = await memoryStore.list(userId);
  const systemInstruction = buildSystemPrompt(memories, new Date(), profile) + LIVE_RULES;
  const model = config.LIVE_MODEL;
  const token = await ai.authTokens.create({
    config: {
      uses: 1,
      expireTime: new Date(Date.now() + 30 * 60_000).toISOString(),
      newSessionExpireTime: new Date(Date.now() + 2 * 60_000).toISOString(),
      liveConnectConstraints: {
        model,
        config: {
          responseModalities: [Modality.AUDIO],
          systemInstruction,
          speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: config.GEMINI_TTS_VOICE } } },
          // Dono taraf ka likha hua bhi aaye — screen par dikhane, history aur logs ke liye.
          inputAudioTranscription: {},
          outputAudioTranscription: {},
          tools: [{ functionDeclarations: [...ALL_TOOLS, END_TOOL] }],
        },
      },
      httpOptions: { apiVersion: "v1alpha" },
    },
  });
  if (!token.name) throw new Error("Live token nahi bana");
  return { token: token.name, model };
}
