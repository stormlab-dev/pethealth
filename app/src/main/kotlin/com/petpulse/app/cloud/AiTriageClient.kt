package com.petpulse.app.cloud

import com.petpulse.core.model.Pet
import com.petpulse.core.model.Severity
import com.petpulse.core.triage.AiTriageResult
import com.petpulse.core.triage.TriageResult
import org.json.JSONArray
import org.json.JSONObject

/** Calls the ai-triage server function (backend/supabase/functions/ai-triage). */
class AiTriageClient(private val session: CloudSession) {
    val available get() = CloudConfig.enabled

    suspend fun check(
        pet: Pet, ageYears: Double?, weightKg: Double?, symptomLabels: List<String>, severity: Severity,
        hoursSinceOnset: Double, notes: String, rule: TriageResult, photoJpegBase64: String?,
    ): AiTriageResult {
        val body = JSONObject()
            .put("pet", JSONObject().put("species", pet.species.name).put("sex", pet.sex.name).put("breed", pet.breed)
                .put("ageYears", ageYears ?: JSONObject.NULL).put("weightKg", weightKg ?: JSONObject.NULL))
            .put("symptoms", JSONArray(symptomLabels))
            .put("severity", severity.name)
            .put("hoursSinceOnset", hoursSinceOnset)
            .put("notes", notes)
            .put("ruleResult", JSONObject().put("urgency", rule.urgency.name).put("reasons", JSONArray(rule.reasons)).put("watchFor", JSONArray(rule.watchFor)))
        if (photoJpegBase64 != null) body.put("photo", JSONObject().put("mediaType", "image/jpeg").put("data", photoJpegBase64))

        val json = session.post("/functions/v1/ai-triage", body, session.accessToken())
        fun list(k: String) = json.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        // Merge again on the device so the result can never be less urgent than the rules.
        return AiTriageResult.merge(
            rule = rule,
            aiUrgency = AiTriageResult.urgencyOf(json.optString("aiUrgency").ifEmpty { json.optString("urgency") }),
            aiReasons = list("reasons"),
            photoFindings = list("photoFindings"),
            aiWatchFor = list("watchFor"),
            note = json.optString("note").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}
