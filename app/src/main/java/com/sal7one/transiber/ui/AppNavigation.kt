package com.sal7one.transiber.ui

/** Stable page IDs also serve the caption notification/overlay's existing deep links. */
internal enum class MainTab(val page: Int, val label: String, val title: String) {
    CAPTIONS(0, "Captions", "Live captions"),
    TALK(7, "Talk", "Conversation & face to face"),
    TRANSLATE(11, "Translate", "Type to translate"),
    CAMERA(10, "Camera", "Camera & OCR"),
    SETTINGS(3, "Settings", "Settings"),
}

/** Only the visible page is composed. Each tab retains its own setup return path. */
internal class AppNavigation private constructor(
    val tab: MainTab,
    private val stacks: List<List<Int>>,
) {
    val page: Int get() = stacks[tab.ordinal].last()
    val isRoot: Boolean get() = page == tab.page
    val canGoBack: Boolean get() = !isRoot || tab != MainTab.CAPTIONS

    fun select(next: MainTab): AppNavigation =
        if (next == tab) replace(listOf(next.page)) else AppNavigation(next, stacks)

    fun open(next: Int): AppNavigation {
        require(next in 0..14) { "Unknown page: $next" }
        MainTab.entries.firstOrNull { it.page == next }?.let { return select(it) }
        val stack = stacks[tab.ordinal]
        val existing = stack.indexOf(next)
        return replace(if (existing >= 0) stack.take(existing + 1) else stack + next)
    }

    fun back(): AppNavigation = if (!isRoot) replace(stacks[tab.ordinal].dropLast(1))
        else AppNavigation(MainTab.CAPTIONS, stacks.mapIndexed { i, stack ->
            if (i == MainTab.CAPTIONS.ordinal) listOf(MainTab.CAPTIONS.page) else stack
        })

    fun toRoot(): AppNavigation = replace(listOf(tab.page))

    private fun replace(stack: List<Int>) = AppNavigation(tab,
        stacks.mapIndexed { i, previous -> if (i == tab.ordinal) stack else previous })

    // Integers only: safe for rememberSaveable and Activity/process restoration.
    fun save(): List<Int> = listOf(tab.ordinal) + stacks.flatMap { listOf(it.size) + it }

    companion object {
        fun initial(page: Int = 0): AppNavigation {
            val destination = page.coerceIn(0, 14)
            val root = MainTab.entries.firstOrNull { it.page == destination } ?: MainTab.SETTINGS
            val state = AppNavigation(root, MainTab.entries.map { listOf(it.page) })
            return if (destination == root.page) state else state.open(destination)
        }

        fun restore(saved: List<Int>): AppNavigation {
            var offset = 1
            val tab = MainTab.entries.getOrNull(saved.firstOrNull() ?: -1) ?: return initial()
            val stacks = MainTab.entries.map { root ->
                val size = saved.getOrNull(offset++) ?: return initial()
                if (size !in 1..11 || offset + size > saved.size) return initial()
                val stack = saved.subList(offset, offset + size).toList()
                offset += size
                if (stack.first() != root.page || stack.distinct().size != stack.size ||
                    stack.any { it !in 0..14 } || stack.drop(1).any { id -> MainTab.entries.any { it.page == id } }) return initial()
                stack
            }
            return if (offset == saved.size) AppNavigation(tab, stacks) else initial()
        }
    }
}
