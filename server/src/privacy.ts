// Dusri safety layer: phone (PrivacyFilter.kt) pehle hi private cheezein chhupa deta hai, lekin koi
// aur client (jaise terminal chat ya future laptop app) bhool jaaye to bhi Gemini tak asli value na pahunche.
// Rules android/app/src/main/java/com/max/assistant/PrivacyFilter.kt jaise hi rakhna.

export const MASK = "[private]";

const SECRET_WORDS = "otp|o\\.t\\.p|pin|mpin|upi pin|atm pin|cvv|password|passcode|pass code|passwd|ओटीपी|पिन|पासवर्ड";
const ACCOUNT_WORDS = "account|a/c|acct|khata|खाता|अकाउंट";
const LONG_NUMBER = "long number";

const RULES: [string, RegExp][] = [
  ["password", /(?<=(?:password|passcode|passwd|पासवर्ड)\s?(?:hai|he|is|है|:|=)\s?)\S+/gi],
  ["password", /(?<=(?:password|passcode|passwd|पासवर्ड)\s)(?!hai\b|he\b|is\b|kya\b|है)\S+(?=\s+(?:hai|he|है)\b)/gi],
  ["otp/pin", new RegExp(`(?<=(?:${SECRET_WORDS})\\D{0,25})\\b\\d{3,8}\\b`, "gi")],
  ["bank account", new RegExp(`(?<=(?:${ACCOUNT_WORDS})\\D{0,25})\\b\\d{9,18}\\b`, "gi")],
  ["pan", /\b[A-Z]{5}\d{4}[A-Z]\b/gi],
  [LONG_NUMBER, /\b\d(?:[ -]?\d){11,18}\b/g],
];

const AADHAAR_SHAPE = /^\d{4}[ -]\d{4}[ -]\d{4}$/;

export function maskPrivate(input: string): { text: string; hidden: string[] } {
  const hidden = new Set<string>();
  let text = input;
  for (const [kind, regex] of RULES) {
    text = text.replace(regex, (match) => {
      const value = match.trim();
      if (kind !== LONG_NUMBER) hidden.add(kind);
      else if (AADHAAR_SHAPE.test(value)) hidden.add("aadhaar");
      else if (isPhoneWithCountryCode(value)) return match;
      else hidden.add("card/long number");
      return MASK;
    });
  }
  return { text, hidden: [...hidden] };
}

// "+91 98765 43210" phone number hai, card nahi.
function isPhoneWithCountryCode(value: string): boolean {
  const digits = value.replace(/\D/g, "");
  return digits.length === 12 && digits.startsWith("91") && /[6-9]/.test(digits[2] ?? "");
}
