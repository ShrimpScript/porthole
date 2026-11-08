package dev.shrimpscript.porthole.net

import org.json.JSONObject

/**
 * The questions agents left waiting on the person, read by the computer from each
 * project's notes (`portholed inbox`). Answers go back to that project's decisions file,
 * and to a Claude Code session running there.
 */
data class InboxItem(
    val id: String,
    /** The note it was found in, relative to the project. */
    val file: String,
    val line: Int,
    val text: String,
    /** "A | B | C" in the note: one tap answers. Empty: answered in words. */
    val options: List<String> = emptyList(),
    /** The note's own suggestion, written in [brackets]. */
    val suggested: String = "",
    /** When it was written, ISO-8601. */
    val since: String = "",
    /** [text] less its choices and its [suggested], when the computer could tell them apart. */
    val question: String = "",
) {
    /** What the card shows above the choices. */
    val asked: String get() = question.ifBlank { text }
}

data class InboxProject(
    val name: String,
    val dir: String,
    /** A Claude Code session runs in this folder: an answer is also typed to it. */
    val live: Boolean,
    val items: List<InboxItem>,
    val machineId: String = "",
    /** The computer's name, when the phone has several. */
    val machine: String = "",
)

data class Inbox(
    /** The computer has folders to read; when not, the screen says how to add them. */
    val configured: Boolean,
    val projects: List<InboxProject>,
) {
    val count: Int get() = projects.sumOf { it.items.size }

    companion object {
        fun parse(obj: JSONObject): Inbox {
            val ps = obj.optJSONArray("projects")
            return Inbox(
                configured = obj.optBoolean("configured"),
                projects = buildList {
                    for (i in 0 until (ps?.length() ?: 0)) {
                        val p = ps!!.getJSONObject(i)
                        val its = p.optJSONArray("items")
                        add(
                            InboxProject(
                                name = p.optString("name"),
                                dir = p.optString("dir"),
                                live = p.optBoolean("live"),
                                items = buildList {
                                    for (j in 0 until (its?.length() ?: 0)) {
                                        val it = its!!.getJSONObject(j)
                                        val opts = it.optJSONArray("options")
                                        add(
                                            InboxItem(
                                                id = it.optString("id"),
                                                file = it.optString("file"),
                                                line = it.optInt("line"),
                                                text = it.optString("text"),
                                                options = buildList { for (k in 0 until (opts?.length() ?: 0)) add(opts!!.getString(k)) },
                                                suggested = it.optString("default"),
                                                since = it.optString("since"),
                                                question = it.optString("question"),
                                            ),
                                        )
                                    }
                                },
                            ),
                        )
                    }
                }.filter { it.items.isNotEmpty() && it.name.isNotBlank() },
            )
        }

        /**
         * Every computer's inbox as one, each project saying which computer it is on. Null
         * until one of them has answered; configured when any of them is.
         */
        fun merge(parts: List<Triple<String, String, Inbox?>>): Inbox? {
            val known = parts.filter { it.third != null }
            if (known.isEmpty()) return null
            val several = parts.size > 1
            return Inbox(
                configured = known.any { it.third!!.configured },
                projects = known.flatMap { (id, name, inbox) ->
                    inbox!!.projects.map { it.copy(machineId = id, machine = if (several) name else "") }
                },
            )
        }
    }
}

/** How an answer went: where it was recorded, which session was told, or why it was not. */
data class InboxAnswered(val item: String, val decisions: String, val session: String, val error: String, val dir: String = "") {
    companion object {
        fun parse(obj: JSONObject) = InboxAnswered(
            dir = obj.optString("dir"),
            item = obj.optString("item"),
            decisions = obj.optString("decisions"),
            session = obj.optString("session"),
            error = obj.optString("error"),
        )
    }
}
