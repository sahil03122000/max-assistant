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

export async function getWeather(city: string, day: "today" | "tomorrow"): Promise<Record<string, unknown>> {
  const geo = (await fetch(
    `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1&language=en`,
    { signal: AbortSignal.timeout(8000) },
  ).then((r) => r.json())) as { results?: { name: string; admin1?: string; country?: string; latitude: number; longitude: number }[] };
  const place = geo.results?.[0];
  if (!place) return { ok: false, error: `'${city}' naam ki jagah nahi mili — user se shehar ka naam dobara poocho` };

  const url =
    `https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}` +
    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2";
  const w = (await fetch(url, { signal: AbortSignal.timeout(8000) }).then((r) => r.json())) as {
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
    instructions: "1-2 chhote vaakya mein bolo (taapmaan, aasmaan, baarish ka chance). Baarish 50%+ ho to chhata le jaane ki salah.",
  };
}

export async function getNews(topic?: string): Promise<Record<string, unknown>> {
  const url = topic
    ? `https://news.google.com/rss/search?q=${encodeURIComponent(topic)}&hl=en-IN&gl=IN&ceid=IN:en`
    : "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en";
  const xml = await fetch(url, { signal: AbortSignal.timeout(8000) }).then((r) => r.text());
  const headlines = [...xml.matchAll(/<item>[\s\S]*?<title>([\s\S]*?)<\/title>/g)]
    .map((m) => decode(m[1] ?? "").replace(/\s+-\s+[^-]+$/, "").trim()) // " - Times of India" hatao
    .filter(Boolean)
    .slice(0, 5);
  if (!headlines.length) return { ok: false, error: "Abhi khabrein nahi mili" };
  return {
    ok: true,
    topic: topic ?? "India top news",
    headlines,
    instructions: "Top 3 khabrein Hinglish mein, har ek ek chhoti line mein, apne shabdon mein. Phir poocho kisi par aur jaanna hai?",
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
