# Max Server (dimaag)

## Setup
1. `.env.example` ko copy karke `.env` banao aur `GEMINI_API_KEY` + `MAX_API_TOKEN` bharo.
2. `npm install`

## Commands
| Command | Kaam |
|---|---|
| `npm run chat` | Terminal mein Max se baat karo (phone ke bina test) |
| `npm run dev` | API server chalao, code badalne par auto-restart |
| `npm run typecheck` | TypeScript errors check karo |

## Files
| File | Kya hai |
|---|---|
| `src/brain.ts` | AI loop: sochna → tools chalana → jawab |
| `src/prompt.ts` | Max ki personality aur rules |
| `src/tools.ts` | Max kya-kya kar sakta hai (call, SMS, alarm, memory...) |
| `src/memory.ts` | Long-term memory (abhi `data/memory.json`) |
| `src/sessions.ts` | Chalti baat-cheet ki short-term history |
| `src/index.ts` | HTTP API jo Android app use karega |
| `src/cli.ts` | Terminal chat tool |

## API (Android app ke liye)
Har request mein header: `Authorization: Bearer <MAX_API_TOKEN>`

**`POST /v1/chat`** `{ "sessionId": "abc", "message": "papa ko call karo" }`

Jawab do tarah ka hoga:
```json
{ "status": "done", "reply": "Kaise ho?" }
```
```json
{ "status": "needs_action", "reply": "", "actions": [
  { "id": "a1", "name": "call_contact", "args": { "contact_name": "Papa" } }
] }
```
`needs_action` aaye to phone kaam kare aur result bheje:

**`POST /v1/chat/actions`** `{ "sessionId": "abc", "results": [ { "id": "a1", "ok": true } ] }`
(fail hone par `{ "id": "a1", "ok": false, "error": "Contact nahi mila" }`)

Iska jawab bhi upar jaisa hi hoga (multi-step kaam mein phir se `needs_action` aa sakta hai).
