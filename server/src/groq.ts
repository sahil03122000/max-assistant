// Groq — free aur bahut tez (0.2-1 sec) AI. Max ka pehla dimaag yahi; limit/busy ho to Gemini backup.
//
// Max ki baat-cheet Gemini ke format (Content/parts) mein save hoti hai. Groq OpenAI jaisa format samajhta hai,
// isliye yahan dono taraf badalte hain: history → Groq messages, Groq ka jawab → Gemini Content.
import type { Content, FunctionDeclaration, Part, Schema } from "@google/genai";
import { config } from "./config.js";

export class GroqError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

interface GroqToolCall {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
}

type GroqMessage =
  | { role: "system" | "user"; content: string }
  | { role: "assistant"; content: string | null; tool_calls?: GroqToolCall[] }
  | { role: "tool"; tool_call_id: string; content: string };

export const groqEnabled = (): boolean => Boolean(config.GROQ_API_KEY);

/** Ek Groq model se agla kadam — jawab Gemini Content mein (taaki baaki Max ka code same rahe). */
export async function groqThink(
  model: string,
  history: Content[],
  systemInstruction: string,
  tools: FunctionDeclaration[],
): Promise<Content> {
  const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: { authorization: `Bearer ${config.GROQ_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({
      model,
      messages: toMessages(history, systemInstruction),
      tools: tools.map((t) => ({
        type: "function",
        function: { name: t.name, description: t.description, parameters: toJsonSchema(t.parameters) },
      })),
      temperature: 0.6,
      max_completion_tokens: 600,
    }),
    signal: AbortSignal.timeout(15_000),
  });
  const body = (await res.json().catch(() => ({}))) as {
    error?: { message?: string };
    choices?: { message?: { content?: string | null; tool_calls?: GroqToolCall[] } }[];
  };
  if (!res.ok) throw new GroqError(res.status, body.error?.message ?? `Groq error ${res.status}`);

  const message = body.choices?.[0]?.message;
  const parts: Part[] = [];
  // Kuch models "<think>...</think>" mein apni soch likhte hain — wo bolne ke liye nahi.
  const text = (message?.content ?? "").replace(/<think>[\s\S]*?<\/think>/g, "").trim();
  if (text) parts.push({ text });
  for (const call of message?.tool_calls ?? []) {
    let args: Record<string, unknown> = {};
    try {
      args = JSON.parse(call.function.arguments || "{}");
    } catch {
      // galat JSON — khaali args
    }
    // Gemini 3 apne purane function calls par "thought signature" maangta hai; dusre model ke calls ke liye
    // Google ka bataya hua placeholder — taaki baad mein Gemini backup par jaane par error na aaye.
    parts.push({ functionCall: { id: call.id, name: call.function.name, args }, thoughtSignature: "skip_thought_signature_validator" });
  }
  if (!parts.length) parts.push({ text: "Theek hai." }); // kaam ke baad khaali jawab = bas "Theek hai"
  return { role: "model", parts };
}

/** Gemini history → Groq messages. Function calls ki id na ho to jagah ke hisaab se bana do (dono taraf same). */
function toMessages(history: Content[], systemInstruction: string): GroqMessage[] {
  const messages: GroqMessage[] = [{ role: "system", content: systemInstruction }];
  let openCalls: string[] = []; // pichhle model turn ke call ids, unke responses ke liye
  history.forEach((content, turn) => {
    const parts = content.parts ?? [];
    if (content.role === "model") {
      const text = parts.filter((p) => p.text && !p.thought).map((p) => p.text).join("").trim();
      const calls = parts.flatMap((p, i) =>
        p.functionCall
          ? [{
              id: p.functionCall.id ?? `call_${turn}_${i}`,
              type: "function" as const,
              function: { name: p.functionCall.name ?? "", arguments: JSON.stringify(p.functionCall.args ?? {}) },
            }]
          : [],
      );
      openCalls = calls.map((c) => c.id);
      messages.push({ role: "assistant", content: text || null, ...(calls.length ? { tool_calls: calls } : {}) });
      return;
    }
    let responseIndex = 0;
    for (const p of parts) {
      if (p.functionResponse) {
        const id = p.functionResponse.id ?? openCalls[responseIndex] ?? `call_${turn}_${responseIndex}`;
        responseIndex++;
        messages.push({ role: "tool", tool_call_id: id, content: JSON.stringify(p.functionResponse.response ?? {}) });
      } else if (p.text) {
        messages.push({ role: "user", content: p.text });
      }
    }
    openCalls = [];
  });
  return messages;
}

/** Gemini ka schema (Type.OBJECT jaise bade akshar) → JSON schema (object, string...). */
function toJsonSchema(schema: Schema | undefined): Record<string, unknown> {
  if (!schema) return { type: "object", properties: {} };
  const out: Record<string, unknown> = {};
  if (schema.type) out.type = String(schema.type).toLowerCase();
  if (schema.description) out.description = schema.description;
  if (schema.enum) out.enum = schema.enum;
  if (schema.required) out.required = schema.required;
  if (schema.items) out.items = toJsonSchema(schema.items);
  if (schema.properties) {
    out.properties = Object.fromEntries(Object.entries(schema.properties).map(([k, v]) => [k, toJsonSchema(v)]));
  }
  if (out.type === "object" && !out.properties) out.properties = {};
  return out;
}

/** Groq Whisper: aawaz → text (Max ke bolte waqt user ne jo bola). */
export async function groqTranscribe(wav: Buffer): Promise<string> {
  const form = new FormData();
  form.append("file", new Blob([new Uint8Array(wav)], { type: "audio/wav" }), "speech.wav");
  form.append("model", "whisper-large-v3-turbo");
  form.append("temperature", "0");
  const res = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
    method: "POST",
    headers: { authorization: `Bearer ${config.GROQ_API_KEY}` },
    body: form,
    signal: AbortSignal.timeout(12_000),
  });
  const body = (await res.json().catch(() => ({}))) as { text?: string; error?: { message?: string } };
  if (!res.ok) throw new GroqError(res.status, body.error?.message ?? `Groq whisper ${res.status}`);
  return (body.text ?? "").trim();
}
