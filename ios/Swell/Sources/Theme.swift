import SwiftUI

/// Swell look: deep navy in dark mode, soft mist in light mode, one sea-teal accent.
/// Text always uses the system font (Dynamic Type). No pixel art.
enum Ocean {
    private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
    static let accent = dyn(0x1B72B8, 0x2390DC)          // Electric blue
    static let cyan = Color(red: 0x06/255, green: 0xB6/255, blue: 0xD4/255)
    static let foam = Color(red: 0xB7/255, green: 0xE1/255, blue: 0xF2/255)
    static let bubble = LinearGradient(colors: [Color(red: 0x25/255, green: 0x63/255, blue: 0xEB/255), cyan], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let onAccent = Color.white
    static let background = dyn(0xF2F6FA, 0x0A0B22)      // Deep
    static let card = dyn(0xFFFFFF, 0x1B2152)     // Raised
    static let incoming = dyn(0xE6ECF3, 0x121231)   // Navy
    static let hairline = dyn(0xD5DDE8, 0x2A3358)
}

private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 255) / 255, green: CGFloat((hex >> 8) & 255) / 255, blue: CGFloat(hex & 255) / 255, alpha: 1)
    }
}

/// Primary: filled, full width, radius 14. Secondary: plain accent text.
struct PrimaryButton: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline).foregroundStyle(Ocean.onAccent)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Ocean.bubble))
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.4)
    }
}

struct SecondaryButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.medium)).foregroundStyle(Ocean.accent)
            .frame(maxWidth: .infinity, minHeight: 44)
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// Brand mark with a soft glow, modest size.
struct BrandMark: View {
    var size: CGFloat = 96
    var body: some View {
        Image("mark").resizable().scaledToFit().frame(width: size, height: size)
            .shadow(color: Ocean.cyan.opacity(0.45), radius: 28)
            .accessibilityHidden(true)
    }
}

/// Small illustration: soft gradient circle with an SF Symbol.
struct Hero: View {
    let symbol: String
    var size: CGFloat = 120
    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(Ocean.bubble))
            .accessibilityHidden(true)
    }
}

/// Round avatar with initials on a soft gradient chosen from the name.
struct Avatar: View {
    let name: String
    var size: CGFloat = 48
    var photo: Data? = nil
    private var initials: String {
        let parts = name.split(separator: " ").prefix(2).compactMap { $0.first }
        return String(parts).uppercased()
    }
    private var hue: Double { Double(name.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) % 360 }) / 360 }
    var body: some View {
        if let photo, let ui = UIImage(data: photo) {
            Image(uiImage: ui).resizable().scaledToFill().frame(width: size, height: size).clipShape(Circle()).accessibilityHidden(true)
        } else { initialsView }
    }
    private var initialsView: some View {
        Text(initials.isEmpty ? "?" : initials)
            .font(.system(size: size * 0.38, weight: .semibold, design: .rounded)).foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(LinearGradient(colors: [Color(hue: hue, saturation: 0.45, brightness: 0.78), Color(hue: hue, saturation: 0.6, brightness: 0.55)], startPoint: .topLeading, endPoint: .bottomTrailing)))
            .accessibilityHidden(true)
    }
}

/// Rounded text field used on plain pages.
struct FieldBox: ViewModifier {
    func body(content: Content) -> some View {
        content.padding(14).background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Ocean.card))
    }
}
extension View { func fieldBox() -> some View { modifier(FieldBox()) } }
