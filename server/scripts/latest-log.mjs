// Phone se "📤 Bhejo" kiye gaye logs padho (Supabase Storage → max-logs).
//   node scripts/latest-log.mjs         → sabse naya log
//   node scripts/latest-log.mjs 3       → aakhri 3 ki list
import "dotenv/config";
import { createClient } from "@supabase/supabase-js";
const db = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_SERVICE_KEY, { auth: { persistSession: false } });
const { data: files, error } = await db.storage.from("max-logs").list("", { sortBy: { column: "name", order: "desc" }, limit: 20 });
if (error) throw error;
const n = Number(process.argv[2] ?? 0);
if (n) { files.slice(0, n).forEach((f) => console.log(f.name)); process.exit(0); }
if (!files.length) { console.log("Koi log nahi"); process.exit(0); }
const { data } = await db.storage.from("max-logs").download(files[0].name);
console.log(`--- ${files[0].name} ---\n${await data.text()}`);
