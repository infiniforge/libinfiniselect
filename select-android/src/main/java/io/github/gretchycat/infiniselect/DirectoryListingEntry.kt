package io.github.gretchycat.infiniselect

import org.json.JSONArray
import org.json.JSONObject

/** Version-1 directory-listing JSON record. Location is authoritative, never a read grant. */
data class DirectoryListingEntry(
    val uri: String,
    val name: String,
    val label: String,
    val mime: String,
    val directory: Boolean,
    val size: Long?,
    val modified: Long,
) {
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("uri", uri)
            put("name", name)
            put("label", label)
            put("mime", mime)
            put("directory", directory)
            put("size", size ?: -1L)
            put("modified", modified)
        }

    companion object {
        fun decodePage(json: String): List<DirectoryListingEntry> {
            require(json.toByteArray(Charsets.UTF_8).size <= 384 * 1024) {
                "Directory page exceeds the relay limit"
            }
            val rows = JSONArray(json)
            require(rows.length() <= 256) { "Too many entries in directory page" }
            return (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val uri = row.getString("uri")
                require(java.net.URI(uri).isAbsolute) {
                    "Directory entry needs an authoritative URI"
                }
                val name = row.getString("name")
                DirectoryListingEntry(
                    uri,
                    name,
                    row.optString("label", name),
                    row.getString("mime"),
                    row.getBoolean("directory"),
                    row.optLong("size", -1).takeIf { it >= 0 },
                    row.optLong("modified", 0),
                )
            }
        }
    }
}
