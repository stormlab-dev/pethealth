import SwiftUI
import SwiftData
import PetPulseCore

/// Remembers linked partner accounts. Real OAuth tokens belong in the Keychain or, better, on
/// your backend; this only stores non-secret ids.
enum LinkedAccounts {
    static func get(_ providerId: String) -> LinkedAccount? {
        guard let v = UserDefaults.standard.string(forKey: "linked.\(providerId)") else { return nil }
        let parts = v.split(separator: "|", maxSplits: 1).map(String.init)
        return LinkedAccount(providerId: providerId, accountId: parts[0], displayName: parts.count > 1 ? parts[1] : providerId)
    }
    static func save(_ a: LinkedAccount) { UserDefaults.standard.set("\(a.accountId)|\(a.displayName)", forKey: "linked.\(a.providerId)") }
    static func remove(_ providerId: String) { UserDefaults.standard.removeObject(forKey: "linked.\(providerId)") }
}

@MainActor
struct PharmacyView: View {
    @Bindable var med: MedicationRecord
    @Environment(\.dismiss) private var dismiss
    @State private var account: LinkedAccount?
    @State private var prescriptions: [Prescription] = []
    @State private var orders: [RefillOrder] = []
    @State private var busy = false
    @State private var message: String?
    private let provider = partners.pharmacies.first!

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if account == nil {
                        Text("Connect your pharmacy account to see prescriptions and request refills in one tap.")
                        Button("Connect account") { run { let a = try await provider.link(authCode: "demo-auth-code"); LinkedAccounts.save(a); account = a; try await load() } }
                    } else {
                        Text("Connected as \(account!.displayName)")
                        Button("Disconnect", role: .destructive) { LinkedAccounts.remove(provider.info.id); account = nil; prescriptions = [] }
                    }
                } header: {
                    HStack { Text(provider.info.displayName); if provider.info.isDemo { Pill(text: "Demo", color: .yellow) } }
                }
                if account != nil {
                    Section(med.pharmacyPrescriptionId == nil ? "Pick the prescription that matches \(med.name)" : "Prescriptions") {
                        ForEach(prescriptions, id: \.id) { rx in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(rx.drugName).font(.headline)
                                Text("\(rx.strength) · \(rx.directions)")
                                Text("\(rx.refillsRemaining) refills left · expires \(rx.expiresOn.pretty) · \(rx.prescribingClinic)").font(.caption).foregroundStyle(.secondary)
                                HStack {
                                    Button(rx.refillsRemaining > 0 ? "Request refill" : "Ask vet to renew") {
                                        run {
                                            let o = try await provider.requestRefill(account: account!, prescriptionId: rx.id, quantity: nil)
                                            orders.insert(o, at: 0); message = o.message; try await load()
                                        }
                                    }.buttonStyle(.borderedProminent)
                                    if med.pharmacyPrescriptionId != rx.id {
                                        Button("Link to \(med.name)") { med.pharmacyPrescriptionId = rx.id; med.pharmacyProviderId = provider.info.id }.buttonStyle(.bordered)
                                    }
                                }
                            }
                        }
                    }
                }
                if !orders.isEmpty {
                    Section("Orders") {
                        ForEach(orders, id: \.id) { o in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text("Order \(o.id)")
                                    if let m = o.message { Text(m).font(.caption) }
                                    if let t = o.trackingUrl { Text("Tracking: \(t)").font(.caption) }
                                }
                                Spacer()
                                Pill(text: o.state.name.lowercased().replacingOccurrences(of: "_", with: " "),
                                     color: ["SHIPPED", "DELIVERED"].contains(o.state.name) ? .green : .yellow)
                            }
                        }
                        Button("Refresh status") {
                            run { var fresh: [RefillOrder] = []; for o in orders { fresh.append(try await provider.order(account: account!, orderId: o.id)) }; orders = fresh }
                        }
                    }
                }
            }
            .overlay { if busy { ProgressView() } }
            .navigationTitle("Pharmacy")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") {} }
            .task { account = LinkedAccounts.get(provider.info.id); if account != nil { run { try await load() } } }
        }
    }

    private func load() async throws {
        guard let a = account else { return }
        prescriptions = try await provider.prescriptions(account: a)
    }

    private func run(_ work: @escaping () async throws -> Void) {
        Task {
            busy = true
            defer { busy = false }
            do { try await work() } catch { message = error.localizedDescription }
        }
    }
}
