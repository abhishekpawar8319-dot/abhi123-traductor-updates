package com.abhi.vozconversa.core

/**
 * Lightweight, offline Spanish-vs-English guess from text. Used when the speech recognizer
 * does not report which language it heard.
 */
object LanguageGuesser {
    // Only words that exist in one of the two languages. Words both share ("no", "a", "me", "son", "van",
    // "hotel", "taxi", "radio", "cable", "once", "pie", "sale", "solo") are left out on purpose.
    private val spanish = setOf(
        "el", "la", "los", "las", "de", "del", "que", "y", "en", "un", "una", "es", "por", "con",
        "para", "pero", "más", "mas", "muy", "yo", "tú", "usted", "ustedes", "está", "estoy",
        "están", "estás", "estamos", "hola", "gracias", "sí", "qué", "cómo", "dónde", "cuándo", "cuánto",
        "bien", "mal", "buenos", "buenas", "bueno", "buena", "días", "día", "tardes", "tarde", "noches", "noche",
        "mi", "mis", "su", "sus", "tu", "tus", "lo", "le", "les", "se", "te", "nos", "al", "este", "esta", "eso", "esto",
        "ese", "esa", "aquí", "allí", "ahí", "ahora", "hoy", "mañana", "ayer", "anoche", "tengo", "tiene", "tienes",
        "tenemos", "quiero", "quieres", "quiere", "puedo", "puede", "puedes", "podemos", "hay", "somos", "soy", "eres",
        "fue", "era", "ser", "estar", "hacer", "hace", "vamos", "voy", "vas", "va", "necesito", "necesita",
        "necesitamos", "también", "porque", "cuando", "donde", "como", "nada", "todo", "toda", "todos", "todas",
        "mucho", "mucha", "muchos", "muchas", "poco", "otra", "otro", "otros", "cada", "hasta", "sin", "sobre",
        "entre", "después", "antes", "señor", "señora", "amigo", "trabajo", "trabaja", "trabajamos", "casa", "agua",
        "comida", "dinero", "hora", "horas", "tiempo", "semana", "lunes", "martes", "miércoles", "jueves", "viernes",
        "sábado", "domingo", "año", "años", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho",
        "nueve", "diez", "doce", "trece", "quince", "veinte", "treinta", "cuarenta", "cincuenta", "cien", "ciento",
        "mil", "falta", "faltan", "vez", "vale", "ahorita", "tal", "claro", "sé", "sabes", "dijo", "trae", "traer",
        "lleva", "llega", "llegan", "llegó", "empezamos", "terminamos", "terminó", "revisa", "revisar", "limpia",
        "limpiar", "guarda", "guardar", "baja", "bajar", "sube", "subir", "pon", "ponte", "ven", "mira", "oye",
        "espera", "cuidado", "grande", "pequeño", "nuevo", "nueva", "listo", "lista", "roto", "rota", "cerca", "lejos",
        "arriba", "abajo", "dentro", "fuera", "siempre", "nunca", "ya", "adiós", "perdón", "permiso", "momento",
        "vemos", "herramientas", "escalera", "casco", "guantes", "pernos", "tornillos", "tornillo", "cuerda",
        "cuerdas", "camión", "camioneta", "obra", "torre", "almacén", "oficina", "jefe", "capataz", "ingeniero",
        "ingeniera", "chofer", "hermano", "hermana", "madre", "padre", "esposa", "hijo", "hija", "abuela", "tío",
        "cabeza", "espalda", "brazo", "rodilla", "mano", "cuello", "estómago", "pago", "boleto", "gasolina",
        "autobús", "tren", "avión", "coche", "carro", "tablas", "botas", "lentes", "llaves", "llave", "tuercas",
        "cemento", "bolsas", "metros", "trabajadores", "lluvia", "llover", "viento", "calor", "frío", "niebla",
        "dejó", "dejamos", "olvidé", "encuentro", "toques", "pares", "debajo", "deben", "cuesta", "gusta", "comemos",
        "duele", "siente", "cumple", "vive", "salimos", "quién", "cuál", "pasas", "nos",
        "mucho", "verduras", "pollo", "arroz", "carne", "pescado", "huevos", "queso", "jugo", "naranja",
        "refresco", "caliente", "fría", "nosotros", "ellos", "ellas", "él", "ella", "compañero", "contento",
        "cansado", "cansada", "enfermo", "preocupado", "mareado", "parece", "supuesto", "qué", "escuché",
        "equivoqué", "sabía", "olvidó", "venir", "esperar", "ayuda", "medir", "mover", "pintar", "cambiar",
    )
    private val english = setOf(
        "the", "and", "is", "are", "you", "i", "to", "of", "it", "that", "this", "what", "with",
        "have", "has", "for", "not", "be", "was", "were", "we", "they", "my", "your", "do", "does",
        "did", "can", "will", "would", "hello", "hi", "thanks", "thank", "please", "where", "how",
        "when", "why", "who", "yes", "good", "morning", "afternoon", "evening", "night", "here",
        "there", "now", "today", "tomorrow", "yesterday", "want", "need", "needs", "am", "he", "she", "him",
        "her", "our", "their", "from", "at", "on", "in", "about", "just", "very", "much", "many",
        "some", "time", "work", "works", "house", "water", "food", "money", "friend", "sir", "okay", "ok",
        "right", "going", "go", "come", "know", "think", "see", "could", "should", "an", "or", "but", "if", "so",
        "because", "all", "nothing", "everything", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "twenty", "thirty", "fifty", "hundred", "thousand", "arrives", "arrive",
        "lives", "live", "left", "finished", "checked", "forgot", "bring", "lift", "lower", "store", "clean", "check",
        "move", "paint", "measure", "change", "put", "pass", "find", "touch", "start", "stop", "eat", "like", "owe",
        "leaves", "said", "feels", "hurts", "broke", "climbing", "careful", "welcome", "fine", "great", "sure",
        "thing", "maybe", "sorry", "excuse", "moment", "bye", "course", "truck", "ladder", "helmet", "drill", "rope",
        "hammer", "wrench", "level", "tape", "harness", "crane", "bolt", "bolts", "gloves", "keys", "tools", "boots",
        "glasses", "nuts", "workers", "boards", "screws", "bags", "bottles", "meters", "payment", "ticket", 
        "train", "plane", "car", "week", "tonight", "birthday", "wife", "mother", "father", "sister",
        "daughter", "brother", "uncle", "grandmother", "head", "back", "arm", "knee", "stomach", "hand", "foot",
        "neck", "tired", "sick", "happy", "worried", "dizzy", "cold", "hot", "windy", "sunny", "foggy", "rain",
        "fog", "always", "already", "next", "last", "again", "before", "after", "under", "near", "up", "down",
        "office", "warehouse", "site", "tower", "gate", "boss", "people", "job", "meeting", "late", "missing",
        "ready", "broken", "new", "big", "small", "lot", "way", "sounds", "way", "of", "partner", "driver",
        "engineer", "foreman", "supervisor", "electrician", "pay", "days", "day", "chicken", "rice", "beef",
        "vegetable", "soup", "fried", "fish", "beans", "eggs", "ham", "cheese", "coffee", "juice", "orange", 
        "tea", "cement", "help", "waiting", "coming", "mistake", "hear", "heard", "made",
    )
    private val loanwords = setOf("café", "josé", "piñata", "jalapeño", "jalapeños", "résumé", "cliché", "fiancé",
        "señorita", "habanero", "piñatas", "cafés")
    private val nameWords = setOf("el", "la", "los", "las", "de", "del", "san", "santa", "the")
    private val esEndings = listOf("amos", "emos", "imos", "ción", "ciones", "ando", "iendo")

    /** Returns (spanishScore, englishScore). */
    fun score(text: String): Pair<Int, Int> {
        var es = 0
        var en = 0
        if (text.any { it == '¿' || it == '¡' }) es += 3
        val original = text.split(Regex("[^\\p{L}\\p{N}'’]+")).filter { it.isNotEmpty() }
        fun capital(k: Int) = original.getOrNull(k)?.firstOrNull()?.isUpperCase() == true
        for ((i, raw) in original.withIndex()) {
            val w = raw.lowercase().replace('’', '\'')
            // "Los" in "Los Angeles", "de" in "Rio de Janeiro": part of a name, not a clue.
            if (w in nameWords && ((capital(i) && capital(i + 1)) || (i > 1 && capital(i - 1) && capital(i + 1)))) continue
            // Contractions ("that's", "you're", "can't") only happen in English.
            if ('\'' in w.trim('\'')) { en += 2; continue }
            // Accents and ñ are Spanish, except in words English uses too ("café", "José", "piñata").
            if (w !in loanwords) es += 2 * w.count { it in "ñáéíóúü" }
            if (w in spanish) es++
            if (w in english) en++
            if (w.length >= 5 && w !in spanish && esEndings.any { w.endsWith(it) }) es++
            if (w.length >= 5 && w !in english && w.endsWith("ing")) en++
        }
        return es to en
    }

    /** Spanish/English guess, or null when the text gives no clear signal. */
    fun guess(text: String): Lang? {
        val (es, en) = score(text)
        return when {
            es > en -> Lang.ES
            en > es -> Lang.EN
            else -> null
        }
    }
}

/** Decides which language a finished sentence was spoken in. */
object DirectionDecider {
    /**
     * @param detected language reported by the recognizer (Android 14+), if any
     * @param listening language the recognizer was primed for
     */
    fun sourceOf(text: String, mode: ListenMode, detected: Lang?, listening: Lang): Lang = when (mode) {
        ListenMode.SPANISH -> Lang.ES
        ListenMode.ENGLISH -> Lang.EN
        ListenMode.AUTO -> {
            val guess = LanguageGuesser.guess(text)
            val (es, en) = LanguageGuesser.score(text)
            val strongGuess = guess != null && kotlin.math.abs(es - en) >= 2
            when {
                // Clear text evidence wins over a recognizer detection that disagrees.
                strongGuess -> guess!!
                detected != null -> detected
                guess != null -> guess
                else -> listening
            }
        }
    }
}
