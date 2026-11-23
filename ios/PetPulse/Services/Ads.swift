import SwiftUI
import UIKit
import GoogleMobileAds
import UserMessagingPlatform

/// AdMob banners for the free plan. Premium and Family never see ads. Google's consent form (UMP)
/// is shown first where the law requires it.
@MainActor
final class Ads: ObservableObject {
    static let shared = Ads()
    @Published private(set) var ready = false
    private var started = false

    static var bannerID: String { Bundle.main.object(forInfoDictionaryKey: "CFPAdMobBannerID") as? String ?? "" }

    func start() {
        ConsentInformation.shared.requestConsentInfoUpdate(with: RequestParameters()) { [weak self] _ in
            Task { @MainActor in
                guard let root = UIApplication.shared.topViewController else { self?.initializeIfAllowed(); return }
                ConsentForm.loadAndPresentIfRequired(from: root) { _ in
                    Task { @MainActor in self?.initializeIfAllowed() }
                }
            }
        }
        initializeIfAllowed()
    }

    private func initializeIfAllowed() {
        guard ConsentInformation.shared.canRequestAds, !started else { return }
        started = true
        MobileAds.shared.start { [weak self] _ in Task { @MainActor in self?.ready = true } }
    }

    var privacyOptionsRequired: Bool { ConsentInformation.shared.privacyOptionsRequirementStatus == .required }

    func showPrivacyOptions() {
        guard let root = UIApplication.shared.topViewController else { return }
        ConsentForm.presentPrivacyOptionsForm(from: root) { _ in }
    }
}

extension UIApplication {
    var topViewController: UIViewController? {
        var vc = connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController
        while let presented = vc?.presentedViewController { vc = presented }
        return vc
    }
}

/// Adaptive banner for free-plan users. Keep it off health screens (symptom checks, meds, vet).
struct FreePlanBanner: View {
    @ObservedObject private var plans = PlanStore.shared
    @ObservedObject private var ads = Ads.shared

    var body: some View {
        if plans.plan == "FREE" && ads.ready {
            GeometryReader { geo in
                BannerContainer(width: geo.size.width)
            }
            .frame(height: 60)
        }
    }
}

private struct BannerContainer: UIViewRepresentable {
    let width: CGFloat
    func makeUIView(context: Context) -> BannerView {
        let banner = BannerView(adSize: currentOrientationAnchoredAdaptiveBanner(width: width))
        banner.adUnitID = Ads.bannerID
        banner.rootViewController = UIApplication.shared.topViewController
        banner.load(Request())
        return banner
    }
    func updateUIView(_ uiView: BannerView, context: Context) {}
}
