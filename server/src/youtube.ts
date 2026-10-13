// YouTube search — koi API key nahi chahiye, YouTube ka search page padhte hain.
// YouTube apna page badle to ye fail ho sakta hai; tab phone purane tarike se search results khol deta hai.

export interface YouTubeVideo {
  videoId: string;
  title: string;
  channel: string;
  duration: string;
}

export async function searchYouTube(query: string, limit = 5): Promise<YouTubeVideo[]> {
  try {
    const res = await fetch(`https://www.youtube.com/results?search_query=${encodeURIComponent(query)}`, {
      headers: { "User-Agent": "Mozilla/5.0", "Accept-Language": "en-IN,en;q=0.9" },
      signal: AbortSignal.timeout(6000),
    });
    if (!res.ok) return [];
    const html = await res.text();
    const json = html.match(/var ytInitialData = (\{.*?\});<\/script>/s)?.[1];
    if (!json) return [];
    const videos: YouTubeVideo[] = [];
    collectVideos(JSON.parse(json), videos, limit);
    return videos;
  } catch (err) {
    console.warn(`  YouTube search fail: ${String(err)}`);
    return [];
  }
}

// Page ka data bahut gehra nested JSON hai — har "videoRenderer" dhundh lo, jahan bhi ho.
function collectVideos(node: unknown, out: YouTubeVideo[], limit: number): void {
  if (out.length >= limit || node === null || typeof node !== "object") return;
  if (Array.isArray(node)) {
    for (const item of node) collectVideos(item, out, limit);
    return;
  }
  const obj = node as Record<string, any>;
  const v = obj.videoRenderer;
  if (v?.videoId) {
    out.push({
      videoId: v.videoId,
      title: v.title?.runs?.map((r: { text: string }) => r.text).join("") ?? "",
      channel: v.ownerText?.runs?.[0]?.text ?? "",
      duration: v.lengthText?.simpleText ?? "",
    });
    return;
  }
  for (const value of Object.values(obj)) collectVideos(value, out, limit);
}
