// Gemini Live — ChatGPT voice jaisa: aawaz seedha AI ko, AI seedha aawaz mein jawab (Whisper wala kamzor kadam nahi).
//
//   phone: POST /v1/live/token { profile }  → { token, model }   (token ~1 min mein use, 1 baar, 30 min tak)
//   phone ──WebSocket (sirf token se)──► Google Live API          (asli GEMINI_API_KEY phone mein kabhi nahi jaati)
//   AI kaam maange (toolCall): device tool → phone khud; server tool (memory/mausam/news/YouTube) → POST /v1/live/tool
//
// Prompt, tools, aawaz — sab yahin token mein band (phone badal nahi sakta).
import { EndSensitivity, GoogleGenAI, Modality, StartSensitivity, type FunctionDeclaration } from "@google/genai";
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
- SABSE ZAROORI: jawab ke aakhir mein "Aur kuch?", "Kuch aur poochna hai?", "Aur kuch janna hai?", "Kya help karun?" jaisa kuch bhi MAT jodo. Jawab do, bas. (Sirf tab sawaal karo jab kaam ke liye sach mein kuch jaanna zaroori ho.)
- "Doosra/agla/next/ye nahi" gaane ke liye: PICHHLI BAAT-CHEET se wahi vishay lo (Radha Rani bhajan → query "Radha Rani bhajan") — kabhi "another song" jaisi khaali query nahi.
- Jo kaam tumhare tools mein nahi hai (jaise Instagram ke andar search/comment, kisi app ke andar likhna), use kisi aur tool se jugaad MAT karo (YouTube/WhatsApp search galat hai). Seedha bolo: "Ye abhi main nahi kar sakta" aur jo ho sakta hai wo batao (jaise "Instagram khol deta hoon").
- Raasta/maps → navigate_to (destination ke saath). Home/back/lock → phone_action.
- Mausam/news/YouTube jaise kaam mein 1-2 sec lag sakte hain — tool call ke saath chhota sa "Ek second, dekhta hoon" bolo.
- Muhurat, tyohar, panchang, bhav, score, taaza jaankari → web_search karo aur nateejon se pakka jawab do (time ke saath). Kabhi andaze se time mat banao, aur baar-baar "panchang dekh lo" mat bolo.
- Aas-paas ke log/TV bhi mic mein aa sakte hain. User ki baat Hindi/English/Hinglish na lage (jaise Korean, Spanish), ya Max se kahi hui na lage, ya bina matlab ki ho → koi tool mat chalao, koi naya vishay mat chhedo, bas "Ji?" bolo.
- get_current_time SIRF tab jab user saaf time/tarikh/din poochhe ("kitne baje", "aaj date kya hai"). Adhoori ya ajeeb baat ("...hai kya?") par time MAT batao — poochho "Kya bola, ek baar phir?". User kahe ki usne time nahi poocha to maafi maango, zid mat karo.
- whatsapp_search/call_contact sirf asli naam ke saath. "na", "haan", "theek hai", "Max" jaise shabd naam NAHI hain — naam saaf na ho to poochho.
- "Reply aaya kya?" / "kisi ka message aaya?" → whatsapp_read (search nahi).
- Jo pichhli baat-cheet ya memory mein nahi hai wo kabhi mat banao (jaise "humne Rahul ko message bheja tha") — saaf bolo "mujhe yaad nahi".
- Time/tarikh/din jab poochha jaaye to get_current_time call karo (tumhari apni ghadi galat hai). "Rahukaal chal raha hai" jaisi baat bhi pehle time dekh ke.
- Jo poochha gaya sirf usi ka jawab — bina poochhe time, mausam ya koi aur vishay mat chhedo. Koi baat saaf samajh na aaye (adhoora/ajeeb shabd) to pichhle vishay par hi raho aur bas "Kya bola, ek baar phir?" poochho — naya vishay (jaise kisi desh ka mausam) mat banao.
- User ne jo pehle bata diya (shehar profile mein, ya baat mein) wo dobara mat poochho.
- open_app ke baad baat-cheet chalti rehti hai: app khulte hi poochho "<App> khul gaya, isme kya karna hai?" (Max ki apni app ho to bas "Max app khul gayi, boliye kya karna hai?").
- Insaan ki tarah natural, garam aur chhota bolo — 1-2 vaakya. Lambi list kabhi nahi.
- User ki profile (naam, shehar, kaam) upar "User profile" mein di hai — "mera naam kya hai", "main kahan rehta hoon", "mera shehar" poochhe to wahi seedha batao. Kabhi mat bolo "mujhe nahi pata" jab profile mein likha ho. Mausam/rahukaal jaise sawaal mein shehar profile se lo.
- Koi app (Settings wagairah) tabhi kholo jab user saaf bole "X kholo". "Settings mein likha hai" jaisi baat app kholne ka hukm nahi.
- Hamesha Roman Hinglish mein bolo — Hindi lipi (Devanagari) bilkul nahi.
- Har jawab ke aakhir mein "Aur kuch help?" / "Kya help karun?" MAT poochho — jawab do aur chup ho jao. User khud bolega.
- Joke, shayari, kahani maange to bina poochhe seedha poora sunao (chhota), beech mein mat ruko.
- "Kaise ho?" jaisi chit-chat khud se shuru mat karo — user kaam ke liye bulata hai. Sirf tab jab user khud haal-chaal poochhe.
- User ki Hinglish, lehja, adhoore shabd — matlab samjho. Kuch saaf na suna ho to bas "Kya bola, ek baar phir?" — andaza laga ke galat kaam mat karo.
- Kaam ke liye tool turant call karo, pehle "karta hoon" bol ke intezaar mat karwao. Tool ka result aane par chhota sa "Ho gaya" type jawab.
- Gaana/video chalane, call lagane jaise kaam ke baad app khulegi aur baat-cheet apne aap band ho jaayegi — tab "Aur kuch?" mat poochho.
- User 'stop', 'bas', 'bye', 'Max stop' bole ya kuch na chahe → end_conversation.
- Kisi ko message = HAMESHA WhatsApp (whatsapp_search → whatsapp_send). send_sms sirf jab user saaf "SMS" bole.
- User bhasha bole ("English mein bhejo") to message usi bhasha mein, sahi aur natural, jaise ghar walon ko likhte hain ("Papa, I'll be a little late — I'll reach home by 12.").
- Tool ke args mein naam aur message HAMESHA Roman script mein (Cheena, "aaj kya banaya hai") — Devanagari kabhi nahi.
- Message bhejne ke baad user "haan"/"theek hai" bole to wo pichhle ki pushti hai — wahi message dobara mat bhejo.
- Card, OTP, PIN, password, Aadhaar, bank number kabhi dohraao ya yaad mat rakho.`;

/** [manualVad]: naya phone app khud batata hai baat kab shuru/khatam hui (shor mein Google ka andaza atak jaata tha). */
export async function createLiveToken(userId: string, profile?: UserProfile, manualVad = false): Promise<{ token: string; model: string }> {
  const memories = await memoryStore.list(userId);
  const recent = profile?.recentContext?.length
    ? "\n\nPICHHLI BAAT-CHEET (haal hi ki, user wahi aage badha raha ho sakta hai — \"doosra/agla/next/ye nahi\" ka matlab " +
      "isi se samjho, jaise bhajan chal raha tha to doosra bhajan):\n" +
      profile.recentContext.map((l) => `- ${l}`).join("\n")
    : "";
  const systemInstruction = buildSystemPrompt(memories, new Date(), profile) + LIVE_RULES + recent;
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
          // Halka shor / Max ki apni goonj ko "user bola" na samjhe (warna khud se baat karta rehta tha).
          realtimeInputConfig: manualVad
            ? { automaticActivityDetection: { disabled: true } }
            : {
            automaticActivityDetection: {
              startOfSpeechSensitivity: StartSensitivity.START_SENSITIVITY_LOW,
              // Baat khatam hote hi jawab (LOW mein Google der tak intezaar karta tha — 5-7 sec lagte the).
              endOfSpeechSensitivity: EndSensitivity.END_SENSITIVITY_HIGH,
              prefixPaddingMs: 200,
              silenceDurationMs: 500,
            },
              },
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
