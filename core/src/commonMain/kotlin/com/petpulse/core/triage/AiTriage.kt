package com.petpulse.core.triage

/**
 * Result of the server-side AI review (backend/supabase/functions/ai-triage). The apps call the
 * function over HTTPS and decode its JSON into this. [merge] mirrors the server's rule so the
 * final urgency can never be lower than the rule-based one, even if a response is tampered with.
 */
data class AiTriageResult(
    val urgency: Urgency,
    val ruleUrgency: Urgency,
    val aiUrgency: Urgency?,
    val raisedByAi: Boolean,
    val reasons: List<String>,
    val photoFindings: List<String>,
    val watchFor: List<String>,
    val note: String?,
    val disclaimer: String = TriageResult.DISCLAIMER,
) {
    companion object {
        fun merge(rule: TriageResult, aiUrgency: Urgency?, aiReasons: List<String>, photoFindings: List<String>, aiWatchFor: List<String>, note: String?): AiTriageResult {
            if (aiUrgency == null) return AiTriageResult(rule.urgency, rule.urgency, null, false, rule.reasons, emptyList(), rule.watchFor, note)
            val urgency = if (aiUrgency.ordinal < rule.urgency.ordinal) aiUrgency else rule.urgency
            return AiTriageResult(
                urgency = urgency,
                ruleUrgency = rule.urgency,
                aiUrgency = aiUrgency,
                raisedByAi = urgency != rule.urgency,
                reasons = (rule.reasons + aiReasons).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8),
                photoFindings = photoFindings.distinct().take(6),
                watchFor = (aiWatchFor + rule.watchFor).distinct().take(6),
                note = note,
            )
        }

        fun urgencyOf(name: String?): Urgency? = Urgency.entries.firstOrNull { it.name == name }
    }
}
