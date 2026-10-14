// Ek "session" = ek baat-cheet. Isse Max ko pichhli 2-4 baatein yaad rehti hain
// ("usko bhi message kar do" — "usko" kaun hai, ye isi history se pata chalta hai).
// Long-term memory alag hai (memory.ts). Sessions abhi RAM mein hain; server restart par reset.
import type { Content, Part } from "@google/genai";

export interface PendingAction {
  id: string;
  name: string;
  args: Record<string, unknown>;
}

// Jab AI ne kuch device tools maange hain aur phone ke result ka intezaar hai.
// Har slot AI ke ek function call ke barabar hai, same order mein.
export type PendingSlot = { action: PendingAction; callId?: string } | { response: Part };

/**
 * Phone ka bheja hua profile — sirf baat-cheet ke liye zaroori hissa. DOB aur last name phone
 * kabhi nahi bhejta; birthday ho to sirf `birthdayToday: true`.
 */
export interface UserProfile {
  callingName?: string;
  gender?: "male" | "female" | "unspecified";
  addressStyle?: "aap" | "tum";
  city?: string;
  profession?: string;
  interests?: string;
  birthdayToday?: boolean;
}

export interface Session {
  key: string;
  userId: string;
  history: Content[];
  pending: PendingSlot[] | null;
  busy: boolean;
  busySince: number;
  updatedAt: number;
  profile?: UserProfile;
}

const SESSION_TTL_MS = 30 * 60 * 1000;
const MAX_HISTORY = 40;
const sessions = new Map<string, Session>();

export function getSession(userId: string, sessionId: string): Session {
  const key = `${userId}:${sessionId}`;
  let session = sessions.get(key);
  if (!session) {
    session = { key, userId, history: [], pending: null, busy: false, busySince: 0, updatedAt: Date.now() };
    sessions.set(key, session);
  }
  session.updatedAt = Date.now();
  return session;
}

// Purani history kaat do, lekin hamesha user ke text message se shuru karo —
// beech se kaatne par AI ko adhoora function call/response mil jaata aur error aata.
export function trimHistory(history: Content[]): void {
  if (history.length <= MAX_HISTORY) return;
  for (let i = history.length - MAX_HISTORY; i < history.length; i++) {
    const c = history[i];
    if (c?.role === "user" && c.parts?.some((p) => p.text) && !c.parts.some((p) => p.functionResponse)) {
      history.splice(0, i);
      return;
    }
  }
}

setInterval(() => {
  const cutoff = Date.now() - SESSION_TTL_MS;
  for (const [key, s] of sessions) if (s.updatedAt < cutoff && !s.busy) sessions.delete(key);
}, 60_000).unref();
