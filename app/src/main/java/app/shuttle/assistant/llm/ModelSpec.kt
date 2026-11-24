package app.shuttle.assistant.llm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Which model Shuttle uses, and where to get it.
 *
 * The project picks the model; users never choose or configure one. The choice is written in
 * models/manifest.json in this repository, which the app reads from GitHub, so the model can
 * be updated (a newer Granite, a better build) without an app update. A copy of the current
 * choice is built in ([BUILT_IN]), so a phone that can't reach GitHub still knows what to
 * download. Every download is checked against the [sha256] here before it's used.
 */
data class ModelSpec(
    val id: String,
    val name: String,
    /** Prompt format; only "granite" ([GranitePrompt]) exists so far. */
    val format: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val license: String,
) {
    val fileName: String get() = "$id.gguf"

    /** "2.2 GB" */
    val sizeLabel: String get() = "%.1f GB".format(size / 1_000_000_000.0)

    companion object {
        const val MANIFEST_URL = "https://raw.githubusercontent.com/jawsh207/Shuttle/main/models/manifest.json"

        val BUILT_IN = ModelSpec(
            id = "granite-4.2-3b-q4_k_m",
            name = "Granite 4.2 3B",
            format = "granite",
            url = "https://huggingface.co/ibm-granite/granite-4.2-3b-GGUF/resolve/main/granite-4.2-3b-Q4_K_M.gguf",
            sha256 = "e0406663965846ae22a403456eb826ccce5f450840491f71952f18a7cb78e7d5",
            size = 2_244_011_552,
            license = "Apache-2.0",
        )

        /** Reads the manifest from GitHub; null when offline or unreadable. Call off the main thread. */
        fun fetchManifest(): ModelSpec? = runCatching {
            val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            try {
                if (conn.responseCode != 200) return null
                parse(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

        fun encode(spec: ModelSpec): String = JSONObject().put(
            "model",
            JSONObject()
                .put("id", spec.id).put("name", spec.name).put("format", spec.format).put("url", spec.url)
                .put("sha256", spec.sha256).put("size", spec.size).put("license", spec.license),
        ).toString()

        fun parse(json: String): ModelSpec? = runCatching {
            val m = JSONObject(json).getJSONObject("model")
            ModelSpec(
                id = m.getString("id"),
                name = m.getString("name"),
                format = m.getString("format"),
                url = m.getString("url"),
                sha256 = m.getString("sha256").lowercase(),
                size = m.getLong("size"),
                license = m.getString("license"),
            ).takeIf { it.format == "granite" && it.url.startsWith("https://") && it.sha256.length == 64 }
        }.getOrNull()
    }
}
