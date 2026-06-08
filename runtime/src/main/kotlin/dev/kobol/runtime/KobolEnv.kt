package dev.kobol.runtime

import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Environment-variable loader for Kobol CONFIG sections.
 *
 * Resolution order (first value wins):
 *   1. Process environment (`System.getenv`)
 *   2. `.env` file in the working directory (or the file named by
 *      `-Dkobol.env.file=path` system property)
 *
 * The `.env` format follows the widely-used dotenv convention:
 *   - Lines starting with `#` are comments.
 *   - Blank lines are skipped.
 *   - Values may be optionally quoted with `"` or `'` (outer quotes are stripped).
 *   - No variable interpolation inside values (intentional — keeps it predictable).
 *
 * Example `.env` file:
 * ```
 * # Database config
 * DB_URL=postgresql://localhost:5432/myapp
 * PORT=8080
 * DEBUG=true
 *
 * # Sensitive — never commit to version control
 * API_KEY="my-secret-key"
 * ```
 *
 * The `.env` file is loaded lazily on the first config access and cached
 * for the lifetime of the program. It is intentionally NOT loaded when the
 * key is already present in the process environment — production deployments
 * set real env vars and the `.env` file is silently ignored.
 */
object KobolEnv {

    /**
     * Parsed `.env` file contents, loaded at most once per process.
     * Using `object`-level lazy means it is thread-safe without explicit locking.
     */
    private val dotEnv: Map<String, String> by lazy { loadDotEnv() }

    // -------------------------------------------------------------------------
    //  Public API — used by compiled Kobol programs (via AsmEmitter.Config.kt)
    // -------------------------------------------------------------------------

    /**
     * Returns the value for [key]: system environment first, then `.env` file.
     * Returns `null` if neither source has the key.
     */
    @JvmStatic
    fun getenv(key: String): String? = System.getenv(key) ?: dotEnv[key]

    /**
     * Returns the value for [key], or throws [KobolConfigError] with a helpful
     * message if neither the environment nor the `.env` file provides it.
     *
     * Used for CONFIG items declared `REQUIRED`.
     */
    @JvmStatic
    fun require(key: String, configName: String): String =
        getenv(key) ?: throw KobolConfigError(
            "CONFIG item '$configName' is REQUIRED but environment variable '$key' is not set.\n" +
            "  → Set it in your process environment, or add it to a .env file in the working directory."
        )

    // -------------------------------------------------------------------------
    //  Interactive terminal input (spec §27.1) — ACCEPT / CONFIRM
    // -------------------------------------------------------------------------

    /**
     * §27.1 `CONFIRM "msg"` — print the prompt and read a yes/no answer from stdin.
     * Returns true only for `y`/`yes` (case-insensitive). EOF / no input → false (safe default:
     * a non-interactive run never silently confirms a destructive action).
     */
    @JvmStatic
    fun confirm(message: String): Boolean {
        print("$message [y/N] ")
        System.out.flush()
        val line = readlnOrNull()?.trim()?.lowercase() ?: return false
        return line == "y" || line == "yes"
    }

    /**
     * §27.1 `ACCEPT v FROM TERMINAL PROMPT "msg"` — print the prompt, read one line from stdin.
     * EOF → empty string (the program decides how to handle a missing answer).
     */
    @JvmStatic
    fun acceptTerminal(prompt: String): String {
        print(prompt)
        System.out.flush()
        return readlnOrNull() ?: ""
    }

    /** Program argv, captured by the generated `main` via [setArgs] so any procedure can read it. */
    @Volatile private var programArgs: Array<String> = emptyArray()

    /** Called once at the top of generated `main(String[])` — stores argv for ACCEPT FROM ARGUMENT. */
    @JvmStatic
    fun setArgs(args: Array<String>) { programArgs = args }

    /**
     * §27.1 `ACCEPT v FROM ARGUMENT "--name" [DEFAULT d]` — read a command-line argument value
     * from the argv captured by [setArgs]. Supports both `--name value` (two tokens) and
     * `--name=value` forms. Returns [default] when the flag is absent.
     */
    @JvmStatic
    fun acceptArgument(flag: String, default: String): String {
        val args = programArgs
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == flag && i + 1 < args.size -> return args[i + 1]
                a.startsWith("$flag=")        -> return a.substring(flag.length + 1)
            }
            i++
        }
        return default
    }

    // -------------------------------------------------------------------------
    //  Type coercion helpers — called by generated config-loading bytecode
    // -------------------------------------------------------------------------

    /** Parses a raw env string to a `long` (Kobol INTEGER). */
    @JvmStatic
    fun toLong(raw: String, configName: String): Long =
        raw.trim().toLongOrNull()
            ?: throw KobolConfigError("CONFIG item '$configName': cannot parse '$raw' as INTEGER.")

    /** Parses a raw env string to a [BigDecimal] (Kobol DECIMAL / MONEY). */
    @JvmStatic
    fun toDecimal(raw: String, configName: String): BigDecimal =
        try { BigDecimal(raw.trim()) }
        catch (_: NumberFormatException) {
            throw KobolConfigError("CONFIG item '$configName': cannot parse '$raw' as DECIMAL.")
        }

    /**
     * Parses a raw env string to a `boolean` (Kobol BOOLEAN).
     * Accepts `true / false / yes / no / 1 / 0` case-insensitively.
     */
    @JvmStatic
    fun toBoolean(raw: String, configName: String): Boolean =
        when (raw.trim().lowercase()) {
            "true",  "yes", "1", "on"  -> true
            "false", "no",  "0", "off" -> false
            else -> throw KobolConfigError(
                "CONFIG item '$configName': cannot parse '$raw' as BOOLEAN. " +
                "Use true/false, yes/no, 1/0, or on/off."
            )
        }

    /** Parses a raw env string to a [LocalDate] (Kobol DATE, ISO-8601 `YYYY-MM-DD`). */
    @JvmStatic
    fun toDate(raw: String, configName: String): LocalDate =
        try { LocalDate.parse(raw.trim()) }
        catch (_: Exception) {
            throw KobolConfigError(
                "CONFIG item '$configName': cannot parse '$raw' as DATE. Use ISO-8601 format YYYY-MM-DD."
            )
        }

    /** Parses a raw env string to a [LocalTime] (Kobol TIME, ISO-8601 `HH:MM:SS`). */
    @JvmStatic
    fun toTime(raw: String, configName: String): LocalTime =
        try { LocalTime.parse(raw.trim()) }
        catch (_: Exception) {
            throw KobolConfigError(
                "CONFIG item '$configName': cannot parse '$raw' as TIME. Use ISO-8601 format HH:MM:SS."
            )
        }

    /** Parses a raw env string to a [LocalDateTime] (Kobol DATETIME, ISO-8601). */
    @JvmStatic
    fun toDateTime(raw: String, configName: String): LocalDateTime =
        try { LocalDateTime.parse(raw.trim()) }
        catch (_: Exception) {
            throw KobolConfigError(
                "CONFIG item '$configName': cannot parse '$raw' as DATETIME. Use ISO-8601 format."
            )
        }

    // -------------------------------------------------------------------------
    //  .env file parser
    // -------------------------------------------------------------------------

    private fun loadDotEnv(): Map<String, String> {
        val path = System.getProperty("kobol.env.file", ".env")
        val file = File(path)
        if (!file.exists()) return emptyMap()

        return file.readLines()
            .asSequence()
            .map    { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx < 1) return@mapNotNull null   // malformed — skip silently
                val key   = line.substring(0, idx).trim()
                val raw   = line.substring(idx + 1)   // don't trim — value may have leading space intentionally
                val value = unquote(raw)
                if (key.isEmpty()) null else key to value
            }
            .toMap()
    }

    /**
     * Strips a single layer of surrounding `"..."` or `'...'` quotes from [s].
     * No escape handling — values are taken literally between the outer quotes.
     */
    private fun unquote(s: String): String {
        val t = s.trim()
        return when {
            t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) ||
                              (t.startsWith('\'') && t.endsWith('\''))) -> t.drop(1).dropLast(1)
            else -> t
        }
    }
}
