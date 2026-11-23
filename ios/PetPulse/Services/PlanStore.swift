import Foundation
import StoreKit
import PetPulseCore

/// The user's plan and one-time purchases. Uses StoreKit 2 once the products exist in App Store
/// Connect; until then (and in the simulator) a demo store that charges nothing.
@MainActor
final class PlanStore: ObservableObject {
    static let shared = PlanStore()

    @Published private(set) var plan = "FREE"          // FREE, PREMIUM or FAMILY
    @Published private(set) var inTrial = false
    @Published private(set) var expiresAt: Date?
    @Published private(set) var owned: Set<String> = []
    @Published private(set) var products: [String: StoreKit.Product] = [:]
    var isDemo: Bool { products.isEmpty }

    var limits: PlanLimits { Billing.shared.limits(planName: plan) }
    var planName: String { ["PREMIUM": "Premium", "FAMILY": "Family"][plan] ?? "Free" }

    static let subscriptionIds = Catalog.shared.subscriptions.map(\.id)
    static let extraIds = Catalog.shared.extras.map(\.id)
    private var updates: Task<Void, Never>?
    private let defaults = UserDefaults.standard

    func start() async {
        if let list = try? await StoreKit.Product.products(for: Self.subscriptionIds + Self.extraIds) {
            products = Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
        }
        updates?.cancel()
        updates = Task { [weak self] in
            for await update in Transaction.updates {
                if case .verified(let t) = update { await t.finish() }
                await self?.refresh()
            }
        }
        await refresh()
    }

    func refresh() async {
        if isDemo { loadDemo(); return }
        var best = "FREE", trial = false, expires: Date?, bought = Set<String>()
        for await result in Transaction.currentEntitlements {
            guard case .verified(let t) = result else { continue }
            if let product = Catalog.shared.byId(id: t.productID), let p = product.plan {
                if best != "FAMILY" { best = p.name; expires = t.expirationDate; trial = t.offerType == .introductory }
            } else {
                bought.insert(t.productID)
            }
        }
        plan = best; inTrial = trial; expiresAt = expires; owned = bought
    }

    enum Outcome { case success(String), cancelled, failed(String) }

    func buy(_ id: String) async -> Outcome {
        guard let item = Catalog.shared.byId(id: id) else { return .failed("Unknown product") }
        if item.kind == .physical { return .failed("The print shop opens soon.") }
        if isDemo { return demoBuy(item) }
        guard let product = products[id] else { return .failed("This item isn't available in your App Store yet.") }
        do {
            var options: Set<StoreKit.Product.PurchaseOption> = []
            if let uid = await CloudSession.shared.userId, let token = UUID(uuidString: uid) { options.insert(.appAccountToken(token)) }
            switch try await product.purchase(options: options) {
            case .success(let verification):
                guard case .verified(let t) = verification else { return .failed("The App Store couldn't verify this purchase.") }
                await t.finish()
                await refresh()
                return .success("Thanks! \(item.title) is active.")
            case .userCancelled: return .cancelled
            case .pending: return .failed("Waiting for approval (Ask to Buy).")
            @unknown default: return .cancelled
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    func restore() async {
        if !isDemo { try? await AppStore.sync() }
        await refresh()
    }

    // MARK: demo store

    private func demoBuy(_ p: PetPulseCore.Product) -> Outcome {
        if p.kind == .subscription, let newPlan = p.plan {
            let trial = p.trialDays > 0 && plan == "FREE"
            let days = trial ? Int(p.trialDays) : (p.period == .yearly ? 365 : 30)
            defaults.set(newPlan.name, forKey: "demo.plan")
            defaults.set(trial, forKey: "demo.trial")
            defaults.set(Date().addingTimeInterval(Double(days) * 86_400).timeIntervalSince1970, forKey: "demo.expires")
        } else {
            defaults.set(Array(owned.union([p.id])), forKey: "demo.owned")
        }
        loadDemo()
        return .success("Demo purchase: \(p.title) is active. Nothing was charged.")
    }

    func resetDemo() {
        ["demo.plan", "demo.trial", "demo.expires", "demo.owned"].forEach { defaults.removeObject(forKey: $0) }
        loadDemo()
    }

    private func loadDemo() {
        let exp = defaults.double(forKey: "demo.expires")
        let active = exp > Date().timeIntervalSince1970
        plan = active ? (defaults.string(forKey: "demo.plan") ?? "FREE") : "FREE"
        inTrial = active && defaults.bool(forKey: "demo.trial")
        expiresAt = active ? Date(timeIntervalSince1970: exp) : nil
        owned = Set(defaults.stringArray(forKey: "demo.owned") ?? [])
    }
}
