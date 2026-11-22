package dev.lilt.player.library

/**
 * Returns [items] with the element at [from] inserted at [to]. Invalid moves
 * return the original list so callers can skip the database write.
 */
fun <T> movedItem(items: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in items.indices || to !in items.indices) return items
    val result = items.toMutableList()
    val item = result.removeAt(from)
    result.add(to, item)
    return result
}
