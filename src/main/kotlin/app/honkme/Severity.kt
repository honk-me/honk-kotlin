package app.honkme

/**
 * Severity, lowest to highest. Every constant has a horn name on the Honk scale; the horn names
 * are aliases of the canonical constants: `Severity.LOUD === Severity.WARNING`.
 *
 * LONG (error) and BLAST (critical) push at least as high priority.
 */
public enum class Severity(public val value: String) {
    INFO("info"),
    SUCCESS("success"),
    WARNING("warning"),
    ERROR("error"),
    CRITICAL("critical"),
    ;

    /** The horn name: light, beep, loud, long or blast. */
    public val horn: String
        get() = when (this) {
            INFO -> "light"
            SUCCESS -> "beep"
            WARNING -> "loud"
            ERROR -> "long"
            CRITICAL -> "blast"
        }

    public companion object {
        /** A light honk (info). */
        @JvmField public val LIGHT: Severity = INFO

        /** A beep-beep (success). */
        @JvmField public val BEEP: Severity = SUCCESS

        /** A loud honk (warning). */
        @JvmField public val LOUD: Severity = WARNING

        /** A long honk (error). */
        @JvmField public val LONG: Severity = ERROR

        /** A blast (critical). */
        @JvmField public val BLAST: Severity = CRITICAL

        /** Horn name → canonical severity. */
        @JvmField public val ALIASES: Map<String, Severity> =
            mapOf("light" to INFO, "beep" to SUCCESS, "loud" to WARNING, "long" to ERROR, "blast" to CRITICAL)

        /** A canonical or horn name, case-insensitively: `parseOrNull("LOUD") == WARNING`. */
        @JvmStatic
        public fun parseOrNull(value: String): Severity? {
            val v = value.trim().lowercase()
            return entries.firstOrNull { it.value == v } ?: ALIASES[v]
        }

        /** Like [parseOrNull], but throws [HonkValidationException] for unknown names. */
        @JvmStatic
        public fun parse(value: String): Severity = parseOrNull(value)
            ?: throw HonkValidationException.local(listOf(FieldError("severity", "invalid_enum", SEVERITY_HINT)))

        internal const val SEVERITY_HINT: String =
            "must be one of light (info), beep (success), loud (warning), long (error), blast (critical)"
    }
}

/** Declared priority. URGENT needs an ingestion key with "allow urgent". */
public enum class Priority(public val value: String) {
    LOW("low"), NORMAL("normal"), HIGH("high"), URGENT("urgent");

    public companion object {
        @JvmStatic
        public fun parse(value: String): Priority = entries.firstOrNull { it.value == value.trim().lowercase() }
            ?: throw HonkValidationException.local(listOf(FieldError("priority", "invalid_enum", "must be one of low, normal, high, urgent")))
    }
}

/** PROBLEM opens an incident for its group, RECOVERY closes it; both need a group key. */
public enum class EventType(public val value: String) {
    EVENT("event"), PROBLEM("problem"), RECOVERY("recovery");

    public companion object {
        @JvmStatic
        public fun parse(value: String): EventType = entries.firstOrNull { it.value == value.trim().lowercase() }
            ?: throw HonkValidationException.local(listOf(FieldError("event_type", "invalid_enum", "must be one of event, problem, recovery")))
    }
}

/** Category taxonomy v1. */
public enum class Category(public val value: String) {
    INFRASTRUCTURE("infrastructure"), SECURITY("security"), BACKUPS("backups"), DEPLOYMENTS("deployments"),
    PAYMENTS("payments"), CUSTOMERS("customers"), SALES("sales"), AUTOMATION("automation"), PERSONAL("personal"),
    OTHER("other");

    public companion object {
        @JvmStatic
        public fun parse(value: String): Category = entries.firstOrNull { it.value == value.trim().lowercase() }
            ?: throw HonkValidationException.local(
                listOf(FieldError("category", "invalid_enum", "must be one of " + entries.joinToString(", ") { it.value })),
            )
    }
}
