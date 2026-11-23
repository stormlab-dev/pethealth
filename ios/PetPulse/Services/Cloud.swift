import Foundation
import PetPulseCore

/// Supabase settings from Info.plist (CFPSupabaseURL, CFPSupabaseAnonKey), filled from build
/// settings CFP_SUPABASE_URL / CFP_SUPABASE_ANON_KEY. Empty means the app runs fully offline.
enum CloudConfig {
    static let url = (Bundle.main.object(forInfoDictionaryKey: "CFPSupabaseURL") as? String ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    static let anonKey = Bundle.main.object(forInfoDictionaryKey: "CFPSupabaseAnonKey") as? String ?? ""
    static var enabled: Bool { url.hasPrefix("https://") && !anonKey.isEmpty }
}

struct CloudError: LocalizedError {
    let message: String
    var code: String? = nil
    var errorDescription: String? { message }
}

/// An anonymous Supabase account for this device (needs "Anonymous sign-ins" enabled in Supabase).
actor CloudSession {
    static let shared = CloudSession()
    private let defaults = UserDefaults.standard

    var userId: String? { defaults.string(forKey: "cloud.userId") }

    func accessToken() async throws -> String {
        if let t = defaults.string(forKey: "cloud.access"), Date().timeIntervalSince1970 < defaults.double(forKey: "cloud.expires") - 60 { return t }
        var json: [String: Any]
        if let refresh = defaults.string(forKey: "cloud.refresh"),
           let j = try? await post("/auth/v1/token?grant_type=refresh_token", ["refresh_token": refresh], bearer: nil) {
            json = j
        } else {
            json = try await post("/auth/v1/signup", [:], bearer: nil)
        }
        guard let access = json["access_token"] as? String, let refresh = json["refresh_token"] as? String,
              let user = json["user"] as? [String: Any], let id = user["id"] as? String else { throw CloudError(message: "Couldn't sign in to the cloud.") }
        defaults.set(access, forKey: "cloud.access")
        defaults.set(refresh, forKey: "cloud.refresh")
        defaults.set(Date().timeIntervalSince1970 + ((json["expires_in"] as? Double) ?? 3600), forKey: "cloud.expires")
        defaults.set(id, forKey: "cloud.userId")
        return access
    }

    func post(_ path: String, _ body: [String: Any], bearer: String?) async throws -> [String: Any] {
        guard CloudConfig.enabled, let url = URL(string: CloudConfig.url + path) else { throw CloudError(message: "Cloud features aren't set up in this build.", code: "NO_CLOUD") }
        var req = URLRequest(url: url, timeoutInterval: 90)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(CloudConfig.anonKey, forHTTPHeaderField: "apikey")
        req.setValue("Bearer \(bearer ?? CloudConfig.anonKey)", forHTTPHeaderField: "Authorization")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: req)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw CloudError(message: (json["error"] as? String) ?? (json["msg"] as? String) ?? "Request failed (\(status))", code: json["code"] as? String)
        }
        return json
    }
}

/// Calls the ai-triage server function. The final urgency is merged again on the device so it can
/// never be lower than the rule-based result.
enum AiTriageClient {
    static var available: Bool { CloudConfig.enabled }

    static func check(pet: PetRecord, symptomLabels: [String], severity: String, hoursSinceOnset: Int, notes: String,
                      rule: TriageResult, photoBase64: String?) async throws -> AiTriageResult {
        let age = PetAge.shared.years(birthDate: pet.birthDate, today: core.today())?.doubleValue
        let weight = pet.weights.max { $0.epochDay < $1.epochDay }?.kilograms
        var body: [String: Any] = [
            "pet": ["species": pet.species, "sex": pet.sex,
                    "breed": pet.breed.map { $0 as Any } ?? NSNull(),
                    "ageYears": age.map { $0 as Any } ?? NSNull(),
                    "weightKg": weight.map { $0 as Any } ?? NSNull()] as [String: Any],
            "symptoms": symptomLabels, "severity": severity, "hoursSinceOnset": hoursSinceOnset, "notes": notes,
            "ruleResult": ["urgency": rule.urgency.name, "reasons": rule.reasons, "watchFor": rule.watchFor],
        ]
        if let photoBase64 { body["photo"] = ["mediaType": "image/jpeg", "data": photoBase64] }
        let token = try await CloudSession.shared.accessToken()
        let json = try await CloudSession.shared.post("/functions/v1/ai-triage", body, bearer: token)
        func list(_ k: String) -> [String] { json[k] as? [String] ?? [] }
        return AiTriageResult.companion.merge(
            rule: rule,
            aiUrgency: AiTriageResult.companion.urgencyOf(name: (json["aiUrgency"] as? String) ?? (json["urgency"] as? String)),
            aiReasons: list("reasons"), photoFindings: list("photoFindings"), aiWatchFor: list("watchFor"),
            note: json["note"] as? String)
    }
}
