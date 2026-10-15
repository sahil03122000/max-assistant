// EXPERIMENT: Max user ki taraf se call par khud baat karta hai (phone speaker par bolta/sunta hai).
//
//   phone: call uthi → POST /v1/call/turn { callee, goal, transcript: [] }        → { say: "Namaste Papa, main Max..." }
//          saamne wale ne kuch bola → transcript mein jod ke phir se              → { say: "...", done }
//
// Har line chhoti — call par lambi baat bekaar lagti hai. Kaam ho gaya to AI [END] likhta hai → phone call kaat deta hai.
import type { Content } from "@google/genai";
import { stripLeakedReasoning, think } from "./brain.js";
import { maskPrivate } from "./privacy.js";

export interface CallLine {
  who: "max" | "them";
  text: string;
}

export interface CallTurnInput {
  callee: string;
  owner: string;
  goal: string;
  transcript: CallLine[];
}

const MAX_LINES = 6;

function systemPrompt({ callee, owner, goal }: CallTurnInput): string {
  return `Tum Max ho — ${owner} ke AI assistant (male, "main bol raha hoon"). Abhi tum ${owner} ki taraf se ${callee} ko phone par call kar rahe ho (phone speaker par bol rahe ho).
Kaam jo ${owner} ne diya: ${goal}

Niyam:
- Sirf Roman Hinglish (Devanagari kabhi nahi). Har baar 1-2 chhote vaakya — jaise ghar ka samajhdaar insaan phone par bolta hai. "Aap" se, izzat se.
- Pehli line: namaste, batao "main Max bol raha hoon, ${owner} ka AI assistant, unki taraf se call kiya hai", phir seedha kaam ki baat.
- ${callee} ki baat call ki aawaz se galat likhi ho sakti hai — matlab samjho. Bilkul samajh na aaye to ek baar dobara poochho.
- "(chup)" = kuch saaf nahi suna — ek baar "Hello, aawaz aa rahi hai?" jaisa poochho.
- Kaam ho gaya (sandesh pahunch gaya / sawaal ka jawab mil gaya) ya ${callee} baat khatam karna chahein: chhota sa dhanyavaad + alvida bolo aur line ke aakhir mein [END] likho.
- Koi wada mat karo jo ${owner} ne nahi kaha. Paise, OTP, password, bank ki jaankari na maango na do — aisi baat par bolo "${owner} khud aapse baat kar lenge".
- Sirf wahi line likho jo bolni hai — koi soch, label ya quote nahi.`;
}

export async function nextCallLine(input: CallTurnInput): Promise<{ say: string; done: boolean }> {
  const history: Content[] = [{ role: "user", parts: [{ text: `[Call uth gayi, saamne ${input.callee} hain. Pehli line bolo.]` }] }];
  for (const line of input.transcript) {
    const text = line.who === "them" ? maskPrivate(line.text).text : line.text;
    history.push({ role: line.who === "max" ? "model" : "user", parts: [{ text }] });
  }
  const maxLines = input.transcript.filter((l) => l.who === "max").length;
  // Bahut lambi ho gayi — ab alvida.
  if (maxLines >= MAX_LINES) {
    history.push({ role: "user", parts: [{ text: "[Call ab khatam karni hai — chhota sa alvida bolo, [END] ke saath.]" }] });
  }
  const content = await think(history, systemPrompt(input), []);
  const raw = (content.parts ?? [])
    .filter((p) => p.text && !p.thought)
    .map((p) => p.text)
    .join(" ");
  const done = /\[END\]/i.test(raw) || maxLines >= MAX_LINES;
  const say = stripLeakedReasoning(raw.replace(/\[END\]/gi, "").replace(/\s+/g, " ").trim()) || "Theek hai, dhanyavaad.";
  return { say, done };
}
