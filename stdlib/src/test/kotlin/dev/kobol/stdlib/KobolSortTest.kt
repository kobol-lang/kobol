package dev.kobol.stdlib

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KobolSortTest {

    // ── sort / sortDescending ────────────────────────────────────────────

    @Test fun `sort ascending`() {
        assertEquals(listOf(1, 2, 3), KobolSort.sort(listOf(3, 1, 2)))
    }

    @Test fun `sortDescending`() {
        assertEquals(listOf(3, 2, 1), KobolSort.sortDescending(listOf(1, 3, 2)))
    }

    // ── sortBy / sortByDescending ─────────────────────────────────────────

    @Test fun `sortBy key ascending`() {
        val words = listOf("banana", "apple", "cherry")
        assertEquals(listOf("apple", "banana", "cherry"), KobolSort.sortBy(words) { it })
    }

    @Test fun `sortByDescending key`() {
        val words = listOf("banana", "apple", "cherry")
        assertEquals(listOf("cherry", "banana", "apple"), KobolSort.sortByDescending(words) { it })
    }

    @Test fun `sortBy length`() {
        val words = listOf("bb", "aaa", "c")
        assertEquals(listOf("c", "bb", "aaa"), KobolSort.sortBy(words) { it.length })
    }

    // ── sortStrings ──────────────────────────────────────────────────────

    @Test fun `sortStrings case-sensitive by default`() {
        val result = KobolSort.sortStrings(listOf("banana", "Apple", "cherry"))
        assertEquals("Apple", result.first())
    }

    @Test fun `sortStrings case-insensitive`() {
        val result = KobolSort.sortStrings(listOf("banana", "Apple", "cherry"), ignoreCase = true)
        assertEquals("Apple", result.first())
    }

    // ── sortWith ─────────────────────────────────────────────────────────

    @Test fun `sortWith custom comparator`() {
        val result = KobolSort.sortWith(listOf("bb", "aaa", "c"), compareBy { it.length })
        assertEquals(listOf("c", "bb", "aaa"), result)
    }

    // ── partition ────────────────────────────────────────────────────────

    @Test fun `partition splits by predicate`() {
        val (evens, odds) = KobolSort.partition(listOf(1, 2, 3, 4, 5)) { it % 2 == 0 }
        assertEquals(listOf(2, 4), evens)
        assertEquals(listOf(1, 3, 5), odds)
    }

    // ── groupBy ──────────────────────────────────────────────────────────

    @Test fun `groupBy key`() {
        val words = listOf("apple", "ant", "banana", "bat")
        val grouped = KobolSort.groupBy(words) { it.first() }
        assertEquals(listOf("apple", "ant"), grouped['a'])
        assertEquals(listOf("banana", "bat"), grouped['b'])
    }

    // ── original list is not mutated ─────────────────────────────────────

    @Test fun `sort does not mutate original`() {
        val original = listOf(3, 1, 2)
        KobolSort.sort(original)
        assertEquals(listOf(3, 1, 2), original)
    }
}
