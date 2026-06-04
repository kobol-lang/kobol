package dev.kobol.runtime

import java.io.*
import java.lang.reflect.Field

/**
 * Kobol runtime file I/O.
 *
 * Supports:
 *   - SEQUENTIAL CSV files  (comma-separated, one record per line)
 *   - SEQUENTIAL TEXT files (one line = one String record)
 *   - INDEXED files         (future — currently aliases sequential)
 *
 * Usage from generated Java:
 * ```java
 * KobolSequentialFile<MyRecord> file =
 *     new KobolSequentialFile<>(path, FileMode.INPUT, FileFormat.CSV, MyRecord.class);
 * file.open();
 * MyRecord rec;
 * while ((rec = file.readNext()) != null) { ... }
 * file.close();
 * ```
 *
 * Record classes must have a no-arg constructor and public fields.
 * CSV columns map to fields in declaration order.
 */
class KobolSequentialFile<T : Any>(
    val path: String,
    val mode: KobolFileMode,
    val format: KobolFileFormat,
    val recordClass: Class<T>,
) {
    private var reader: BufferedReader? = null
    private var writer: PrintWriter?    = null
    private var isOpen = false

    /** Public record fields in declaration order. */
    private val fields: List<Field> by lazy { recordClass.fields.toList() }

    /**
     * Column→field mapping for header-based CSV input. Populated from the header
     * line on open(); index i holds the record field whose name matches CSV column
     * i (or null if no field matches that column). Null until a header is read.
     */
    private var headerMap: List<Field?>? = null

    fun open() {
        check(!isOpen) { "File '$path' is already open" }
        when (mode) {
            KobolFileMode.INPUT -> {
                val f = File(path)
                if (!f.exists()) throw KobolException.FileNotFoundException(path)
                reader = f.bufferedReader()
                if (format == KobolFileFormat.CSV) readHeader()
            }
            KobolFileMode.OUTPUT -> writer = PrintWriter(BufferedWriter(FileWriter(path, false)))
            KobolFileMode.EXTEND -> writer = PrintWriter(BufferedWriter(FileWriter(path, true)))
        }
        isOpen = true
    }

    /** Read the CSV header row and map each column to a record field by normalized name. */
    private fun readHeader() {
        val header = reader?.readLine() ?: return
        val byName = fields.associateBy { normalize(it.name) }
        headerMap = splitCsv(header).map { col -> byName[normalize(col)] }
    }

    /** Canonical key for name matching: lowercase, strip non-alphanumeric (so `invoice-id` == `invoiceId`). */
    private fun normalize(s: String): String =
        buildString { for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar()) }

    fun readNext(): T? {
        check(isOpen) { "File '$path' is not open" }
        val line = reader?.readLine() ?: return null
        if (line.isBlank()) return readNext()  // skip blank lines
        return parseLine(line)
    }

    fun write(record: T) {
        check(isOpen) { "File '$path' is not open" }
        val w = writer ?: throw KobolException.IoException("File '$path' is not open for writing")
        w.println(formatRecord(record))
        w.flush()
    }

    fun close() {
        reader?.close(); reader = null
        writer?.close(); writer = null
        isOpen = false
    }

    fun forEach(action: (T) -> Unit) {
        if (!isOpen) open()
        try {
            var rec = readNext()
            while (rec != null) { action(rec); rec = readNext() }
        } finally {
            close()
        }
    }

    /** Collect all records into a list. */
    fun readAll(): List<T> {
        val result = mutableListOf<T>()
        forEach { result.add(it) }
        return result
    }

    // -------------------------------------------------------------------------
    // CSV / TEXT parsing
    // -------------------------------------------------------------------------

    private fun parseLine(line: String): T? {
        return try {
            val instance = recordClass.getDeclaredConstructor().newInstance()
            when (format) {
                KobolFileFormat.CSV   -> parseCsvInto(instance, line)
                KobolFileFormat.TEXT  -> {
                    // TEXT format: the record class must have a single String field named "line" or "value"
                    val f = fields.firstOrNull { it.type == String::class.java }
                    f?.set(instance, line)
                }
                KobolFileFormat.FIXED -> parseFixedInto(instance, line)
            }
            instance
        } catch (e: Exception) {
            null  // skip malformed lines
        }
    }

    private fun parseCsvInto(instance: T, line: String) {
        val cols = splitCsv(line)
        // Header-based mapping (preferred): column i fills the field named in header column i.
        val map = headerMap
        val pairs: List<Pair<Field?, String>> = if (map != null) {
            cols.mapIndexed { i, raw -> (map.getOrNull(i)) to raw }
        } else {
            // No header → positional mapping in declaration order.
            fields.mapIndexed { i, f -> f to (cols.getOrNull(i) ?: "") }
        }
        for ((f, raw) in pairs) {
            if (f == null) continue
            try {
                f.isAccessible = true
                f.set(instance, convertField(f.type, raw.trim()))
            } catch (_: Exception) { /* skip bad field */ }
        }
    }

    private fun parseFixedInto(instance: T, line: String) {
        // Simple whitespace-split for fixed format
        val parts = line.trim().split(Regex("\\s+"))
        fields.forEachIndexed { i, f ->
            if (i >= parts.size) return@forEachIndexed
            f.isAccessible = true
            f.set(instance, convertField(f.type, parts[i]))
        }
    }

    private fun convertField(type: Class<*>, raw: String): Any? = when {
        type == String::class.java            -> raw
        type == Long::class.java ||
        type == Long::class.javaPrimitiveType -> raw.toLongOrNull() ?: 0L
        type == Int::class.java  ||
        type == Int::class.javaPrimitiveType  -> raw.toIntOrNull() ?: 0
        type == Boolean::class.java ||
        type == Boolean::class.javaPrimitiveType -> raw.equals("true", ignoreCase = true) ||
                                                    raw == "1" || raw.equals("Y", ignoreCase = true)
        type.name == "java.math.BigDecimal"   -> java.math.BigDecimal(raw.ifBlank { "0" })
        type.name == "java.time.LocalDate"    -> java.time.LocalDate.parse(raw)
        else                                  -> raw
    }

    private fun formatRecord(record: T): String = when (format) {
        KobolFileFormat.CSV  -> fields.joinToString(",") { f ->
            f.isAccessible = true
            val v = f.get(record)?.toString() ?: ""
            if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"${v.replace("\"", "\"\"")}\"" else v
        }
        KobolFileFormat.TEXT, KobolFileFormat.FIXED -> fields.joinToString(" ") { f ->
            f.isAccessible = true
            f.get(record)?.toString() ?: ""
        }
    }

    // RFC 4180 CSV splitter (handles quoted fields)
    private fun splitCsv(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuote && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQuote = !inQuote
                ch == ',' && !inQuote -> { result.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }
}

enum class KobolFileMode   { INPUT, OUTPUT, EXTEND }
enum class KobolFileFormat { CSV, TEXT, FIXED }

/**
 * Static façade the compiler emits calls into for FILES-section I/O.
 *
 * A Kobol FILE named `InvoiceFile` resolves to `./data/InvoiceFile.<ext>` where
 * `<ext>` is `csv` for CSV format, `txt` for TEXT, `dat` otherwise — relative to
 * the process working directory.
 *
 * Reading (`FOR EACH rec IN InvoiceFile`) is one pass over the file → an in-memory
 * [List]; O(n) time and O(n) space in the record count. OUTPUT files are kept open
 * in a registry keyed by file name across OPEN/WRITE/CLOSE.
 */
object KobolFiles {
    private val openFiles = java.util.concurrent.ConcurrentHashMap<String, KobolSequentialFile<Any>>()

    private fun fmt(format: String): KobolFileFormat = when (format.uppercase()) {
        "CSV"  -> KobolFileFormat.CSV
        "TEXT" -> KobolFileFormat.TEXT
        else   -> KobolFileFormat.FIXED
    }

    private fun ext(format: String): String = when (format.uppercase()) {
        "CSV"  -> "csv"
        "TEXT" -> "txt"
        else   -> "dat"
    }

    /**
     * `<dir>/<name>.<ext>` where `<dir>` is the `kobol.data.dir` system property
     * (default `data`, relative to the working directory).
     */
    @JvmStatic
    fun dataPath(name: String, format: String): String {
        val dir = System.getProperty("kobol.data.dir", "data")
        return "$dir/$name.${ext(format)}"
    }

    /** Read every record from an INPUT file into a List (single pass, O(n)). */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun readAll(name: String, format: String, recordClass: Class<*>): List<Any> {
        val file = KobolSequentialFile(
            dataPath(name, format), KobolFileMode.INPUT, fmt(format), recordClass as Class<Any>,
        )
        return file.readAll()
    }

    /** Open a file for OPEN/WRITE/CLOSE statements; stored in the registry under [name]. */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun openFile(name: String, mode: String, format: String, recordClass: Class<*>) {
        val m = when (mode.uppercase()) {
            "OUTPUT" -> KobolFileMode.OUTPUT
            "EXTEND" -> KobolFileMode.EXTEND
            else     -> KobolFileMode.INPUT
        }
        val file = KobolSequentialFile(dataPath(name, format), m, fmt(format), recordClass as Class<Any>)
        // Ensure ./data exists for output files.
        if (m != KobolFileMode.INPUT) File(file.path).absoluteFile.parentFile?.mkdirs()
        file.open()
        openFiles[name]?.close()
        openFiles[name] = file
    }

    /** Write one record to a previously OPENed file. */
    @JvmStatic
    fun write(name: String, record: Any) {
        val file = openFiles[name]
            ?: throw KobolException.IoException("File '$name' is not open for writing")
        file.write(record)
    }

    /** Close and forget a previously OPENed file. */
    @JvmStatic
    fun closeFile(name: String) {
        openFiles.remove(name)?.close()
    }
}
