package com.alonmeller.mobiledaw.model

/** Undo/redo stacks of whole project snapshots (cheap: audio lives in shared sources). */
class History<T>(private val limit: Int = 200) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    /** Records [before] as the state to return to on undo. */
    fun push(before: T) {
        past.addLast(before)
        if (past.size > limit) past.removeFirst()
        future.clear()
    }

    fun undo(current: T): T? {
        val prev = past.removeLastOrNull() ?: return null
        future.addLast(current)
        return prev
    }

    fun redo(current: T): T? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        return next
    }
}
