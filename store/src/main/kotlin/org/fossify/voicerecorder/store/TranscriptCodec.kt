package org.fossify.voicerecorder.store

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-rolled JSON codec for [Transcript] sidecar files.
 * Schema version 1: see plan file for documented shape.
 */
internal object TranscriptCodec {

    fun encode(t: Transcript): String {
        val segmentsArray = JSONArray()
        for (s in t.segments) {
            segmentsArray.put(
                JSONObject()
                    .put("start_ms", s.startMs)
                    .put("end_ms", s.endMs)
                    .put("text", s.text)
            )
        }
        val obj = JSONObject()
            .put("schema_version", t.schemaVersion)
            .put("recording_uri", t.recordingUri)
            .put("recording_name", t.recordingName)
            .put("engine", t.engine)
            .put("engine_version", t.engineVersion)
            .put("model", t.model)
            .put("language", t.language)
            .put("language_auto_detected", t.languageAutoDetected)
            .put("created_at", t.createdAtIso)
            .put("duration_ms", t.durationMs)
            .put("segments", segmentsArray)
        if (t.modelSha256 != null) obj.put("model_sha256", t.modelSha256)
        if (t.processingMs != null) obj.put("processing_ms", t.processingMs)
        return obj.toString(2)
    }

    fun decode(json: String): Transcript {
        val obj = JSONObject(json)
        val segArr = obj.getJSONArray("segments")
        val segments = ArrayList<TranscriptSegment>(segArr.length())
        for (i in 0 until segArr.length()) {
            val s = segArr.getJSONObject(i)
            segments += TranscriptSegment(
                startMs = s.getLong("start_ms"),
                endMs = s.getLong("end_ms"),
                text = s.getString("text"),
            )
        }
        return Transcript(
            schemaVersion = obj.getInt("schema_version"),
            recordingUri = obj.getString("recording_uri"),
            recordingName = obj.getString("recording_name"),
            engine = obj.getString("engine"),
            engineVersion = obj.optString("engine_version"),
            model = obj.getString("model"),
            modelSha256 = if (obj.has("model_sha256")) obj.optString("model_sha256") else null,
            language = obj.getString("language"),
            languageAutoDetected = obj.optBoolean("language_auto_detected", false),
            createdAtIso = obj.getString("created_at"),
            durationMs = obj.getLong("duration_ms"),
            processingMs = if (obj.has("processing_ms")) obj.getLong("processing_ms") else null,
            segments = segments,
        )
    }
}
