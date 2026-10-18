// Phone app khud update ho (laptop se install ki zaroorat nahi) + phone ke debug logs bhejna.
//
// Update: nayi APK GitHub ke PRIVATE repo ki "Release" mein (tag v<versionCode>, android/release.sh). Repo private hai,
// isliye phone seedha nahi le sakta — server apne GITHUB_TOKEN (sirf padhne wala) se GitHub ka thodi der chalne wala
// download link bana ke phone ko deta hai. APK (150+ MB) server se hoke nahi jaati, seedha GitHub se.
//
// Logs: Supabase Storage ke private bucket "max-logs" mein (Supabase na ho to server ki DATA_DIR/logs mein).
import { createClient } from "@supabase/supabase-js";
import fs from "node:fs/promises";
import path from "node:path";
import { config } from "./config.js";
import { HttpError } from "./brain.js";

export interface AppRelease {
  versionCode: number;
  name: string;
  sizeBytes: number;
  assetId: number;
}

function github(pathname: string, accept = "application/vnd.github+json"): Promise<Response> {
  if (!config.GITHUB_TOKEN) throw new HttpError(501, "Update abhi set nahi hai (server par GITHUB_TOKEN nahi)");
  return fetch(`https://api.github.com/repos/${config.GITHUB_REPO}${pathname}`, {
    headers: { authorization: `Bearer ${config.GITHUB_TOKEN}`, accept, "x-github-api-version": "2022-11-28" },
    redirect: "manual",
    signal: AbortSignal.timeout(15_000),
  });
}

export async function latestRelease(): Promise<AppRelease | null> {
  const res = await github("/releases/latest");
  if (res.status === 404) return null;
  if (!res.ok) throw new HttpError(502, `GitHub se update ki jaankari nahi mili (${res.status})`);
  const body = (await res.json()) as { tag_name: string; name?: string; assets: { id: number; name: string; size: number }[] };
  const apk = body.assets.find((a) => a.name.endsWith(".apk"));
  const versionCode = Number(body.tag_name.replace(/^v/, ""));
  if (!apk || !Number.isFinite(versionCode)) return null;
  return { versionCode, name: body.name || body.tag_name, sizeBytes: apk.size, assetId: apk.id };
}

/** GitHub ka kuch minute chalne wala seedha download link (koi token nahi chahiye). */
export async function apkDownloadUrl(): Promise<string> {
  const release = await latestRelease();
  if (!release) throw new HttpError(404, "Koi update nahi mila");
  const res = await github(`/releases/assets/${release.assetId}`, "application/octet-stream");
  const location = res.headers.get("location");
  if (!location) throw new HttpError(502, `Download link nahi bana (${res.status})`);
  return location;
}

const LOG_BUCKET = "max-logs";

export async function saveLog(text: string): Promise<string> {
  const name = `${new Date().toISOString().replace(/[:.]/g, "-")}.txt`;
  if (config.SUPABASE_URL && config.SUPABASE_SERVICE_KEY) {
    const db = createClient(config.SUPABASE_URL, config.SUPABASE_SERVICE_KEY, { auth: { persistSession: false } });
    // Pehli baar bucket bana do (private — sirf service key se padh sakte hain).
    await db.storage.createBucket(LOG_BUCKET, { public: false }).catch(() => undefined);
    const { error } = await db.storage.from(LOG_BUCKET).upload(name, text, { contentType: "text/plain; charset=utf-8" });
    if (error) throw new HttpError(502, `Log save nahi hua: ${error.message}`);
  } else {
    const dir = path.join(config.DATA_DIR, "logs");
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(path.join(dir, name), text, "utf8");
  }
  return name;
}
