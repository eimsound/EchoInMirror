package com.eimsound.daw.utils

import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.io.path.absolutePathString

interface UndoableAction {
    suspend fun undo(): Boolean
    suspend fun execute(): Boolean
    val name: String
    val icon: ImageVector
    fun merge(other: UndoableAction): UndoableAction? = null
}

interface Restorable {
    suspend fun restore(path: String)
    suspend fun store(path: String)
}

abstract class ReversibleAction(private val reversed: Boolean = false) : UndoableAction {
    override suspend fun undo() = perform(reversed)
    override suspend fun execute() = perform(!reversed)
    protected abstract suspend fun perform(isForward: Boolean): Boolean
}

abstract class ListElementMoveAction<T>(private val index: Int, private val from: MutableList<T>, private val to: MutableList<T>,
                                        private var toIndex: Int = -1): UndoableAction {
    override suspend fun execute(): Boolean {
        if (index < 0 || index >= from.size) return false
        val elm = from.removeAt(index)
        if (toIndex == -1) {
            to.add(elm)
            toIndex = to.size - 1
        } else to.add(toIndex, elm)
        return true
    }

    override suspend fun undo(): Boolean {
        if (toIndex < 0 || toIndex >= to.size || index < 0 || index >= from.size) return false
        val elm = to.removeAt(toIndex)
        from.add(index, elm)
        return true
    }
}

abstract class ListAddOrRemoveAction<T>(private val target: T, private val list: MutableList<T>,
                             private val isDelete: Boolean, private var index: Int = -1): UndoableAction {
    private var restorablePath: String? = null

    override suspend fun execute(): Boolean {
        if (isDelete) {
            index = list.indexOf(target)
            remove()
        } else {
            if (index == -1) {
                list.add(target)
                index = list.size - 1
            } else list.add(index, target)
            if (target is Restorable && restorablePath != null) target.restore(restorablePath!!)
        }
        return true
    }

    override suspend fun undo(): Boolean {
        if (isDelete) {
            list.add(index, target)
            if (target is Restorable && restorablePath != null) target.restore(restorablePath!!)
        } else remove()
        return true
    }

    private suspend fun remove() {
        list.remove(target)
        if (target is Restorable) withContext(Dispatchers.IO) {
            val dir = createTempDirectory("undo")
            Files.createDirectories(dir)
            restorablePath = dir.absolutePathString()
            target.store(restorablePath!!)
        }
        if (target is AutoCloseable) target.close()
    }
}

abstract class ListReplaceAction<T>(private val target: T, private val list: MutableList<T>,
                                        private val index: Int): UndoableAction {
    private var old: T? = null
    private var isReplaced = false

    override suspend fun execute(): Boolean {
        old = list.getOrNull(index)?.apply { if (this is AutoCloseable) this.close() }
        list[index] = target
        if (isReplaced) {
            target.let { if (it is Restorable) it.restore("") } // TODO: recover path
        } else isReplaced = true
        return true
    }

    override suspend fun undo(): Boolean {
        if (list.getOrNull(index) != target) return false
        val o = old ?: return false
        target.let { if (it is AutoCloseable) it.close() }
        list[index] = o
        if (o is Restorable) o.restore("") // TODO: recover path
        return true
    }
}

class UndoableActionExecuteException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface UndoManager {
    val actions: List<UndoableAction>
    val limit: Int
    val cursor: Int
    val errorHandlers: MutableSet<(UndoableActionExecuteException) -> Unit>
    suspend fun undo(steps: Int = 1): Boolean
    suspend fun redo(steps: Int = 1): Boolean
    suspend fun execute(action: UndoableAction): Boolean
    suspend fun reset(): Boolean
    fun clear()
}
