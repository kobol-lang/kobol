package dev.kobol.stdlib

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import org.bson.conversions.Bson

/**
 * MongoDB document-store bridge for Kobol programs.
 *
 * Kobol syntax:
 *   NOSQL CONNECT TO "mongodb://localhost:27017" DATABASE "mydb"
 *   NOSQL DISCONNECT
 *
 *   FIND IN "orders" WHERE status = "PENDING" GIVING result-list
 *   FIND ONE IN "users" WHERE user-id = id-var GIVING user-doc
 *   SAVE TO "orders" USING order-map
 *   DELETE FROM "orders" WHERE order-id = id-var
 *   COUNT IN "orders" WHERE status = "PENDING" GIVING total
 *
 * The WHERE filter compiles to MongoDB Filters.* calls at code-generation time.
 * SAVE TO expects a MAP OF TEXT TO X variable; FIND returns List<Map<String,Any?>>.
 *
 * Add the driver to your project's kobol.toml [dependencies]:
 *   "org.mongodb:mongodb-driver-sync" = "5.2.0"
 */
object KobolMongo {

    // ThreadLocal is safe with virtual threads (Project Loom): each virtual thread
    // has its own ThreadLocal slot, so concurrent ASYNC PROCEDUREs can use separate
    // connections without contention.
    private val clientRef: ThreadLocal<MongoClient?> = ThreadLocal.withInitial { null }
    private val dbRef: ThreadLocal<MongoDatabase?> = ThreadLocal.withInitial { null }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @JvmStatic fun connect(url: String, databaseName: String) {
        val c = MongoClients.create(url)
        clientRef.set(c)
        dbRef.set(c.getDatabase(databaseName))
    }

    @JvmStatic fun disconnect() {
        clientRef.get()?.close()
        clientRef.set(null)
        dbRef.set(null)
    }

    // ------------------------------------------------------------------
    // FIND — returns all matching documents as List<Map<String,Any?>>
    // ------------------------------------------------------------------

    @JvmStatic fun find(collectionName: String, filter: Bson?): java.util.List<*> {
        val coll = collection(collectionName)
        val cursor = if (filter != null) coll.find(filter) else coll.find()
        val result = java.util.ArrayList<java.util.Map<String, Any?>>()
        cursor.map { documentToMap(it) }.into(result)
        @Suppress("UNCHECKED_CAST")
        return result as java.util.List<*>
    }

    // ------------------------------------------------------------------
    // FIND ONE — returns the first matching document or null
    // ------------------------------------------------------------------

    @JvmStatic fun findOne(collectionName: String, filter: Bson?): Any? {
        val coll = collection(collectionName)
        val doc = if (filter != null) coll.find(filter).first() else coll.find().first()
        return doc?.let { documentToMap(it) }
    }

    // ------------------------------------------------------------------
    // SAVE — insert a new document from a Kobol MAP variable
    // ------------------------------------------------------------------

    @JvmStatic fun save(collectionName: String, document: java.util.Map<*, *>) {
        val doc = Document()
        @Suppress("UNCHECKED_CAST")
        for (entry in (document as java.util.Map<String, Any?>).entrySet()) doc[entry.key] = entry.value
        collection(collectionName).insertOne(doc)
    }

    // ------------------------------------------------------------------
    // UPSERT (SAVE TO … UPSERT) — replace-or-insert by _id
    // ------------------------------------------------------------------

    @JvmStatic fun upsert(collectionName: String, filter: Bson, document: java.util.Map<*, *>) {
        val doc = Document()
        @Suppress("UNCHECKED_CAST")
        for (entry in (document as java.util.Map<String, Any?>).entrySet()) doc[entry.key] = entry.value
        collection(collectionName).replaceOne(
            filter,
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    // ------------------------------------------------------------------
    // DELETE — remove matching documents; returns count deleted
    // ------------------------------------------------------------------

    @JvmStatic fun delete(collectionName: String, filter: Bson): Long =
        collection(collectionName).deleteMany(filter).deletedCount

    // ------------------------------------------------------------------
    // COUNT — count matching documents
    // ------------------------------------------------------------------

    @JvmStatic fun count(collectionName: String, filter: Bson?): Long {
        val coll = collection(collectionName)
        return if (filter != null) coll.countDocuments(filter) else coll.countDocuments()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun collection(name: String) =
        (dbRef.get() ?: error("KobolMongo: no connection — use NOSQL CONNECT TO first"))
            .getCollection(name)

    /** Convert a BSON Document to a plain LinkedHashMap for use in Kobol MAP variables. */
    private fun documentToMap(doc: Document): java.util.Map<String, Any?> {
        val map = java.util.LinkedHashMap<String, Any?>()
        doc.forEach { k, v -> map[k] = v }
        @Suppress("UNCHECKED_CAST")
        return map as java.util.Map<String, Any?>
    }
}
