import SwiftUI
import PetPulseCore

struct PlansView: View {
    @ObservedObject private var plans = PlanStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var message: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Your plan: \(plans.planName)\(plans.inTrial ? " (free trial)" : "")").font(.headline)
                    if let d = plans.expiresAt { Text("\(plans.inTrial ? "Trial ends" : "Renews or ends") \(d.formatted(date: .abbreviated, time: .omitted))").font(.footnote) }
                    if plans.isDemo { Text("Demo store: purchases are simulated and nothing is charged.").font(.footnote).foregroundStyle(.secondary) }
                    if let message { Text(message).foregroundStyle(Color.accentColor) }
                    Button("Restore purchases") { Task { await plans.restore(); message = "Purchases restored." } }
                    if plans.isDemo { Button("Reset demo") { plans.resetDemo(); message = nil } }
                }
                planSection("Premium", plan: "PREMIUM", perks: ["Unlimited photos and videos, clips up to 3 minutes", "AI symptom checker with photo analysis", "Vet-ready health reports", "No ads"],
                            ids: ["cfp_premium_yearly", "cfp_premium_monthly"])
                planSection("Family", plan: "FAMILY", perks: ["Everything in Premium", "Share with up to 6 people", "Unlimited pets"],
                            ids: ["cfp_family_yearly", "cfp_family_monthly"])
                Section("Extras, bought once") {
                    ForEach(Catalog.shared.extras, id: \.id) { p in
                        productRow(p, owned: plans.owned.contains(p.id) && !p.id.hasPrefix("cfp_reel") && p.id != "cfp_contest_boost")
                    }
                }
                Section {
                    ForEach(Catalog.shared.prints, id: \.id) { p in productRow(p, owned: false) }
                } header: { Text("Prints, shipped to your door") } footer: {
                    Text("Subscriptions renew automatically through the App Store until cancelled in Settings → Apple ID → Subscriptions.")
                }
            }
            .navigationTitle("Plans and extras")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .disabled(busy)
        }
    }

    private func planSection(_ name: String, plan: String, perks: [String], ids: [String]) -> some View {
        Section {
            ForEach(perks, id: \.self) { Label($0, systemImage: "checkmark").foregroundStyle(.primary) }
            ForEach(ids, id: \.self) { id in
                if let p = Catalog.shared.byId(id: id) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text("\(price(p)) / \(p.period == .yearly ? "year" : "month")").bold()
                            Text(p.description_).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(p.trialDays > 0 ? "Try free" : "Choose") { buy(id) }
                            .buttonStyle(.borderedProminent).disabled(plans.plan == plan)
                    }
                }
            }
        } header: { Text(name + (plans.plan == plan ? " · your plan" : "")) }
    }

    private func productRow(_ p: PetPulseCore.Product, owned: Bool) -> some View {
        HStack {
            VStack(alignment: .leading) {
                Text(p.title)
                Text(p.description_).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if owned { Text("Owned").foregroundStyle(.secondary) } else { Button(price(p)) { buy(p.id) }.buttonStyle(.bordered) }
        }
    }

    /// The App Store's localized price when available, else the US list price.
    private func price(_ p: PetPulseCore.Product) -> String { plans.products[p.id]?.displayPrice ?? p.priceLabel }

    private func buy(_ id: String) {
        busy = true
        Task {
            switch await plans.buy(id) {
            case .success(let m): message = m
            case .cancelled: break
            case .failed(let m): message = m
            }
            busy = false
        }
    }
}
