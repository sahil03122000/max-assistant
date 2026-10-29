// Aaj ki jaankari — mausam aur khabrein. Dono free, koi API key nahi:
//  - Mausam: Open-Meteo (shehar ka naam → jagah → forecast)
//  - Khabrein: Google News RSS (India)

const WEATHER_WORDS: Record<number, string> = {
  0: "saaf aasmaan, dhoop",
  1: "zyadatar saaf",
  2: "halke baadal",
  3: "baadal chhaaye hue",
  45: "kohra",
  48: "ghana kohra",
  51: "halki boondabaandi",
  53: "boondabaandi",
  55: "tez boondabaandi",
  61: "halki baarish",
  63: "baarish",
  65: "tez baarish",
  71: "halki barf",
  73: "barf",
  75: "tez barf",
  80: "halki bauchhar",
  81: "bauchhar",
  82: "tez bauchhar",
  95: "aandhi-toofan",
  96: "toofan aur ole",
  99: "tez toofan aur ole",
};

const WEATHER_TIP = "1-2 chhote vaakya mein bolo (taapmaan, aasmaan, baarish ka chance). Baarish 50%+ ho to chhata le jaane ki salah.";

/**
 * Dono ek saath poochho, jo pehle sahi jawab de wahi — Open-Meteo Render par atak jaata tha (8+8 sec) phir wttr.in,
 * aur Live mode mein user 30 sec chup baitha rehta tha.
 */
export async function getWeather(city: string, day: "today" | "tomorrow"): Promise<Record<string, unknown>> {
  const results = [openMeteo(city, day), wttr(city, day)].map((p) =>
    p.then((r) => (r.ok ? r : Promise.reject(new Error(String(r.error))))),
  );
  try {
    return await Promise.any(results);
  } catch (err) {
    console.warn(`  🌦️ Mausam dono jagah fail: ${err instanceof AggregateError ? err.errors.map(String).join(" | ") : err}`);
    return { ok: false, error: "Mausam ki service abhi jawab nahi de rahi" };
  }
}

export async function wttr(city: string, day: "today" | "tomorrow"): Promise<Record<string, unknown>> {
  const res = await fetch(`https://wttr.in/${encodeURIComponent(city)}?format=j1`, {
    headers: { "User-Agent": "curl/8" },
    signal: AbortSignal.timeout(6_000),
  });
  if (!res.ok) throw new Error(`wttr.in ${res.status}`);
  const w = (await res.json()) as {
    current_condition: { temp_C: string; FeelsLikeC: string; humidity: string; windspeedKmph: string; weatherDesc: { value: string }[] }[];
    weather: { maxtempC: string; mintempC: string; hourly: { chanceofrain: string; weatherDesc: { value: string }[] }[] }[];
    nearest_area?: { areaName: { value: string }[]; region: { value: string }[] }[];
  };
  const d = w.weather[day === "tomorrow" ? 1 : 0];
  const now = w.current_condition[0];
  if (!d || !now) throw new Error("wttr.in khaali jawab");
  const area = w.nearest_area?.[0];
  return {
    ok: true,
    place: [area?.areaName[0]?.value ?? city, area?.region[0]?.value].filter(Boolean).join(", "),
    day,
    ...(day === "today"
      ? {
          now: {
            temperature_c: Number(now.temp_C),
            feels_like_c: Number(now.FeelsLikeC),
            humidity_percent: Number(now.humidity),
            sky_english: now.weatherDesc[0]?.value,
            wind_kmh: Number(now.windspeedKmph),
          },
        }
      : {}),
    max_c: Number(d.maxtempC),
    min_c: Number(d.mintempC),
    sky_day_english: d.hourly[4]?.weatherDesc[0]?.value,
    rain_chance_percent: Math.max(...d.hourly.map((h) => Number(h.chanceofrain) || 0)),
    instructions: WEATHER_TIP + " Aasmaan English mein hai — Hinglish mein bolo.",
  };
}

async function openMeteo(city: string, day: "today" | "tomorrow"): Promise<Record<string, unknown>> {
  const geo = (await fetch(
    `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1&language=en`,
    { signal: AbortSignal.timeout(4000) },
  ).then((r) => r.json())) as { results?: { name: string; admin1?: string; country?: string; latitude: number; longitude: number }[] };
  const place = geo.results?.[0];
  if (!place) {
    if ((geo as { error?: unknown }).error) throw new Error("Open-Meteo ne mana kiya");
    return { ok: false, error: `'${city}' naam ki jagah nahi mili — user se shehar ka naam dobara poocho` };
  }

  const url =
    `https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}` +
    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2";
  const w = (await fetch(url, { signal: AbortSignal.timeout(4000) }).then((r) => r.json())) as {
    current: { temperature_2m: number; apparent_temperature: number; relative_humidity_2m: number; weather_code: number; wind_speed_10m: number };
    daily: { weather_code: number[]; temperature_2m_max: number[]; temperature_2m_min: number[]; precipitation_probability_max: number[] };
  };
  const i = day === "tomorrow" ? 1 : 0;
  return {
    ok: true,
    place: [place.name, place.admin1].filter(Boolean).join(", "),
    day,
    ...(day === "today"
      ? {
          now: {
            temperature_c: Math.round(w.current.temperature_2m),
            feels_like_c: Math.round(w.current.apparent_temperature),
            humidity_percent: w.current.relative_humidity_2m,
            sky: WEATHER_WORDS[w.current.weather_code] ?? "mila-jula",
            wind_kmh: Math.round(w.current.wind_speed_10m),
          },
        }
      : {}),
    max_c: Math.round(w.daily.temperature_2m_max[i] ?? NaN),
    min_c: Math.round(w.daily.temperature_2m_min[i] ?? NaN),
    sky_day: WEATHER_WORDS[w.daily.weather_code[i] ?? -1] ?? "mila-jula",
    rain_chance_percent: w.daily.precipitation_probability_max[i],
    instructions: WEATHER_TIP,
  };
}

export async function getNews(topic?: string, count = 3): Promise<Record<string, unknown>> {
  const want = Math.min(Math.max(Math.round(count), 1), 10);
  const url = topic
    ? `https://news.google.com/rss/search?q=${encodeURIComponent(topic)}&hl=en-IN&gl=IN&ceid=IN:en`
    : "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en";
  const xml = await fetch(url, { signal: AbortSignal.timeout(8000) }).then((r) => r.text());
  const headlines = [...xml.matchAll(/<item>[\s\S]*?<title>([\s\S]*?)<\/title>/g)]
    .map((m) => decode(m[1] ?? "").replace(/\s+-\s+[^-]+$/, "").trim()) // " - Times of India" hatao
    .filter(Boolean)
    .slice(0, Math.max(want, 5));
  if (!headlines.length) return { ok: false, error: "Abhi khabrein nahi mili" };
  return {
    ok: true,
    topic: topic ?? "India top news",
    headlines,
    instructions: `Pehli ${want} khabrein Hinglish mein, har ek ek chhoti line mein, apne shabdon mein (gin ke poori ${want}).`,
  };
}

function decode(s: string): string {
  return s
    .replace(/<!\[CDATA\[|\]\]>/g, "")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">");
}

/**
 * Internet par dhundho (DuckDuckGo, free, koi key nahi) — muhurat, tyohar, bhav, score, taaza jaankari.
 * Google Search grounding free API par nahi milta tha ("quota"), aur Max andaze se time bana deta tha.
 */
export async function webSearch(query: string): Promise<Record<string, unknown>> {
  // Cloud server (Render) se DuckDuckGo kabhi rok deta hai — Bing ka RSS backup, dono free, koi key nahi.
  for (const source of [duckDuckGo, bingRss]) {
    try {
      const results = await source(query);
      if (results.length) {
        return {
          ok: true,
          query,
          results,
          instructions:
            "In nateejon se seedha, pakka jawab do (tarikh/time/number ke saath), 1-2 vaakya. Nateeje aapas mein alag hon to " +
            "sabse zyada wala batao. Andaza mat lagao; nateejon mein na ho to saaf bolo nahi mila.",
        };
      }
      console.warn(`  🔎 ${source.name}: koi nateeja nahi`);
    } catch (err) {
      console.warn(`  🔎 ${source.name} fail: ${err instanceof Error ? err.message : err}`);
    }
  }
  return { ok: false, error: "Internet par kuch nahi mila" };
}

async function bingRss(query: string): Promise<string[]> {
  const res = await fetch(`https://www.bing.com/search?format=rss&cc=IN&q=${encodeURIComponent(query)}`, {
    headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36" },
    signal: AbortSignal.timeout(8000),
  });
  const xml = await res.text();
  return [...xml.matchAll(/<item>[\s\S]*?<title>([\s\S]*?)<\/title>[\s\S]*?<description>([\s\S]*?)<\/description>/g)]
    .slice(0, 5)
    .map((m) => clean(`${m[1]} — ${m[2]}`));
}

async function duckDuckGo(query: string): Promise<string[]> {
  const res = await fetch("https://html.duckduckgo.com/html/", {
    method: "POST",
    headers: {
      "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36",
      "content-type": "application/x-www-form-urlencoded",
    },
    body: "q=" + encodeURIComponent(query) + "&kl=in-en",
    signal: AbortSignal.timeout(8000),
  });
  const html = await res.text();
  return [...html.matchAll(/class="result__a"[^>]*>([\s\S]*?)<\/a>[\s\S]*?class="result__snippet"[^>]*>([\s\S]*?)<\/a>/g)]
    .slice(0, 5)
    .map((m) => clean(`${m[1]} — ${m[2]}`));
}

function clean(s: string): string {
  return decode(s.replace(/<[^>]+>/g, "")).replace(/&#x27;/g, "'").replace(/\s+/g, " ").trim();
}
