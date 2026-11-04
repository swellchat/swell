import SwiftUI

/// Swell's own themes and accents. Settings stores the choice; the whole view tree is rebuilt when it changes.
enum SwellTheme: String, CaseIterable, Identifiable {
    case navy, midnight, light, mist, system
    var id: String { rawValue }
    var title: String { switch self { case .navy: return "Swell Navy"; case .midnight: return "Midnight"; case .light: return "Swell Light"; case .mist: return "Mist"; case .system: return "Follow system" } }
    /// nil follows the system.
    var scheme: ColorScheme? { switch self { case .navy, .midnight: return .dark; case .light, .mist: return .light; case .system: return nil } }
    static var current: SwellTheme { SwellTheme(rawValue: UserDefaults.standard.string(forKey: "theme") ?? "") ?? .navy }
}

enum SwellAccent: String, CaseIterable, Identifiable {
    case wave, teal, indigo, violet, coral, amber
    var id: String { rawValue }
    var title: String { switch self { case .wave: return "Swell blue"; case .teal: return "Teal"; case .indigo: return "Indigo"; case .violet: return "Violet"; case .coral: return "Coral"; case .amber: return "Amber" } }
    /// (gradient start, gradient end), also the solid accent in dark / light.
    var colors: (UInt32, UInt32) {
        switch self {
        case .wave: return (0x2563EB, 0x06B6D4); case .teal: return (0x0F9488, 0x2DD4BF); case .indigo: return (0x4338CA, 0x818CF8)
        case .violet: return (0x7C3AED, 0xC084FC); case .coral: return (0xE5484D, 0xFF9B7A); case .amber: return (0xD97706, 0xFBBF24)
        }
    }
    var solidDark: UInt32 { self == .wave ? 0x2390DC : colors.1 }
    var solidLight: UInt32 { self == .wave ? 0x1B72B8 : colors.0 }
    static var current: SwellAccent { SwellAccent(rawValue: UserDefaults.standard.string(forKey: "accent") ?? "") ?? .wave }
}

/// Swell look: deep navy by default, soft mist in light themes, one accent. System font everywhere (Dynamic Type). No pixel art.
enum Ocean {
    private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
    private static func hex(_ v: UInt32) -> Color { Color(UIColor(hex: v)) }
    static var accent: Color { let a = SwellAccent.current; return dyn(a.solidLight, a.solidDark) }
    static var cyan: Color { hex(SwellAccent.current.colors.1) }
    static let foam = Color(red: 0xB7/255, green: 0xE1/255, blue: 0xF2/255)
    static var bubble: LinearGradient { let c = SwellAccent.current.colors; return LinearGradient(colors: [hex(c.0), hex(c.1)], startPoint: .topLeading, endPoint: .bottomTrailing) }
    static let onAccent = Color.white
    static var background: Color { switch SwellTheme.current { case .midnight: return dyn(0xF2F6FA, 0x000000); case .mist: return hex(0xE9EEF3); case .light: return hex(0xF7F9FC); default: return dyn(0xF2F6FA, 0x0A0B22) } }
    static var card: Color { switch SwellTheme.current { case .midnight: return dyn(0xFFFFFF, 0x14161B); case .mist: return hex(0xF8FAFC); case .light: return hex(0xFFFFFF); default: return dyn(0xFFFFFF, 0x1B2152) } }
    static var incoming: Color { switch SwellTheme.current { case .midnight: return dyn(0xE6ECF3, 0x0E1014); case .mist: return hex(0xDAE3EC); case .light: return hex(0xE6ECF3); default: return dyn(0xE6ECF3, 0x121231) } }
    static var hairline: Color { switch SwellTheme.current { case .midnight: return dyn(0xD5DDE8, 0x23262D); case .mist: return hex(0xC5D0DC); case .light: return hex(0xD5DDE8); default: return dyn(0xD5DDE8, 0x2A3358) } }
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
