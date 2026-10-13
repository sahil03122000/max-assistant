# Max — Hinglish voice assistant

"Hello Max" bolo, aur Max baat karega, call lagayega, gaane chalayega, yaad rakhega.

```
android/   Kotlin app — kaan (wake word, sunna), muh (bolna), haath (call, alarm, YouTube...)
server/    Node.js + TypeScript — dimaag (Gemini AI, memory, natural aawaz)
```

## Server
Details: [server/README.md](server/README.md)

- Local: `cd server && npm install && npm run dev` (`.env.example` se `.env` banao)
- Cloud: Render.com par [render.yaml](render.yaml) se deploy hota hai. Memory ke liye Supabase
  ([server/supabase.sql](server/supabase.sql) chalao).

## Android app
1. `bash android/download-wake-model.sh` (wake word model, ek baar)
2. `android/max.properties` banao:
   ```
   max.serverUrl=https://<server-address>
   max.apiToken=<server ki .env wala MAX_API_TOKEN>
   ```
3. Android Studio mein `android/` kholo → Run

`max.properties`, `.env` aur `local.properties` mein secrets hain — ye git mein nahi jaate.
