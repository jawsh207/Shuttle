package app.shuttle.assistant.chats

import java.io.File

/**
 * Saved chats: one small text file per chat in the app's private storage.
 *
 * A chat is kept as what was asked and what came back, as plain text. Pending cards (a text
 * waiting for Send, an access waiting for Allow) are saved as their text only, never as
 * something that can still be acted on, so reopening an old chat can't send a message or place
 * a call. Files live in the app's private folder, which only Shuttle can read, and Android
 * backup is off for the app, so chats never leave the phone.
 *
 * The file format is a header and one line per exchange, with tabs, newlines and backslashes
 * escaped. It's plain Kotlin with no Android or library dependencies, so it's covered by JVM
 * unit tests.
 */
class ChatStore(private val dir: File) {

    /** MODEL is an answer the model wrote, so a reopened chat still treats "continue" as a follow-up. */
    enum class Kind { DONE, FAILED, CONFIRM, ACCESS, MODEL }

    data class Line(val asked: String, val kind: Kind, val text: String)

    data class Chat(
        val id: String,
        val title: String,
        val created: Long,
        val updated: Long,
        val lines: List<Line>,
    )

    /** All saved chats, most recently used first. Unreadable files are skipped. */
    fun list(): List<Chat> =
        (dir.listFiles { f -> f.name.endsWith(SUFFIX) } ?: emptyArray())
            .mapNotNull { f -> runCatching { decode(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updated }

    fun save(chat: Chat) {
        dir.mkdirs()
        val file = File(dir, chat.id + SUFFIX)
        // Write then rename, so a crash mid-write never leaves half a chat.
        val tmp = File(dir, chat.id + ".tmp")
        tmp.writeText(encode(chat))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun delete(id: String) {
        File(dir, id + SUFFIX).delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val SUFFIX = ".chat"
        private const val MAGIC = "shuttle-chat 1"
        private const val TITLE_LENGTH = 60

        /** The first thing asked, shortened: what a chat is called in the list. */
        fun titleFor(lines: List<Line>): String {
            val first = lines.firstOrNull { it.asked.isNotBlank() }?.asked?.trim() ?: return "New chat"
            return if (first.length <= TITLE_LENGTH) first else first.take(TITLE_LENGTH - 1).trimEnd() + "…"
        }

        /**
         * Chats containing [query] in what was asked or answered, each with the first matching
         * line as a snippet. An empty query returns every chat with its latest answer.
         */
        fun search(chats: List<Chat>, query: String): List<Pair<Chat, String>> {
            val q = query.trim()
            if (q.isEmpty()) return chats.map { it to (it.lines.lastOrNull()?.text ?: "") }
            return chats.mapNotNull { chat ->
                val hit = chat.lines.firstOrNull { it.asked.contains(q, true) || it.text.contains(q, true) }
                when {
                    hit != null -> chat to (if (hit.text.contains(q, true)) hit.text else hit.asked)
                    chat.title.contains(q, true) -> chat to (chat.lines.lastOrNull()?.text ?: "")
                    else -> null
                }
            }
        }

        fun encode(chat: Chat): String = buildString {
            append(MAGIC).append('\n')
            append(escape(chat.id)).append('\n')
            append(escape(chat.title)).append('\n')
            append(chat.created).append('\n')
            append(chat.updated).append('\n')
            for (line in chat.lines) {
                append(line.kind.name).append('\t').append(escape(line.asked)).append('\t').append(escape(line.text)).append('\n')
            }
        }

        fun decode(text: String): Chat? {
            val rows = text.split('\n')
            if (rows.size < 5 || rows[0] != MAGIC) return null
            val lines = rows.drop(5).filter { it.isNotEmpty() }.map { row ->
                val parts = row.split('\t')
                require(parts.size == 3)
                Line(unescape(parts[1]), Kind.valueOf(parts[0]), unescape(parts[2]))
            }
            return Chat(unescape(rows[1]), unescape(rows[2]), rows[3].toLong(), rows[4].toLong(), lines)
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unescape(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> append('\t')
                        'n' -> append('\n')
                        else -> append(s[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}
