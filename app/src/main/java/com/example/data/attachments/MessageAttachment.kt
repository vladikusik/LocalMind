package com.example.data.attachments

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MessageAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val mimeType: String,
    val localPath: String,
    val isImage: Boolean,
    val sizeBytes: Long,
    val extractedText: String? = null
) {
    companion object {
        fun encodeList(list: List<MessageAttachment>): String? {
            if (list.isEmpty()) return null
            val array = JSONArray()
            list.forEach { item ->
                array.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("displayName", item.displayName)
                        put("mimeType", item.mimeType)
                        put("localPath", item.localPath)
                        put("isImage", item.isImage)
                        put("sizeBytes", item.sizeBytes)
                        if (item.extractedText != null) {
                            put("extractedText", item.extractedText)
                        }
                    }
                )
            }
            return array.toString()
        }

        fun decodeList(raw: String?): List<MessageAttachment> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        add(
                            MessageAttachment(
                                id = obj.optString("id", UUID.randomUUID().toString()),
                                displayName = obj.optString("displayName", "file"),
                                mimeType = obj.optString("mimeType", "application/octet-stream"),
                                localPath = obj.optString("localPath", ""),
                                isImage = obj.optBoolean("isImage", false),
                                sizeBytes = obj.optLong("sizeBytes", 0L),
                                extractedText = obj.optString("extractedText").takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
