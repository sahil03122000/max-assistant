// AI tak bhejne se pehle kuch aam lekin ulajh jaane wali baatein saaf kar do.
// "chinna ko whatsapp pe hi bhejo" mein "hi" = greeting (Hi), "sirf" wala "hi" nahi — AI ye galat samajhta tha.

// "high" = Whisper kabhi "hi" ko "high" sun leta hai ("China ko high message bhejo").
const GREETINGS = ["hi", "hii", "high", "hai", "hello", "helo", "hey", "good morning", "good night", "gm", "gn", "happy birthday", "thank you", "thanks", "bye"];

const GREETING_MESSAGE = new RegExp(
  String.raw`^(.+?)\s+(?:ko|ku)\s+(?:(whatsapp|whats app|sms|message|msg)\s+(?:pe|par|per|me|mein)\s+)?(${GREETINGS.join("|")})\s+(?:(?:message|msg|ka message)\s+)?(?:bhejo|bhej do|bhejna|bol do|bolo|kar do|send karo)\s*[.!]*$`,
  "i",
);

export function clarifyMessage(text: string): string {
  const m = text.trim().match(GREETING_MESSAGE);
  if (!m) return text;
  const [, who, app, greeting] = m;
  const via = /sms/i.test(app ?? "") ? "SMS" : "WhatsApp";
  const g = greeting!.toLowerCase();
  const message = g === "gm" ? "Good morning" : g === "gn" ? "Good night" : ["high", "hai", "hii"].includes(g) ? "Hi"
    : greeting!.replace(/\b\w/g, (c) => c.toUpperCase());
  return `${who} ko ${via} pe '${message}' message bhejo`;
}
