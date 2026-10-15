// Max ke "haath": wo kaam jo AI kar sakta hai.
//
// Do tarah ke tools hain:
//  - DEVICE tools: phone par chalte hain (call, SMS, alarm...). Server sirf batata hai "ye karo",
//    Android app karta hai aur result wapas bhejta hai.
//  - SERVER tools: server par hi chalte hain (memory save/delete).
import { Type, type FunctionDeclaration } from "@google/genai";
import { memoryStore } from "./memory.js";
import { searchYouTube } from "./youtube.js";

const contactName = {
  type: Type.STRING,
  description: "Contact ka naam jaise user ne bola, jaise 'Papa', 'Mummy', 'Rahul'. Phone ki contact list mein isi se dhundha jayega.",
};

const exactName = {
  type: Type.BOOLEAN,
  description:
    "true SIRF tab jab user ne pichhli list (jaise 'Papa ya Papaji?') mein se ek poora naam chuna ho — " +
    "contact_name mein wahi poora naam do. Pehli baar naam bolne par mat bhejo.",
};

export const DEVICE_TOOLS: FunctionDeclaration[] = [
  {
    name: "call_contact",
    description: "Phone se kisi contact ko call lagao.",
    parameters: { type: Type.OBJECT, properties: { contact_name: contactName, exact_name: exactName }, required: ["contact_name"] },
  },
  {
    name: "call_and_talk",
    description:
      "EXPERIMENT: Max khud contact ko call karke speaker par user ki taraf se baat karega aur jawab sun ke laayega. " +
      "Tab use karo jab user bole 'X ko call karke bol do ...' / 'X se pooch ke batao ...'. Sirf call lagani ho to call_contact. " +
      "Result mein poori baat-cheet aayegi — user ko 1-2 line mein batao X ne kya kaha.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        contact_name: contactName,
        exact_name: exactName,
        goal: {
          type: Type.STRING,
          description: "Call par kya kehna/poochhna hai, saaf likho, jaise 'Batana hai Sahil 10 minute late aayega' ya 'Poochhna hai khana bana ya nahi'.",
        },
      },
      required: ["contact_name", "goal"],
    },
  },
  {
    name: "send_sms",
    description: "Kisi contact ko SMS bhejo.",
    parameters: {
      type: Type.OBJECT,
      properties: { contact_name: contactName, exact_name: exactName, message: { type: Type.STRING, description: "Bhejne wala message" } },
      required: ["contact_name", "message"],
    },
  },
  {
    name: "whatsapp_search",
    description:
      "WhatsApp kholo aur uske andar chat dhundho (contacts, groups, WhatsApp-only naam bhi). Result mein milte chats ki list aati hai. " +
      "WhatsApp message bhejne ka PEHLA kadam yahi hai. Ek se zyada milein to user se poocho kaunsa (number/naam), phir whatsapp_send.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        name: { type: Type.STRING, description: "Jo naam user ne bola" },
        app: { type: Type.STRING, enum: ["normal", "business"], description: "Kaunsa WhatsApp — sirf jab user ne bataya / poochhne par" },
        which_whatsapp: { type: Type.STRING, description: "Dual/clone WhatsApp ho (tool ne bataya ho) to user ka chuna option: '1', '2' ya naam (jaise 'clone')" },
      },
      required: ["name"],
    },
  },
  {
    name: "whatsapp_send",
    description:
      "WhatsApp mein chuni hui chat kholo, message likho aur Send dabao (sach mein bhej deta hai). whatsapp_search ke BAAD, " +
      "aur user se message confirm karne ke baad hi. Result ok aaye tabhi 'bhej diya' bolo.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        chat: { type: Type.STRING, description: "whatsapp_search ki list mein se chat ka poora naam" },
        message: { type: Type.STRING, description: "Bhejne wala message" },
        app: { type: Type.STRING, enum: ["normal", "business"], description: "Kaunsa WhatsApp (search wala hi)" },
        which_whatsapp: { type: Type.STRING, description: "Search mein jo chuna tha wahi (dual/clone WhatsApp)" },
      },
      required: ["chat", "message"],
    },
  },
  {
    name: "send_whatsapp",
    description: "(Backup) WhatsApp chat message ke saath kholo. Sirf tab jab whatsapp_search 'Phone control OFF' bole.",
    parameters: {
      type: Type.OBJECT,
      properties: { contact_name: contactName, exact_name: exactName, message: { type: Type.STRING, description: "Bhejne wala message" } },
      required: ["contact_name", "message"],
    },
  },
  {
    name: "set_alarm",
    description: "Phone ki clock app mein alarm lagao (agle 24 ghante ke andar ka time).",
    parameters: {
      type: Type.OBJECT,
      properties: {
        hour: { type: Type.INTEGER, description: "Ghanta, 24-hour format mein (0-23)" },
        minute: { type: Type.INTEGER, description: "Minute (0-59)" },
        label: { type: Type.STRING, description: "Alarm ka naam (optional)" },
      },
      required: ["hour", "minute"],
    },
  },
  {
    name: "set_timer",
    description: "Countdown timer lagao.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        seconds: { type: Type.INTEGER, description: "Kitne seconds ka timer" },
        label: { type: Type.STRING, description: "Timer ka naam (optional)" },
      },
      required: ["seconds"],
    },
  },
  {
    name: "set_reminder",
    description:
      "Kisi specific date/time par reminder lagao. Us time phone reminder ko ZOR SE BOLEGA aur notification bhi dega. 'X minute baad batana', 'timer mein 1 minute bache tab batana' jaise kaamon ke liye bhi yahi use karo.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        datetime: { type: Type.STRING, description: "ISO 8601 date-time timezone ke saath, jaise 2026-10-12T09:00:00+05:30" },
        text: { type: Type.STRING, description: "Reminder ke time bola jaane wala vaakya, jaise 'Sahil, timer mein 1 minute bacha hai'" },
      },
      required: ["datetime", "text"],
    },
  },
  {
    name: "open_app",
    description: "Phone mein koi app kholo.",
    parameters: {
      type: Type.OBJECT,
      properties: { app_name: { type: Type.STRING, description: "App ka naam, jaise 'Instagram', 'Camera', 'Paytm'" } },
      required: ["app_name"],
    },
  },
  {
    name: "open_camera",
    description:
      "Camera kholo — selfie, photo ya video. Camera ke har kaam ke liye yahi use karo (open_app nahi). " +
      "app='phone' (DEFAULT): phone ka apna Camera app, selfie ho to front camera; agar phone control ON hai to Max khud guide karke click karta hai, warna user. " +
      "app='snapchat' / 'instagram': us app ka camera. " +
      "app='max': Max ka apna coach camera — Max chehra dekh ke guide karta hai ('thoda left aao', 'smile karo') aur khud click karta hai; SIRF tab jab user kahe ki Max guide kare / khud photo le.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        mode: { type: Type.STRING, enum: ["selfie", "photo", "video"], description: "Kya karna hai" },
        app: {
          type: Type.STRING,
          enum: ["phone", "snapchat", "instagram", "max"],
          description: "Kaunse app mein (default 'phone')",
        },
        camera: {
          type: Type.STRING,
          enum: ["front", "back"],
          description: "Kaunsa camera (optional). Selfie/video mein front default, photo mein back.",
        },
      },
      required: ["mode"],
    },
  },
  {
    name: "unlock_phone",
    description:
      "Phone ka lock kholne wali screen saamne lao (face/fingerprint/PIN). 'Phone kholo', 'unlock karo' ke liye. Max khud lock nahi tod sakta — user dekh ke ya ungli laga ke kholta hai.",
    parameters: { type: Type.OBJECT, properties: {} },
  },
  {
    name: "play_on_youtube",
    description:
      "YouTube par gaana ya video chalao. Agar search_youtube se user ne koi video chuna hai to uska video_id do; specific gaane ka naam ho to sirf query kaafi hai.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        query: { type: Type.STRING, description: "Kya chalana hai (gaane/video ka naam)" },
        video_id: { type: Type.STRING, description: "search_youtube se mila video_id (optional)" },
      },
      required: ["query"],
    },
  },
];

const SERVER_TOOLS: FunctionDeclaration[] = [
  {
    name: "search_youtube",
    description:
      "YouTube par search karke top videos (title, channel, duration, video_id) lao — jab user ne koi specific gaana nahi bataya aur use options dene hain.",
    parameters: {
      type: Type.OBJECT,
      properties: { query: { type: Type.STRING, description: "Search query, jaise 'Arijit Singh romantic songs'" } },
      required: ["query"],
    },
  },
  {
    name: "remember",
    description:
      "User ke baare mein koi lambe samay tak kaam aane wali baat yaad rakho: pasand-napasand, family/relations, important dates, jagah, routine. Choti ya ek baar ki baatein save mat karo.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        fact: { type: Type.STRING, description: "Ek line mein yaad rakhne wali baat, jaise 'Mummy ka birthday 5 March ko hai'" },
        category: {
          type: Type.STRING,
          description: "Category",
          enum: ["family", "preference", "date", "place", "routine", "work", "other"],
        },
      },
      required: ["fact", "category"],
    },
  },
  {
    name: "forget_memory",
    description: "Koi yaad rakhi hui baat delete karo, jab user bole ki ye bhool jao ya ye galat hai.",
    parameters: {
      type: Type.OBJECT,
      properties: { memory_id: { type: Type.STRING, description: "Memory ki id (system prompt ki list mein di gayi hai)" } },
      required: ["memory_id"],
    },
  },
];

export const ALL_TOOLS = [...DEVICE_TOOLS, ...SERVER_TOOLS];
const deviceToolNames = new Set(DEVICE_TOOLS.map((t) => t.name));

export function isDeviceTool(name: string): boolean {
  return deviceToolNames.has(name);
}

// "3:45" ya "12:03" → gaana; "1:02:33" (ghante) ya 12 minute se lamba → compilation.
function isShort(duration: string): boolean {
  const parts = duration.split(":").map(Number);
  if (parts.length !== 2 || parts.some(Number.isNaN)) return false;
  return parts[0]! * 60 + parts[1]! <= 12 * 60;
}

export async function runServerTool(userId: string, name: string, args: Record<string, unknown>): Promise<Record<string, unknown>> {
  switch (name) {
    case "search_youtube": {
      const videos = await searchYouTube(String(args.query ?? ""), 10);
      // Ghante bhar ke jukebox/compilation hatao, taaki options mein asli gaane hon.
      const songs = videos.filter((v) => isShort(v.duration));
      const picked = (songs.length >= 3 ? songs : videos).slice(0, 3);
      if (!picked.length) return { ok: false, error: "YouTube search se kuch nahi mila" };
      return {
        ok: true,
        instructions: "Options ko bilkul isi order mein bolo (option 1 pehle). User 'doosra' bole to option 2.",
        options: picked.map((v, i) => ({ option: i + 1, title: v.title, channel: v.channel, video_id: v.videoId })),
      };
    }
    case "remember": {
      const memory = await memoryStore.add(userId, String(args.fact ?? ""), String(args.category ?? "other"));
      return { ok: true, memory_id: memory.id };
    }
    case "forget_memory": {
      const removed = await memoryStore.remove(userId, String(args.memory_id ?? ""));
      return removed ? { ok: true } : { ok: false, error: "Is id ki koi memory nahi mili" };
    }
    default:
      return { ok: false, error: `Unknown tool: ${name}` };
  }
}
