// Max ki long-term memory: user ke baare mein yaad rakhi gayi baatein.
// Do jagah save ho sakti hai:
//  - Supabase (cloud database) — jab .env mein SUPABASE_URL + SUPABASE_SERVICE_KEY hon. Cloud server
//    ke liye zaroori, kyunki wahan restart par files mit jaati hain.
//  - Local JSON file (data/memory.json) — laptop par bina setup ke testing ke liye.
import { promises as fs } from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { createClient } from "@supabase/supabase-js";
import { config } from "./config.js";

export interface Memory {
  id: string;
  text: string;
  category: string;
  createdAt: string;
}

interface MemoryStore {
  list(userId: string): Promise<Memory[]>;
  add(userId: string, text: string, category: string): Promise<Memory>;
  remove(userId: string, id: string): Promise<boolean>;
}

const newId = () => randomUUID().slice(0, 8);

// ---------- Supabase ----------
// Table banane ki SQL: server/supabase.sql

function supabaseStore(url: string, key: string): MemoryStore {
  const db = createClient(url, key, { auth: { persistSession: false } });
  return {
    async list(userId) {
      const { data, error } = await db
        .from("memories")
        .select("id, text, category, created_at")
        .eq("user_id", userId)
        .order("created_at");
      if (error) throw new Error(`Memory load fail: ${error.message}`);
      return data.map((r) => ({ id: r.id, text: r.text, category: r.category, createdAt: r.created_at }));
    },

    async add(userId, text, category) {
      const memory: Memory = { id: newId(), text, category, createdAt: new Date().toISOString() };
      const { error } = await db
        .from("memories")
        .insert({ id: memory.id, user_id: userId, text, category, created_at: memory.createdAt });
      if (error) throw new Error(`Memory save fail: ${error.message}`);
      return memory;
    },

    async remove(userId, id) {
      const { data, error } = await db.from("memories").delete().eq("user_id", userId).eq("id", id).select("id");
      if (error) throw new Error(`Memory delete fail: ${error.message}`);
      return data.length > 0;
    },
  };
}

// ---------- Local JSON file ----------

function fileStore(): MemoryStore {
  const file = path.join(config.DATA_DIR, "memory.json");
  let cache: Record<string, Memory[]> | null = null;
  let writeQueue: Promise<void> = Promise.resolve();

  async function load(): Promise<Record<string, Memory[]>> {
    if (cache) return cache;
    try {
      cache = JSON.parse(await fs.readFile(file, "utf8")) as Record<string, Memory[]>;
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code !== "ENOENT") throw err;
      cache = {};
    }
    return cache;
  }

  // Writes ek ke baad ek hote hain taaki do saath aaye requests file ko kharab na karein.
  function persist(): Promise<void> {
    const data = JSON.stringify(cache, null, 2);
    writeQueue = writeQueue
      .catch(() => {})
      .then(async () => {
        await fs.mkdir(config.DATA_DIR, { recursive: true });
        const tmp = `${file}.tmp`;
        await fs.writeFile(tmp, data, "utf8");
        await fs.rename(tmp, file);
      });
    return writeQueue;
  }

  return {
    async list(userId) {
      return [...((await load())[userId] ?? [])];
    },

    async add(userId, text, category) {
      const all = await load();
      const memory: Memory = { id: newId(), text, category, createdAt: new Date().toISOString() };
      (all[userId] ??= []).push(memory);
      await persist();
      return memory;
    },

    async remove(userId, id) {
      const all = await load();
      const list = all[userId] ?? [];
      const index = list.findIndex((m) => m.id === id);
      if (index === -1) return false;
      list.splice(index, 1);
      await persist();
      return true;
    },
  };
}

export const memoryStore: MemoryStore =
  config.SUPABASE_URL && config.SUPABASE_SERVICE_KEY
    ? supabaseStore(config.SUPABASE_URL, config.SUPABASE_SERVICE_KEY)
    : fileStore();

export const memoryBackend = config.SUPABASE_URL && config.SUPABASE_SERVICE_KEY ? "Supabase" : "local file";
