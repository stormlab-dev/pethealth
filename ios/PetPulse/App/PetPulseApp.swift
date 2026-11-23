import SwiftUI
import SwiftData
import UserNotifications

@main
struct PetPulseApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    let container: ModelContainer = {
        do {
            return try ModelContainer(for: PetRecord.self, SymptomRecord.self, WeightRecord.self, VaccineRecord.self,
                                      MedicationRecord.self, DoseRecord.self, AppointmentRecord.self, ClinicLinkRecord.self,
                                      MediaRecord.self)
        } catch {
            fatalError("Could not open the CuteFamilyPet database: \(error)")
        }
    }()

    var body: some Scene {
        WindowGroup {
            PetListView()
                .task {
                    await PlanStore.shared.start()
                    Ads.shared.start()
                    MediaFiles.pruneOrphans(keeping: (try? container.mainContext.fetch(FetchDescriptor<MediaRecord>()))?.map(\.fileName) ?? [])
                    Reminders.shared.container = container
                    await Reminders.shared.requestPermission()
                    await Reminders.shared.rescheduleAll()
                }
        }
        .modelContainer(container)
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        Reminders.shared.registerCategories()
        return true
    }

    // Show reminders even when the app is open.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    // "Given" / "Skip" buttons on a dose reminder.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        await Reminders.shared.handle(response)
    }
}
