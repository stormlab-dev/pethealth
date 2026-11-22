import Foundation
import SwiftData
import UserNotifications
import PetPulseCore

/// Dose, vaccine and refill notifications. iOS allows 64 pending local notifications, so each
/// medication gets its next few doses queued; the app tops them up whenever it opens.
@MainActor
final class Reminders {
    static let shared = Reminders()
    var container: ModelContainer?
    private let center = UNUserNotificationCenter.current()
    private let dosesPerMedication = 6

    func requestPermission() async {
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
    }

    nonisolated func registerCategories() {
        let given = UNNotificationAction(identifier: "GIVEN", title: "Given")
        let skip = UNNotificationAction(identifier: "SKIP", title: "Skip")
        let dose = UNNotificationCategory(identifier: "DOSE", actions: [given, skip], intentIdentifiers: [])
        UNUserNotificationCenter.current().setNotificationCategories([dose])
    }

    func rescheduleAll() async {
        guard let context = container?.mainContext else { return }
        center.removeAllPendingNotificationRequests()
        let meds = (try? context.fetch(FetchDescriptor<MedicationRecord>())) ?? []
        for med in meds { schedule(med) }
        scheduleHealthAlerts(context)
    }

    func schedule(_ med: MedicationRecord) {
        cancel(med)
        guard med.remindersEnabled, let pet = med.pet else { return }
        var after = core.nowLocal()
        for i in 0..<dosesPerMedication {
            guard let next = DoseScheduler.shared.nextDose(med: med.model, after: after) else { break }
            after = next
            let content = UNMutableNotificationContent()
            content.title = "Time for \(pet.name)'s \(med.name)"
            content.body = med.dosage
            content.sound = .default
            content.categoryIdentifier = "DOSE"
            content.userInfo = ["med": String(med.uid), "at": String(next.swiftDate.epochMillis)]
            let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: next.swiftDate)
            let request = UNNotificationRequest(identifier: "dose-\(med.uid)-\(i)", content: content,
                                                trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false))
            center.add(request)
        }
    }

    func cancel(_ med: MedicationRecord) {
        center.removePendingNotificationRequests(withIdentifiers: (0..<dosesPerMedication).map { "dose-\(med.uid)-\($0)" })
    }

    /// One morning alert for anything overdue or about to run out.
    private func scheduleHealthAlerts(_ context: ModelContext) {
        let today = core.today()
        var lines: [String] = []
        for pet in (try? context.fetch(FetchDescriptor<PetRecord>())) ?? [] {
            let due = VaccineTracker.shared.latestByName(all: pet.vaccines.map(\.model))
                .filter { let s = VaccineTracker.shared.status(v: $0, today: today).name; return s == "OVERDUE" || s == "DUE_SOON" }
            if !due.isEmpty { lines.append("\(pet.name): \(due.map(\.name).joined(separator: ", ")) due") }
            for med in pet.medications {
                if let f = RefillPredictor.shared.forecast(med: med.model, today: today, leadDays: 7), f.shouldReorder {
                    lines.append("\(pet.name)'s \(med.name): about \(f.daysLeft) days left")
                }
            }
        }
        guard !lines.isEmpty else { return }
        let content = UNMutableNotificationContent()
        content.title = "PetPulse health check"
        content.body = lines.joined(separator: "\n")
        var comps = DateComponents()
        comps.hour = 9
        center.add(UNNotificationRequest(identifier: "health-check", content: content,
                                         trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)))
    }

    func handle(_ response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let context = container?.mainContext,
              let medId = (info["med"] as? String).flatMap(Int64.init),
              let at = (info["at"] as? String).flatMap(Int64.init) else { return }
        let action = response.actionIdentifier
        guard action == "GIVEN" || action == "SKIP" else { return }
        let meds = (try? context.fetch(FetchDescriptor<MedicationRecord>(predicate: #Predicate { $0.uid == medId }))) ?? []
        guard let med = meds.first else { return }
        med.recordDose(taken: action == "GIVEN", scheduledFor: Date(epochMillis: at))
        try? context.save()
    }
}
