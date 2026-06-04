package dev.kobol.stdlib

import java.util.UUID

object KobolUuid {
    @JvmStatic fun generate(): UUID          = UUID.randomUUID()
    @JvmStatic fun fromText(s: String): UUID = UUID.fromString(s)
    @JvmStatic fun nil(): UUID               = UUID(0L, 0L)
    @JvmStatic fun toText(u: UUID): String   = u.toString()
}
