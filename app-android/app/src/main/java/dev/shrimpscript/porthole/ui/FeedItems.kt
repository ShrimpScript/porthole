package dev.shrimpscript.porthole.ui

import dev.shrimpscript.porthole.net.Row as FeedRow

/** One step of a stretch of work: a tool call and, once it is back, its result. */
data class WorkStep(val call: FeedRow, val result: FeedRow?) {
    val failed: Boolean get() = result?.glyph == "✗"
    /** The lines an edit added and removed; the daemon counts them on the result. */
    val diff: dev.shrimpscript.porthole.net.LineDiff? get() = result?.diff
    /** What the step opens: titled by what it did, its command above its output. */
    fun detailRow(): FeedRow {
        val r = result
        val parts = listOfNotNull(
            call.detail.takeIf { it.isNotBlank() && r !== call }?.let { "$ $it" },
            r?.detail?.takeIf { it.isNotBlank() },
        )
        return (r ?: call).copy(text = call.text, detail = parts.joinToString("\n\n"))
    }
}

/**
 * What the feed draws. Rows stay rows, except that a stretch of tool calls and their
 * results - between Claude's words - folds into one line ("Ran 2 commands, edited a file"),
 * and Claude's words are told apart: narration on the way, or the answer.
 */
sealed interface FeedItem {
    /** The span of the feed's own rows this item stands for. */
    val first: Int
    val last: Int

    data class One(
        val row: FeedRow,
        val index: Int,
        /** Claude's words with more work after them in the same turn: on the way, not the answer. */
        val narration: Boolean = false,
        /** Claude's last words of a turn (or so far): copy, listen and share go under them. */
        val answer: Boolean = false,
        /** On a turn line: what the turn's edits added and removed, for its diff chip. */
        val turnDiff: Pair<Int, Int>? = null,
    ) : FeedItem {
        override val first get() = index
        override val last get() = index
    }

    data class Work(val steps: List<WorkStep>, override val first: Int, override val last: Int) : FeedItem {
        val failed: Int get() = steps.count { it.failed }
        val added: Int get() = steps.sumOf { it.diff?.add ?: 0 }
        val removed: Int get() = steps.sumOf { it.diff?.del ?: 0 }
        /** The step still running: called, and no result for it anywhere in the feed. */
        val running: WorkStep? get() = steps.lastOrNull { it.result == null && it.call.kind == "tool" && it.call.toolId.isNotEmpty() }
    }
}

/** Rows that fold into a work line: ordinary tool calls and their results. */
private fun FeedRow.isWork(agentCalls: Set<String>, questions: Set<String>): Boolean = when (kind) {
    "tool" -> agent == null
    "result" -> toolId !in agentCalls && toolId !in questions && !toolId.startsWith("cmd:")
    else -> false
}

/**
 * The feed's rows as items. A call and its result are one step wherever the result
 * landed - an image, a parallel call's result or a queued message can come between them -
 * so a result whose call is in the feed is drawn with it, and an agent's with its card.
 * A question's answer and a command's late reply stay rows of their own.
 */
fun feedItems(rows: List<FeedRow>): List<FeedItem> {
    val agentCalls = rows.asSequence().filter { it.agent != null && it.toolId.isNotEmpty() }.map { it.toolId }.toSet()
    val questions = rows.asSequence().filter { it.kind == "question" && it.toolId.isNotEmpty() }.map { it.toolId }.toSet()
    val calls = rows.asSequence().filter { it.kind == "tool" && it.toolId.isNotEmpty() }.map { it.toolId }.toSet()
    val resultOf = HashMap<String, FeedRow>()
    rows.forEach { if (it.kind == "result" && it.toolId in calls) resultOf.putIfAbsent(it.toolId, it) }
    fun withItsCall(r: FeedRow) = r.kind == "result" && r.toolId.isNotEmpty() && r.toolId in calls
    val out = ArrayList<FeedItem>()
    var i = 0
    while (i < rows.size) {
        val r = rows[i]
        if (withItsCall(r)) { i++; continue }
        if (!r.isWork(agentCalls, questions)) {
            out += FeedItem.One(r, i)
            i++
            continue
        }
        val start = i
        val steps = ArrayList<WorkStep>()
        while (i < rows.size && (rows[i].isWork(agentCalls, questions) || withItsCall(rows[i]))) {
            val x = rows[i]
            when {
                withItsCall(x) -> {}
                x.kind == "tool" -> steps += WorkStep(x, resultOf[x.toolId])
                else -> steps += WorkStep(x, x) // a result whose call came before the loaded history: it stands for itself
            }
            i++
        }
        out += FeedItem.Work(steps, start, i - 1)
    }
    return markWords(out)
}

/**
 * Claude's words before more work in the same turn are narration; the last words of a
 * turn are the answer. A turn ends at its turn line (the CLI writes one for every turn
 * it finishes), at an interruption, at a command, or at the end of the feed - not at a
 * message: one queued while Claude works lands in the middle of the turn.
 */
private fun markWords(items: List<FeedItem>): List<FeedItem> {
    fun endsTurn(it: FeedItem) = it is FeedItem.One &&
        (it.row.kind == "turn" || it.row.kind == "command" || (it.row.kind == "event" && it.row.text == "You interrupted"))
    val out = items.toMutableList()
    var turnAdd = 0
    var turnDel = 0
    for (k in out.indices) {
        val item = out[k]
        if (item is FeedItem.Work) { turnAdd += item.added; turnDel += item.removed }
        if (item is FeedItem.One && item.row.kind == "turn") {
            if (turnAdd + turnDel > 0) out[k] = item.copy(turnDiff = turnAdd to turnDel)
            turnAdd = 0; turnDel = 0
            continue
        }
        if (endsTurn(item)) { turnAdd = 0; turnDel = 0 }
        if (item !is FeedItem.One || item.row.kind != "assistant") continue
        var workAfter = false
        var j = k + 1
        while (j < out.size && !endsTurn(out[j])) {
            val n = out[j]
            if (n is FeedItem.Work || (n is FeedItem.One && (n.row.kind == "tool" || n.row.kind == "question"))) { workAfter = true; break }
            if (n is FeedItem.One && n.row.kind == "assistant") break // later words decide for themselves
            j++
        }
        val laterWords = j < out.size && (out[j] as? FeedItem.One)?.row?.kind == "assistant"
        out[k] = item.copy(narration = workAfter, answer = !workAfter && !laterWords)
    }
    return out
}

/** What a step was: the tool's name when the daemon gave it, else read from the verb. */
fun stepKind(row: FeedRow): String = when (row.tool) {
    "Bash" -> "command"
    "Edit", "MultiEdit", "NotebookEdit" -> "edit"
    "Write" -> "write"
    "Read" -> "read"
    "Grep", "Glob" -> "search"
    "WebSearch" -> "web"
    "WebFetch" -> "fetch"
    "Skill" -> "skill"
    "TodoWrite" -> "plan"
    "" -> when {
        row.text.startsWith("Ran ") -> "command"
        row.text.startsWith("Edited") -> "edit"
        row.text.startsWith("Wrote ") -> "write"
        row.text.startsWith("Read ") -> "read"
        row.text.startsWith("Searched the web") -> "web"
        row.text.startsWith("Searched ") || row.text.startsWith("Globbed ") -> "search"
        row.text.startsWith("Fetched ") -> "fetch"
        row.text.startsWith("Loaded skill") -> "skill"
        row.text.startsWith("Updated the plan") -> "plan"
        else -> "other"
    }
    else -> "other"
}

/** "Ran 2 commands, edited a file": each kind of step once, in the order it first came. */
fun workSummary(steps: List<WorkStep>): String {
    val counts = LinkedHashMap<String, Int>()
    steps.forEach { counts.merge(stepKind(it.call), 1, Int::plus) }
    val parts = counts.map { (kind, n) ->
        fun of(one: String, many: String) = if (n == 1) one else many.replace("#", n.toString())
        when (kind) {
            "command" -> of("ran a command", "ran # commands")
            "edit" -> of("edited a file", "made # edits")
            "write" -> of("created a file", "created # files")
            "read" -> of("read a file", "read # files")
            "search" -> of("searched once", "searched # times")
            "web" -> of("searched the web", "searched the web # times")
            "fetch" -> of("fetched a page", "fetched # pages")
            "skill" -> of("loaded a skill", "loaded # skills")
            "plan" -> of("updated the plan", "updated the plan # times")
            else -> of("used a tool", "used # tools")
        }
    }
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
}
