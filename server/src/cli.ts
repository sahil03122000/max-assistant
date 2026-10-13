// Terminal mein Max se baat karo — Android app bane bina dimaag test karne ke liye.
// Jab Max phone ka koi kaam maangega (call, alarm...), ye poochega ki result kya bhejna hai:
// Enter dabao = kaam ho gaya, ya error likho (jaise "contact nahi mila").
//
//   npm run chat
import { randomUUID } from "node:crypto";
import { createInterface } from "node:readline/promises";
import { handleActionResults, handleUserMessage, HttpError, type ActionResult, type TurnResult } from "./brain.js";
import { memoryStore } from "./memory.js";
import { getSession } from "./sessions.js";

const rl = createInterface({ input: process.stdin, output: process.stdout });
const session = getSession("default", `cli-${randomUUID()}`);

// Ctrl+C / Ctrl+D par readline band ho jaata hai — tab crash ki jagah aaram se bahar niklo.
let closed = false;
rl.on("close", () => {
  closed = true;
});
async function ask(prompt: string): Promise<string | null> {
  if (closed) return null;
  try {
    return (await rl.question(prompt)).trim();
  } catch {
    return null;
  }
}

console.log("🤖 Max se baat karo. 'memories' = yaad rakhi baatein, 'exit' = band karo.\n");

while (true) {
  const text = await ask("Aap: ");
  if (text === null || text === "exit") break;
  if (!text) continue;
  if (text === "memories") {
    for (const m of await memoryStore.list("default")) console.log(`  [${m.id}] (${m.category}) ${m.text}`);
    continue;
  }

  try {
    let turn: TurnResult = await handleUserMessage(session, text);
    while (turn.status === "needs_action") {
      if (turn.reply) console.log(`Max: ${turn.reply}`);
      const results: ActionResult[] = [];
      for (const action of turn.actions) {
        console.log(`  📱 [phone] ${action.name} ${JSON.stringify(action.args)}`);
        const error = (await ask("     result? (Enter = success, ya error likho): ")) ?? "";
        results.push(error ? { id: action.id, ok: false, error } : { id: action.id, ok: true });
      }
      turn = await handleActionResults(session, results);
    }
    console.log(`Max: ${turn.reply}\n`);
  } catch (err) {
    console.log(`⚠️  ${err instanceof HttpError ? err.message : String(err)}\n`);
  }
}

rl.close();
