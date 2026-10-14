package com.max.assistant

/**
 * Bole gaye naam ko contact list se milana. Speech-to-text Hindi naam Devanagari mein deta hai ("पापा"),
 * jabki contacts aksar English mein save hote hain ("Papa") — isliye dono ko Roman mein badal kar milate hain.
 * Saath mein milte-julte naam bhi dhundhta hai, taaki Max "A se Abhishek, Aman hain..." bol sake.
 */
object NameMatcher {

    /** Devanagari → Roman (lagbhag). Vowel sahi hon zaroori nahi — milane ke liye [skeleton] bhi hai. */
    fun toLatin(text: String): String {
        // क़ ज़ फ़ jaise akshar kabhi ek code point, kabhi "क + ़" — sabko dusre roop mein le aao.
        var decomposed = text
        PRECOMPOSED_NUKTA.forEach { (single, base) -> decomposed = decomposed.replace(single.toString(), "$base$NUKTA") }

        val out = StringBuilder()
        val chars = decomposed.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            val consonant = CONSONANTS[c]
            when {
                consonant != null -> {
                    val hasNukta = chars.getOrNull(i + 1) == NUKTA
                    out.append(if (hasNukta) NUKTA_SOUNDS[c] ?: consonant else consonant)
                    if (hasNukta) i++
                    // Vyanjan ke saath "a" apne aap hota hai, jab tak aage matra/halant na ho (aur shabd ke aakhir mein nahi).
                    val next = chars.getOrNull(i + 1)
                    if (next != null && CONSONANTS.containsKey(next)) out.append('a')
                }
                c in VOWELS -> out.append(VOWELS[c])
                c in MATRAS -> out.append(MATRAS[c])
                c == NUKTA || c == HALANT -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Sirf chhote letters, Roman mein: "पापा जी" → "papaji", "Papa Ji" → "papaji". */
    fun normalize(name: String): String = toLatin(name).lowercase().filter { it in 'a'..'z' }

    /** Vowels hata ke vyanjan ka dhancha: "papa" → "pp", "rahul" → "rhl" — Hindi/English spelling farak se bachne ke liye. */
    fun skeleton(name: String): String {
        val n = normalize(name).replace("ph", "f").replace("w", "v").replace("z", "j")
        val consonants = n.filter { it !in "aeiouy" || it == 'y' && n.length <= 2 }.replace("h", "")
        return consonants.fold(StringBuilder()) { sb, ch -> if (sb.lastOrNull() != ch) sb.append(ch); sb }.toString()
    }

    /** 0..1, kitna milta-julta hai. */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        val full = ratio(na, nb)
        val sa = skeleton(a)
        val sb = skeleton(b)
        val bones = if (sa.isNotEmpty() && sb.isNotEmpty()) ratio(sa, sb) * 0.9 else 0.0
        return maxOf(full, bones)
    }

    /**
     * [names] mein se [query] se match. Pehle pakka match (same naam / naam ke andar / same dhancha),
     * na mile to khaali list — tab [suggest] se milte-julte naam lo.
     */
    fun match(query: String, names: List<String>): List<String> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qWords = toLatin(query).lowercase().split(Regex("\\s+")).map { normalize(it) }.filter { it.isNotEmpty() }
        names.filter { normalize(it) == q }.takeIf { it.isNotEmpty() }?.let { return it }
        names.filter { name ->
            val words = toLatin(name).lowercase().split(Regex("\\s+")).map { normalize(it) }
            words.any { it == q } || qWords.all { qw -> words.any { it == qw } } || (q.length >= 3 && normalize(name).contains(q))
        }.takeIf { it.isNotEmpty() }?.let { return it }
        val qs = skeleton(query)
        if (qs.length >= 2) names.filter { skeleton(it) == qs }.takeIf { it.isNotEmpty() }?.let { return it }
        return names.filter { similarity(query, it) >= 0.8 }
    }

    /** Milte-julte naam (sabse zyada milte pehle), aur kam hon to same pehle akshar wale. */
    fun suggest(query: String, names: List<String>, limit: Int = 5): List<String> {
        val unique = names.distinct()
        val close = unique.map { it to similarity(query, it) }
            .filter { it.second >= 0.5 }
            .sortedByDescending { it.second }
            .map { it.first }
        val first = normalize(query).firstOrNull()
        val sameLetter = if (first == null) emptyList()
        else unique.filter { normalize(it).firstOrNull() == first }.sortedBy { it.lowercase() }
        return (close + sameLetter).distinct().take(limit)
    }

    private fun ratio(a: String, b: String): Double = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private const val NUKTA = '़'
    private const val HALANT = '्'

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )

    /** Nukta ke saath aawaz badalti hai: ज़ = z, फ़ = f, ड़ = r ... */
    private val NUKTA_SOUNDS = mapOf(
        'क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f", 'य' to "y",
    )

    /** Ek code point wale nukta akshar (U+0958..U+095F) → unka base akshar. */
    private val PRECOMPOSED_NUKTA = mapOf(
        'क़' to 'क', 'ख़' to 'ख', 'ग़' to 'ग', 'ज़' to 'ज',
        'ड़' to 'ड', 'ढ़' to 'ढ', 'फ़' to 'फ', 'य़' to 'य',
    )
    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऋ' to "ri",
    )
    private val MATRAS = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'े' to "e",
        'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ृ' to "ri", 'ं' to "n", 'ँ' to "n", 'ः' to "h",
    )
}
