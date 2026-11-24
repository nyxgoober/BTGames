package us.crafties.btgames.games.keymash

import kotlin.math.roundToInt

/** Which race this match is. Codes double as the wire encoding. */
enum class KeymashMode(val code: Int) {
    SENTENCE(0),
    WORDS(1);

    companion object {
        fun fromCode(c: Int): KeymashMode? = entries.firstOrNull { it.code == c }
    }
}

/**
 * Everything both phones must agree on without sending it: the word and sentence banks, how a
 * shared seed turns into identical prompts on both sides, and how speed turns into points.
 * Pure Kotlin, fully unit tested.
 */
object KeymashContent {
    const val WORD_COUNT = 25
    const val SENTENCE_COUNT = 3

    /** How long a stalled player gets before their partial run is scored as-is. */
    const val PLAY_TIMEOUT_MS = 180_000L

    /** Everyday words, lowercase, no punctuation: phone keyboards type these fastest. */
    val WORDS = listOf(
        "the", "be", "of", "and", "have", "with", "this", "from", "they", "would",
        "there", "their", "what", "about", "which", "when", "make", "like", "time", "just",
        "know", "take", "into", "year", "your", "good", "some", "them", "than", "then",
        "look", "only", "come", "think", "also", "back", "after", "use", "people", "great",
        "again", "where", "most", "over", "such", "because", "even", "before", "through", "between",
        "much", "right", "too", "mean", "same", "tell", "does", "down", "want", "need",
        "world", "still", "learn", "plant", "cover", "house", "picture", "river", "stone", "bird",
        "night", "light", "morning", "water", "music", "paper", "quick", "quiet", "rain", "road",
        "smile", "sound", "spring", "storm", "story", "street", "strong", "table", "taste", "thank",
        "today", "together", "tomorrow", "town", "train", "travel", "tree", "truck", "mountain", "garden",
        "bridge", "brown", "build", "carry", "catch", "chair", "chance", "change", "chocolate", "clock",
        "cloud", "dance", "dream", "drink", "drive", "early", "earth", "empty", "enemy", "enjoy",
        "enter", "every", "field", "fight", "fresh", "friend", "front", "frost", "fruit", "glass",
        "green", "ground", "happy", "heart", "heavy", "hello", "honey", "horse", "hungry", "island",
        "jolly", "juice", "kitchen", "laugh", "lemon", "lucky", "lunch", "magic", "market", "melody",
        "merry", "money", "month", "ocean", "offer", "often", "orange", "paint", "party", "peace",
        "pencil", "phone", "photo", "piano", "piece", "pizza", "plane", "planet", "plate", "proud",
        "apple", "banana", "bread", "butter", "cheese", "cookie", "grape", "mango", "peach", "pepper",
        "potato", "salad", "soup", "sugar", "toast", "bacon", "beans", "berry", "cake", "candy",
        "carrot", "cherry", "corn", "dinner", "feast", "flavor", "garlic", "ginger", "kettle", "knife",
        "oven", "pasta", "pickle", "roast", "snack", "spice", "spoon", "sweet", "treat", "wheat",
        "crust", "supper", "cocoa", "cider", "maple", "meadow", "moss", "willow",
        "bear", "wolf", "fox", "ant", "ape", "badger", "bat", "beaver", "bison", "bunny",
        "camel", "cat", "chicken", "colt", "crab", "crane", "cricket", "crow", "deer", "dolphin",
        "donkey", "dove", "eagle", "elephant", "falcon", "finch", "firefly", "fish", "flamingo", "frog",
        "gazelle", "giraffe", "goat", "goose", "hamster", "hawk", "heron", "jaguar", "kangaroo", "kitten",
        "koala", "lark", "leopard", "lion", "lizard", "llama", "lobster", "mouse", "otter", "owl",
        "panda", "parrot", "penguin", "pigeon", "pony", "puppy", "rabbit", "raccoon", "raven", "robin",
        "salmon", "seal", "shark", "sheep", "shrimp", "snail", "snake", "sparrow", "spider", "squirrel",
        "swan", "tiger", "toad", "trout", "tuna", "turkey", "turtle", "whale", "zebra",
        "bake", "blink", "bloom", "bolt", "bounce", "bring", "brush", "bubble", "bump", "burn",
        "burst", "carve", "chase", "cheer", "chew", "climb", "clap", "clean", "clear", "close",
        "collect", "count", "crash", "crawl", "create", "cruise", "crush", "dash", "decide", "delight",
        "deliver", "dive", "dodge", "drift", "drum", "escape", "excite", "explore", "fetch", "finish",
        "flap", "flash", "float", "focus", "freeze", "giggle", "glide", "glitter", "glow", "grab",
        "greet", "grind", "groove", "guess", "guide", "hammer", "hike", "hurry", "imagine", "invent",
        "juggle", "jump", "knock", "leap", "listen", "march", "marvel", "mash", "match", "mend",
        "merge", "mimic", "nudge", "open", "paddle", "peek", "perform", "picnic", "pitch", "pluck",
        "plunge", "point", "polish", "ponder", "pop", "pour", "pounce", "prance", "press",
        "amber", "awake", "bitter", "black", "blank", "bold", "breezy", "bright", "brisk", "calm",
        "chilly", "clever", "cozy", "creamy", "crisp", "crunchy", "curly", "daring", "eager", "elegant",
        "endless", "epic", "faint", "fair", "fast", "fiery", "fluffy", "foggy", "funny", "gentle",
        "giant", "glad", "glossy", "golden", "grand", "grateful", "handy", "hazy", "hearty", "humble",
        "icy", "ideal", "keen", "kind", "lively", "lonely", "lovely", "lucid", "lunar", "lush",
        "misty", "modern", "muddy", "narrow", "neat", "nifty", "nimble", "noble", "noisy", "novel",
        "pastel", "peppy", "perky", "plucky", "plump", "plush", "polite", "pretty", "prompt", "punchy",
        "purple", "quaint", "quirky", "rapid", "roomy", "rosy", "round", "rowdy", "royal", "rusty",
        "sandy", "sassy", "scary", "shady", "shaky", "sharp", "shiny", "short", "silky", "silly",
        "simple", "sleepy", "slender", "slick", "slow", "small", "smart", "smoky", "smooth", "snappy",
        "sneaky", "snug", "soft", "soggy", "solar", "solid", "sunny", "superb", "swift", "tangy",
        "tasty", "tender", "tense", "thick", "thin", "thirsty", "tidy", "tiny", "toasty", "tranquil",
        "tricky", "trim", "trusty", "vivid", "warm", "wealthy", "weary", "weird", "witty", "wobbly",
        "wooden", "woolly", "yellow", "yummy", "zany", "zippy",
    )

    /** Short sentences with capitals and periods, all kind to a phone keyboard. */
    val SENTENCES = listOf(
        "The quick brown fox jumps over the lazy dog.",
        "A gentle rain fell on the quiet town.",
        "She sells fresh bread at the corner market.",
        "The river winds slowly through the green valley.",
        "Bright stars filled the clear night sky.",
        "He fixed the old clock with careful hands.",
        "The children played happily in the warm sand.",
        "A strong wind shook the tall trees outside.",
        "We watched the sun sink behind the hills.",
        "The cat slept soundly on the soft chair.",
        "Fresh snow covered every roof in the village.",
        "They shared stories around the warm fire.",
        "The train arrived exactly on time today.",
        "He wrote a short letter to his old friend.",
        "The garden blooms with color every spring.",
        "Birds sing sweetly outside my window each morning.",
        "We packed our bags and left before dawn.",
        "The baker pulled warm loaves from the oven.",
        "The dog barked loudly at the passing truck.",
        "She found a tiny shell on the sandy beach.",
        "Fresh coffee steamed on the wooden table.",
        "A brave mouse faced the hungry cat.",
        "The old bridge creaks in the cold wind.",
        "Leaves dance across the empty street.",
        "He tossed the ball high into the air.",
        "The phone buzzed during the quiet movie.",
        "Clouds drift slowly over the still lake.",
        "She keeps a cactus on her sunny desk.",
        "The puppy chewed my left shoe again.",
        "Thunder rumbled far behind the hills.",
        "We raced our bikes down the steep hill.",
        "The library closes early on Fridays.",
        "A robin built a nest above our door.",
        "He hummed a tune while washing dishes.",
        "The kite soared above the grassy field.",
        "Snow melted into puddles by noon.",
        "The owl hooted once, then went silent.",
        "She drew a map on the back of a menu.",
        "The elevator stopped on every floor.",
        "Ants marched in a line toward the sugar.",
        "The baby laughed at the barking dog.",
        "He saved his coins in a glass jar.",
        "The pond rippled under the soft breeze.",
        "We counted stars until we fell asleep.",
        "The zebra grazed beside the tall fence.",
        "She tied her shoes and joined the race.",
        "The soup simmered slowly on the stove.",
        "A frog leaped off the mossy stone.",
        "The mail truck rattled down our road.",
        "He polished his bike until it gleamed.",
    )

    /** Both phones run this with the seed from the CHOICE message and get the same list. */
    fun targetsFor(mode: KeymashMode, seed: Long): List<String> {
        val random = kotlin.random.Random(seed)
        return when (mode) {
            KeymashMode.WORDS -> List(WORD_COUNT) { WORDS[random.nextInt(WORDS.size)] }
            KeymashMode.SENTENCE -> SENTENCES.indices
                .shuffled(random)
                .take(SENTENCE_COUNT)
                .map { SENTENCES[it] }
        }
    }

    /** Correct characters when [typed] was submitted for [target] (spaces count elsewhere). */
    fun matches(typed: String, target: String): Int =
        typed.indices.count { it < target.length && typed[it] == target[it] }

    /** Credit for a submitted word: only the right word earns the submitting space in full. */
    fun wordCredit(typed: String, target: String): Int {
        val positional = matches(typed, target)
        return if (typed == target) positional + 1 else positional / 2
    }

    /** Correct leading characters of an unfinished attempt (partial credit on timeout). */
    fun prefixMatches(typed: String, target: String): Int {
        var n = 0
        while (n < typed.length && n < target.length && typed[n] == target[n]) n++
        return n
    }

    /**
     * Sentence acceptance: case does not matter and the closing dot is optional, so a
     * password-layout keyboard with no auto-shift is not a handicap.
     */
    fun sentenceMatches(typed: String, target: String): Boolean {
        if (typed.equals(target, ignoreCase = true)) return true
        return target.endsWith('.') && typed.equals(target.dropLast(1), ignoreCase = true)
    }

    /** Correct leading characters of an unfinished sentence, ignoring case. */
    fun sentencePrefixMatches(typed: String, target: String): Int {
        var n = 0
        while (n < typed.length && n < target.length && typed[n].equals(target[n], ignoreCase = true)) n++
        return n
    }

    fun wpmOf(correct: Int, elapsedMs: Long): Double {
        if (correct <= 0 || elapsedMs <= 0) return 0.0
        val minutes = elapsedMs.coerceAtLeast(1000) / 60_000.0
        return (correct / 5.0) / minutes
    }

    fun accuracyOf(correct: Int, typed: Int): Double {
        if (typed <= 0) return 0.0
        return (correct.toDouble() / typed).coerceIn(0.0, 1.0)
    }

    /**
     * Points: words-per-minute times accuracy, times 100. A 40 wpm run at 95% is 3800;
     * spray-and-pray typing tanks accuracy and with it the score.
     */
    fun scoreOf(correct: Int, typed: Int, elapsedMs: Long): Int {
        if (typed <= 0) return 0
        return (wpmOf(correct, elapsedMs) * accuracyOf(correct, typed) * 100).roundToInt()
    }
}
