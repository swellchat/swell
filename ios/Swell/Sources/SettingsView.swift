import SwiftUI
import PhotosUI
import SwellCore
import SwellSignal

/// Settings: a profile header, then grouped pages. Every row does something.
struct SettingsSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirm = false
    @State private var working = false
    @State private var showTerms = false
    @State private var showCode = false
    @State private var name = ""
    @State private var editingName = false
    @State private var pick: PhotosPickerItem?
    @State private var link: String?
    @State private var copied = false
    @FocusState private var nameFocus: Bool
    @State private var debugPage: String? = {
        #if DEBUG
        if let i = CommandLine.arguments.firstIndex(of: "-swellPage"), i + 1 < CommandLine.arguments.count { return CommandLine.arguments[i + 1] }
        #endif
        return nil
    }()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    header
                    card {
                        nav("paintpalette", "Appearance", "Themes and accent colour") { AppearanceView() }
                        divider
                        nav("hand.raised", "Privacy", "Lock, screen security, receipts") { PrivacyView() }
                        divider
                        nav("bubble.left.and.bubble.right", "Conversations", "Voice, blocked contacts") { ConversationsView() }
                        divider
                        nav("key.horizontal", "Recovery phrase", "Your 12 words") { RecoveryView() }
                    }
                    card {
                        nav("point.3.connected.trianglepath.dotted", "Network", "How your messages travel") { NetworkView() }
                        divider
                        nav("questionmark.circle", "Help", "Questions, debug log") { HelpView() }
                        divider
                        nav("info.circle", "About", "Version, licence") { AboutView() }
                    }
                    card {
                        Button { showTerms = true } label: { row("doc.text", "Terms of Use", nil) }.buttonStyle(.plain)
                        divider
                        Link(destination: URL(string: "mailto:support@jijilabsdigital.com")!) { row("envelope", "Contact Jiji Labs", nil) }.buttonStyle(.plain)
                    }
                    card {
                        Button(role: .destructive) { confirm = true } label: {
                            HStack { Image(systemName: "trash").frame(width: 28); Text(working ? "Deleting…" : "Delete account"); Spacer() }
                                .foregroundStyle(.red).padding(14).contentShape(Rectangle())
                        }.disabled(working)
                        if let p = model.problem { Text(p).font(.footnote).foregroundStyle(.red).padding([.horizontal, .bottom], 14) }
                    }
                    Text("Erases your contacts, messages and keys from this iPhone.").font(.footnote).foregroundStyle(.secondary)
                }.padding(16)
            }
            .background(Ocean.background.ignoresSafeArea())
            .navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { model.setName(name); dismiss() }.disabled(working) } }
            .interactiveDismissDisabled(working)
            .onAppear { name = model.name }
            .task { link = await model.myCode().code.map { InviteCode.linkPrefix + $0 } }
            .onChange(of: pick) { _, item in
                guard let item else { return }
                Task {
                    if let d = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: d) { model.setPhoto(img) }
                    pick = nil
                }
            }
            .sheet(isPresented: $showTerms) { TermsSheet() }
            .sheet(isPresented: $showCode) { MyCodeSheet() }
            .navigationDestination(item: $debugPage) { p in
                switch p {
                case "appearance": AppearanceView()
                case "privacy": PrivacyView()
                case "server": ServerSeesView()
                case "recovery": RecoveryView()
                case "network": NetworkView()
                case "help": HelpView()
                default: AboutView()
                }
            }
            .confirmationDialog("Delete your account?", isPresented: $confirm, titleVisibility: .visible) {
                Button("Delete account", role: .destructive) {
                    working = true
                    Task { let ok = await model.deleteAccount(); working = false; if ok { dismiss() } }
                }
            } message: {
                Text("All your contacts, messages and keys are erased from this iPhone. Your 12 recovery words will no longer bring them back. Anything still waiting in your mailbox is no longer fetched and expires within 14 days.")
            }
        }
        .presentationBackground(.ultraThinMaterial)
    }

    private var header: some View {
        VStack(spacing: 12) {
            ZStack(alignment: .topTrailing) {
                VStack(spacing: 12) {
                    PhotosPicker(selection: $pick, matching: .images) {
                        ZStack(alignment: .bottomTrailing) {
                            Avatar(name: model.name.isEmpty ? "?" : model.name, size: 96, photo: model.myPhoto)
                            Image(systemName: "camera.circle.fill").symbolRenderingMode(.palette).foregroundStyle(.white, Ocean.accent).font(.title)
                        }
                    }.accessibilityLabel("Change profile photo")
                    if editingName {
                        TextField("Your name", text: $name).font(.title2.weight(.semibold)).multilineTextAlignment(.center).focused($nameFocus)
                            .submitLabel(.done).onSubmit { model.setName(name); editingName = false }
                    } else {
                        Button { editingName = true; nameFocus = true } label: {
                            HStack(spacing: 6) { Text(model.name.isEmpty ? "Add your name" : model.name).font(.title2.weight(.semibold)); Image(systemName: "pencil").font(.footnote).foregroundStyle(.secondary) }
                        }.buttonStyle(.plain).accessibilityLabel("Edit name")
                    }
                    if model.myPhoto != nil { Button("Remove photo", role: .destructive) { model.setPhoto(nil) }.font(.footnote) }
                }.frame(maxWidth: .infinity)
                Button { showCode = true } label: {
                    Image(systemName: "qrcode").font(.title3).foregroundStyle(Ocean.accent).padding(10).background(Circle().fill(Ocean.card))
                }.accessibilityLabel("My code")
            }
            HStack(spacing: 10) {
                if let link {
                    ShareLink(item: "Chat with me on Swell: \(link)") { Label("Invite a friend", systemImage: "person.badge.plus").frame(maxWidth: .infinity) }.buttonStyle(PrimaryButton())
                    Button { UIPasteboard.general.string = link; copied = true; DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false } } label: {
                        Label(copied ? "Copied" : "Copy link", systemImage: copied ? "checkmark" : "link").frame(maxWidth: .infinity)
                    }.buttonStyle(PrimaryButton())
                } else {
                    Button { showCode = true } label: { Label("Invite a friend", systemImage: "person.badge.plus").frame(maxWidth: .infinity) }.buttonStyle(PrimaryButton())
                }
            }
            Text("Your name and photo are shared only with your contacts.").font(.footnote).foregroundStyle(.secondary)
        }
        .padding(18).background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Ocean.card))
    }

    private func card<C: View>(@ViewBuilder _ c: () -> C) -> some View {
        VStack(spacing: 0) { c() }.background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Ocean.card))
    }
    private var divider: some View { Rectangle().fill(Ocean.hairline).frame(height: 0.5).padding(.leading, 56) }
    private func nav<D: View>(_ icon: String, _ title: String, _ sub: String, @ViewBuilder _ dest: () -> D) -> some View {
        NavigationLink { dest() } label: { row(icon, title, sub, chevron: true) }.buttonStyle(.plain)
    }
    private func row(_ icon: String, _ title: String, _ sub: String?, chevron: Bool = false) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.body).foregroundStyle(Ocean.accent).frame(width: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.body).foregroundStyle(.primary)
                if let sub { Text(sub).font(.footnote).foregroundStyle(.secondary) }
            }
            Spacer()
            if chevron { Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary) }
        }.padding(14).contentShape(Rectangle())
    }
}

/// A plain page with Swell's card style.
struct SettingsPage<C: View>: View {
    let title: String
    @ViewBuilder let content: () -> C
    var body: some View {
        ScrollView { VStack(spacing: 14) { content() }.padding(16) }
            .background(Ocean.background.ignoresSafeArea())
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
    }
}

struct Card<C: View>: View {
    var title: String? = nil
    var footer: String? = nil
    @ViewBuilder let content: () -> C
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let title { Text(title).font(.footnote.weight(.semibold)).foregroundStyle(.secondary).padding(.leading, 6) }
            VStack(alignment: .leading, spacing: 12) { content() }
                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Ocean.card))
            if let footer { Text(footer).font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 6) }
        }
    }
}

// MARK: Appearance

struct AppearanceView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        SettingsPage(title: "Appearance") {
            Card(title: "Preview") {
                VStack(spacing: 6) {
                    HStack { Text("Is the new look ready?").padding(.horizontal, 14).padding(.vertical, 9).background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Ocean.incoming)); Spacer() }
                    HStack { Spacer(); Text("It is, take a look.").foregroundStyle(Ocean.onAccent).padding(.horizontal, 14).padding(.vertical, 9).background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Ocean.bubble)) }
                }
            }
            Card(title: "Theme") {
                ForEach(SwellTheme.allCases) { t in
                    Button { model.theme = t } label: {
                        HStack { Text(t.title).foregroundStyle(.primary); Spacer(); if model.theme == t { Image(systemName: "checkmark").foregroundStyle(Ocean.accent) } }.contentShape(Rectangle())
                    }.buttonStyle(.plain)
                    if t != SwellTheme.allCases.last { Rectangle().fill(Ocean.hairline).frame(height: 0.5) }
                }
            }
            Card(title: "Accent colour") {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: 14) {
                    ForEach(SwellAccent.allCases) { a in
                        Button { model.accent = a } label: {
                            Circle().fill(LinearGradient(colors: [Color(hex6: a.colors.0), Color(hex6: a.colors.1)], startPoint: .topLeading, endPoint: .bottomTrailing))
                                .frame(width: 38, height: 38)
                                .overlay(Circle().stroke(Color.primary.opacity(model.accent == a ? 0.9 : 0), lineWidth: 2.5).padding(-4))
                        }.buttonStyle(.plain).accessibilityLabel(a.title).accessibilityAddTraits(model.accent == a ? .isSelected : [])
                    }
                }
                Text(model.accent.title).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

extension Color {
    init(hex6 v: UInt32) { self.init(red: Double((v >> 16) & 255) / 255, green: Double((v >> 8) & 255) / 255, blue: Double(v & 255) / 255) }
}

// MARK: Privacy

struct PrivacyView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        SettingsPage(title: "Privacy") {
            Card(title: "Protect Swell", footer: "Lock Swell asks for Face ID or your passcode when you open the app. Screen security hides the chats in the app switcher and while the screen is recorded or mirrored.") {
                Toggle("Lock Swell", isOn: $model.appLock)
                Toggle("Screen security", isOn: $model.screenSecurity)
            }
            Card(title: "Receipts", footer: "When these are off, you neither send nor see them. They work only with contacts who turned them on too.") {
                Toggle("Delivery and read receipts", isOn: $model.receipts)
                Toggle("Typing indicator", isOn: $model.typing)
            }
            Card(title: "Transparency") {
                NavigationLink { ServerSeesView() } label: {
                    HStack { Image(systemName: "eye.slash").foregroundStyle(Ocean.accent); Text("What the server sees").foregroundStyle(.primary); Spacer(); Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary) }
                }.buttonStyle(.plain)
            }
        }
    }
}

struct ServerSeesView: View {
    @EnvironmentObject var model: AppModel
    @State private var view: [(queue: String, envelopes: Int, bytes: Int)]?
    var body: some View {
        SettingsPage(title: "What the server sees") {
            Card(title: "The mailbox can see") {
                line("number.square", "\(model.contacts.filter { $0.queueReady }.count) random queue ids, one for each of your chats. They are not linked to a name.")
                line("tray.full", "How many sealed envelopes wait in each queue, how large they are, and when each one arrived.")
                line("clock", "They stay until you collect them, and for at most 14 days.")
                line("waveform", "It can see when a message arrives.")
            }
            Card(title: "It cannot see") {
                line("person.crop.circle.badge.xmark", "Your name or photo.")
                line("person.2.slash", "Your contacts.")
                line("text.bubble", "What any message says. Everything is encrypted on your iPhone first.")
                line("network.slash", "Your IP address. Swell reaches it through a private route.")
                line("arrow.left.arrow.right", "Who sent a message.")
            }
            Card(title: "On this iPhone right now", footer: "Queue ids are shortened here. Nothing is read or changed by looking.") {
                if let view {
                    if view.isEmpty { Text("No queues yet.").font(.subheadline).foregroundStyle(.secondary) }
                    ForEach(Array(view.enumerated()), id: \.offset) { _, v in
                        HStack {
                            Text(v.queue + "…").font(.system(.subheadline, design: .monospaced))
                            Spacer()
                            Text(v.envelopes == 0 ? "empty" : "\(v.envelopes) waiting, \(ByteCountFormatter.string(fromByteCount: Int64(v.bytes), countStyle: .file))").font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                } else { Text("Checking…").font(.subheadline).foregroundStyle(.secondary) }
            }
        }
        .task { view = await model.mailboxView() }
    }
    private func line(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) { Image(systemName: icon).foregroundStyle(Ocean.accent).frame(width: 24); Text(text).font(.subheadline) }
    }
}

// MARK: Conversations

struct ConversationsView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        SettingsPage(title: "Conversations") {
            Card(title: "Voice notes") { Toggle("Play the next voice note automatically", isOn: $model.autoplay) }
            Card(title: "Blocked", footer: "Messages from blocked contacts are dropped without a notice.") {
                let blocked = model.contacts.filter { $0.blocked }
                if blocked.isEmpty { Text("No blocked contacts").font(.subheadline).foregroundStyle(.secondary) }
                ForEach(blocked) { c in
                    HStack {
                        Avatar(name: c.displayName, size: 32, photo: model.avatars[c.id]); Text(c.displayName); Spacer()
                        Button("Unblock") { model.setBlocked(c.id, false) }.font(.subheadline.weight(.medium))
                    }
                }
            }
            Card(title: "Archived", footer: "Archived chats leave the main list. Swipe a chat and choose Archive.") {
                let a = model.contacts.filter { (model.flags[$0.id] ?? 0) & 2 != 0 }
                if a.isEmpty { Text("No archived chats").font(.subheadline).foregroundStyle(.secondary) }
                ForEach(a) { c in
                    HStack { Avatar(name: c.displayName, size: 32, photo: model.avatars[c.id]); Text(c.displayName); Spacer()
                        Button("Unarchive") { model.setFlag(c.id, 2, false) }.font(.subheadline.weight(.medium)) }
                }
            }
        }
    }
}

// MARK: Recovery phrase

struct RecoveryView: View {
    @EnvironmentObject var model: AppModel
    @State private var shown = false
    @State private var qr = false
    @State private var confirmHide = false
    @State private var copied = false
    var body: some View {
        SettingsPage(title: "Recovery phrase") {
            if model.phraseHidden {
                Card { Label("The phrase is hidden on this iPhone. If you wrote it down, keep that paper safe: it cannot be shown here again.", systemImage: "eye.slash").font(.subheadline) }
            } else {
                let words = model.account?.phrase ?? []
                Card(title: "Your 12 words", footer: "These words are the only way to get your account back on a new phone. Anyone who has them can become you.") {
                    if shown {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 10) {
                            ForEach(Array(words.enumerated()), id: \.offset) { i, w in
                                HStack(spacing: 4) { Text("\(i + 1)").font(.caption).foregroundStyle(.secondary); Text(w).font(.system(.body, design: .monospaced)) }
                            }
                        }
                    } else {
                        Button { Task { if await Gate.unlock(reason: "Show your recovery phrase") { shown = true } } } label: { Label("Show the words", systemImage: "eye") }.buttonStyle(PrimaryButton())
                    }
                }
                if shown {
                    Card {
                        Button { UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: words.joined(separator: " ")]], options: [.expirationDate: Date().addingTimeInterval(60), .localOnly: true]); copied = true } label: {
                            Label(copied ? "Copied for one minute" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                        }
                        Button { qr.toggle() } label: { Label(qr ? "Hide the QR code" : "View as QR code", systemImage: "qrcode") }
                        if qr { QRCodeView(text: words.joined(separator: " ")).frame(maxWidth: .infinity) }
                    }
                    Card(footer: "Only do this once the words are written down. Swell will never show them again.") {
                        Button(role: .destructive) { confirmHide = true } label: { Label("Hide permanently on this device", systemImage: "eye.slash") }
                    }
                }
            }
        }
        .confirmationDialog("Hide the phrase for good?", isPresented: $confirmHide, titleVisibility: .visible) {
            Button("Hide permanently", role: .destructive) { model.phraseHidden = true; shown = false; qr = false }
        } message: { Text("You will not be able to see the 12 words in Swell again. Make sure they are written down.") }
    }
}

// MARK: Network

struct NetworkView: View {
    @EnvironmentObject var model: AppModel
    @State private var hops: [String]?
    @State private var tick = 0
    var body: some View {
        SettingsPage(title: "Network") {
            Card(title: "Your route right now", footer: "Each stop only knows the stop before and after it. The route changes on its own.") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 0) {
                        stop("iphone", "You", nil)
                        ForEach(Array(labels.enumerated()), id: \.offset) { i, l in link; stop(i == 0 ? "door.left.hand.open" : "arrow.triangle.swap", l.0, l.1) }
                        link; stop("tray.full", "Swell mailbox", nil)
                    }.padding(.vertical, 6)
                }
                Text(hops == nil ? "Waiting for a route…" : "\(model.networkOK ? "Connected" : "Connecting")").font(.footnote).foregroundStyle(.secondary)
            }
            Card(title: "More") {
                NavigationLink { ServerSeesView() } label: {
                    HStack { Image(systemName: "eye.slash").foregroundStyle(Ocean.accent); Text("What the server sees").foregroundStyle(.primary); Spacer(); Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary) }
                }.buttonStyle(.plain)
                LabeledContent("Status", value: model.network).font(.subheadline)
            }
        }
        .task {
            while !Task.isCancelled { hops = await model.route(); try? await Task.sleep(nanoseconds: 4_000_000_000) }
        }
    }
    private var labels: [(String, String?)] {
        guard let h = hops, !h.isEmpty else { return [("Entry", nil), ("Relay", nil), ("Relay", nil)] }
        // Entry, middle relays, then the last relay before the mailbox.
        return h.enumerated().map { i, cc in (i == 0 ? "Entry" : "Relay", cc.isEmpty ? nil : Self.country(cc)) }
    }
    static func country(_ cc: String) -> String { Locale.current.localizedString(forRegionCode: cc) ?? cc }
    private var link: some View { Capsule().fill(LinearGradient(colors: [Ocean.accent.opacity(0.3), Ocean.cyan.opacity(0.9)], startPoint: .leading, endPoint: .trailing)).frame(width: 26, height: 3) }
    private func stop(_ icon: String, _ title: String, _ sub: String?) -> some View {
        VStack(spacing: 5) {
            Image(systemName: icon).font(.title3).foregroundStyle(.white).frame(width: 46, height: 46).background(Circle().fill(Ocean.bubble))
            Text(title).font(.caption.weight(.semibold)).lineLimit(1)
            Text(sub ?? " ").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }.frame(width: 72)
    }
}

// MARK: Help and About

struct HelpView: View {
    @EnvironmentObject var model: AppModel
    @State private var exported: URL?
    var body: some View {
        SettingsPage(title: "Help") {
            Card(title: "Questions") {
                faq("Who can read my messages?", "Only you and the person you write to. Messages are encrypted on your iPhone and can be opened only on theirs.")
                faq("Why does a message say it is waiting?", "The other phone is off or has no signal. Your message waits in the mailbox for up to 14 days and arrives when they are back.")
                faq("How do I add a friend?", "Tap Invite a friend, send them the link, or let them scan your code. Both of you are connected as soon as they open it.")
                faq("I lost my phone.", "Install Swell on the new phone and choose I have a recovery phrase. Your identity comes back; you add your contacts again with new codes.")
                faq("Can I see what Swell knows about me?", "Yes: Settings, Privacy, What the server sees.")
            }
            Card(title: "Debug log", footer: "A short report for support: app version, counts and states. It contains no messages, no names, no contacts, no keys and no addresses.") {
                if let exported { ShareLink(item: exported) { Label("Share the log", systemImage: "square.and.arrow.up") } }
                else { Button { exported = model.exportDebugLog() } label: { Label("Export debug log", systemImage: "doc.text.magnifyingglass") } }
            }
        }
    }
    private func faq(_ q: String, _ a: String) -> some View {
        DisclosureGroup { Text(a).font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading) } label: { Text(q).font(.subheadline.weight(.medium)) }
    }
}

struct AboutView: View {
    var body: some View {
        SettingsPage(title: "About") {
            VStack(spacing: 8) { BrandMark(size: 72); Text("Swell").font(.title2.weight(.semibold))
                Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0") (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "2"))").font(.footnote).foregroundStyle(.secondary) }
                .padding(.vertical, 8)
            Card {
                Text("Swell is free software under the GNU AGPL-3.0 and comes with no warranty. Copyright PT JIJI LABS DIGITAL and the authors of the included software.").font(.subheadline)
                Link(destination: URL(string: "https://jijilabsdigital.com/swell/source")!) { Label("Source code and licenses", systemImage: "chevron.left.forwardslash.chevron.right") }
            }
        }
    }
}
