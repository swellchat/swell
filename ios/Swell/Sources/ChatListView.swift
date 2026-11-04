import SwiftUI
import PhotosUI
import SwellCore
import SwellSignal

struct ChatListView: View {
    @EnvironmentObject var model: AppModel
    @State private var showAdd = Debug.has("-swellAdd")
    @State private var showSettings = Debug.has("-swellSettings")
    @State private var showCode = Debug.has("-swellCode")
    @State private var path: [String] = Debug.has("-swellChat") || Debug.has("-swellInfo") || Debug.has("-swellInfoSafety") ? [DemoIds.first] : (Debug.has("-swellNote") ? [Engine.noteId] : [])
    @State private var toDelete: String?
    @State private var query = ""
    @State private var results: [ChatMessage] = []

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if !query.isEmpty { searchResults }
                else if model.contacts.isEmpty { EmptyChats(add: { showAdd = true }, invite: { showCode = true }) }
                else { list }
            }
            .background(Ocean.background)
            .navigationTitle("Chats")
            .navigationBarTitleDisplayMode(.large)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search messages")
            .onChange(of: query) { _, q in Task { results = q.isEmpty ? [] : await model.search(q) } }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }.accessibilityLabel("Settings")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAdd = true } label: { Image(systemName: "plus.circle.fill") }.accessibilityLabel("Add contact")
                }
            }
            .navigationDestination(for: String.self) { ChatView(contactId: $0) }
            .onAppear {
                #if DEBUG
                fputs("SWELL chatlist onAppear t=\(String(format: "%.3f", ProcessClock.sinceStart())) contacts=\(model.contacts.count)\n", stderr)
                #endif
            }
        }
        .confirmationDialog("Delete this contact and the chat?", isPresented: Binding(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } }), titleVisibility: .visible) {
            Button("Delete", role: .destructive) { if let id = toDelete { model.delete(id) }; toDelete = nil }
        }
        .sheet(isPresented: $showAdd) { AddContactSheet() }
        .sheet(isPresented: $showSettings) { SettingsSheet() }
        .sheet(isPresented: $showCode) { MyCodeSheet() }
    }

    private func name(_ id: String) -> String { id == Engine.noteId ? "Note to Self" : model.contacts.first { $0.id == id }?.displayName ?? "Chat" }

    private var searchResults: some View {
        List {
            if results.isEmpty { Text("No messages found").foregroundStyle(.secondary) }
            ForEach(results) { m in
                Button { path = [m.contactId] ; query = "" } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack { Text(name(m.contactId)).font(.subheadline.weight(.semibold))
                            Spacer(); Text(Date(timeIntervalSince1970: Double(m.sentAt) / 1000).formatted(date: .abbreviated, time: .omitted)).font(.caption).foregroundStyle(.secondary) }
                        Text(m.text).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                    }
                }.listRowBackground(Ocean.background)
            }
        }.listStyle(.plain).scrollContentBackground(.hidden)
    }

    private var list: some View {
        List {
            NavigationLink(value: Engine.noteId) {
                HStack(spacing: 14) {
                    Image(systemName: "note.text").font(.title3).foregroundStyle(.white).frame(width: 50, height: 50).background(Circle().fill(Ocean.bubble))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Note to Self").font(.headline)
                        Text("Only you can see this chat").font(.subheadline).foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 4)
            }.listRowBackground(Ocean.background).listRowSeparatorTint(Ocean.hairline)
            ForEach(model.contacts) { c in
                NavigationLink(value: c.id) {
                    HStack(spacing: 14) {
                        Avatar(name: c.displayName, size: 50, photo: model.avatars[c.id])
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(c.displayName).font(.headline).lineLimit(1)
                                if c.verified { Image(systemName: "checkmark.seal.fill").font(.caption).foregroundStyle(Ocean.accent).accessibilityLabel("Verified") }
                                Spacer(minLength: 6)
                                if c.lastAt > 0 {
                                    Text(Date(timeIntervalSince1970: Double(c.lastAt) / 1000).formatted(.relative(presentation: .named, unitsStyle: .abbreviated)))
                                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                            HStack(spacing: 8) {
                                Text(c.waiting ? "Waiting for them to accept…" : c.lastText.isEmpty ? "No messages yet" : c.lastText)
                                    .font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                                Spacer(minLength: 0)
                                if c.unread > 0 {
                                    Text("\(c.unread)").font(.caption.weight(.semibold)).foregroundStyle(Ocean.onAccent)
                                        .padding(.horizontal, 7).padding(.vertical, 2).background(Capsule().fill(Ocean.bubble))
                                }
                            }
                        }
                    }.padding(.vertical, 4)
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) { toDelete = c.id } label: { Label("Delete", systemImage: "trash") }
                    Button { model.setBlocked(c.id, !c.blocked) } label: { Label(c.blocked ? "Unblock" : "Block", systemImage: "nosign") }.tint(.orange)
                }
                .swipeActions(edge: .leading) {
                    Button { model.setVerified(c.id, !c.verified) } label: { Label(c.verified ? "Unverify" : "Verify", systemImage: "checkmark.seal") }.tint(Ocean.accent)
                }
                .listRowBackground(Ocean.background)
                .listRowSeparatorTint(Ocean.hairline)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }
}

private struct EmptyChats: View {
    let add: () -> Void
    let invite: () -> Void
    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            BrandMark(size: 96)
            Text("No chats yet").font(.title2.bold())
            Text("Invite a friend to chat. They get a link, tap it, and you are connected.")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button(action: invite) { Label("Invite a friend", systemImage: "person.badge.plus") }.buttonStyle(PrimaryButton()).padding(.top, 8)
            Button("I have a link or code", action: add).buttonStyle(SecondaryButton())
            Spacer()
        }.padding(.horizontal, 32)
    }
}

private struct AddContactSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var pasted = ""
    @State private var error: String?
    @State private var scan = false
    @State private var myCode = false

    private func add(_ text: String) {
        error = nil
        Task { if let e = await model.add(code: text) { error = e } else { dismiss() } }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack { Spacer(); Hero(symbol: "qrcode.viewfinder", size: 88); Spacer() }
                    Text("Add a contact").font(.title2.bold())
                    Text("Scan your friend's QR code with Swell, or paste the link or code they sent you.").font(.subheadline).foregroundStyle(.secondary)
                    Button { scan = true } label: { Label("Scan a code", systemImage: "camera.fill") }.buttonStyle(PrimaryButton()).disabled(model.adding)
                    Button { myCode = true } label: { Label("Invite a friend", systemImage: "person.badge.plus") }.buttonStyle(SecondaryButton())
                    Divider().overlay(Ocean.hairline)
                    Text("Or paste a link or code").font(.subheadline.weight(.semibold))
                    TextField("Link or code", text: $pasted, axis: .vertical).font(.body).lineLimit(1...4)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().fieldBox()
                    Button { pasted = UIPasteboard.general.string ?? pasted } label: { Label("Paste from clipboard", systemImage: "doc.on.clipboard") }
                        .buttonStyle(SecondaryButton())
                    if let error { Label(error, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.red) }
                    if model.adding {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Connecting. This can take a moment.").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    Button("Add") { add(pasted) }.buttonStyle(PrimaryButton())
                        .disabled(model.adding || pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }.padding(20)
            }
            .background(Ocean.background.ignoresSafeArea())
            .navigationTitle("Add contact").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() }.disabled(model.adding) } }
            .fullScreenCover(isPresented: $scan) {
                ScanPage(title: "Scan a code", hint: "Point the camera at your friend's Swell code") { code in scan = false; pasted = code; add(code) }
            }
            .sheet(isPresented: $myCode) { MyCodeSheet() }
        }
        .presentationBackground(.ultraThinMaterial)
        .interactiveDismissDisabled(model.adding)
    }
}

/// "My code": the QR and text of a code that lets a friend add you, plus the share sheet with a link.
struct MyCodeSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var code: String?
    @State private var error: String?
    @State private var busy = false

    private var link: String? { code.map { InviteCode.linkPrefix + $0 } }

    private func load(new: Bool = false) {
        error = nil
        #if DEBUG
        let t0 = Date()
        #endif
        Task {
            let r = await model.myCode(new: new)
            code = r.code ?? (new ? code : nil); error = r.error
            #if DEBUG
            fputs("SWELL mycode shown after \(String(format: "%.4f", Date().timeIntervalSince(t0)))s\n", stderr)
            #endif
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if busy {
                        Color.clear.frame(height: 300)
                    } else if let code, let link {
                        Text("Send your friend the link, or let them scan this code with their camera.").font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        ShareLink(item: "Chat with me on Swell: \(link)") {
                            Label("Invite a friend", systemImage: "square.and.arrow.up")
                        }.buttonStyle(PrimaryButton())
                        QRCodeView(text: link, bright: true)
                        Text("The link works once and expires after 24 hours.").font(.footnote).foregroundStyle(.secondary)
                        Menu {
                            Button { UIPasteboard.general.string = code } label: { Label("Copy code", systemImage: "doc.on.doc") }
                            Button { load(new: true) } label: { Label("Make a new link", systemImage: "arrow.triangle.2.circlepath") }
                        } label: { Label("More options", systemImage: "ellipsis.circle") }.buttonStyle(SecondaryButton())
                    } else {
                        VStack(spacing: 12) {
                            Image(systemName: "exclamationmark.triangle").font(.largeTitle).foregroundStyle(.orange)
                            Text(error ?? "Could not make a code.").font(.subheadline).multilineTextAlignment(.center)
                            Button("Try again") { load() }.buttonStyle(PrimaryButton())
                        }.padding(.top, 40)
                    }
                }.padding(20)
            }
            .background(Ocean.background.ignoresSafeArea())
            .navigationTitle("Invite a friend").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .task { load() }
        }
        .presentationBackground(.ultraThinMaterial)
    }
}

private struct SettingsSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirm = false
    @State private var working = false
    @State private var showTerms = false
    @State private var showCode = false
    @State private var name = ""
    @State private var pick: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        PhotosPicker(selection: $pick, matching: .images) {
                            ZStack(alignment: .bottomTrailing) {
                                Avatar(name: model.name.isEmpty ? "?" : model.name, size: 60, photo: model.myPhoto)
                                Image(systemName: "camera.circle.fill").symbolRenderingMode(.palette).foregroundStyle(.white, Ocean.accent).font(.title3)
                            }
                        }.accessibilityLabel("Change profile photo")
                        TextField("Your name", text: $name).font(.headline).submitLabel(.done).onSubmit { model.setName(name) }
                    }.padding(.vertical, 4)
                    if model.myPhoto != nil { Button("Remove photo", role: .destructive) { model.setPhoto(nil) } }
                } header: { Text("You") } footer: { Text("Your name and photo are shared only with your contacts.") }
                .listRowBackground(Ocean.card)
                Section {
                    Button { showCode = true } label: { Label("Invite a friend", systemImage: "person.badge.plus") }
                } .listRowBackground(Ocean.card)
                Section {
                    Toggle("Delivery and read receipts", isOn: $model.receipts)
                    Toggle("Typing indicator", isOn: $model.typing)
                } header: { Text("Privacy") } footer: { Text("When these are off, you neither send nor see them. They work only with contacts who turned them on too.") }
                .listRowBackground(Ocean.card)
                Section {
                    Label("Messages are end-to-end encrypted and routed privately, so your location stays hidden.", systemImage: "lock.fill")
                    Label("This iPhone receives while Swell is open. Messages wait in your mailbox for up to 14 days.", systemImage: "tray")
                } header: { Text("How it works") }
                .font(.subheadline).listRowBackground(Ocean.card)
                Section {
                    let blocked = model.contacts.filter { $0.blocked }
                    if blocked.isEmpty { Text("No blocked contacts").font(.subheadline).foregroundStyle(.secondary) }
                    ForEach(blocked) { c in
                        HStack {
                            Avatar(name: c.displayName, size: 32, photo: model.avatars[c.id])
                            Text(c.displayName).font(.body)
                            Spacer()
                            Button("Unblock") { model.setBlocked(c.id, false) }.font(.subheadline.weight(.medium))
                        }
                    }
                } header: { Text("Blocked") } footer: { Text("Messages from blocked contacts are dropped without a notice.") }
                .listRowBackground(Ocean.card)
                Section {
                    Link(destination: URL(string: "mailto:support@jijilabsdigital.com")!) { Label("Contact Jiji Labs", systemImage: "envelope") }
                    Button { showTerms = true } label: { Label("Terms of Use", systemImage: "doc.text") }
                } header: { Text("Support") }
                .listRowBackground(Ocean.card)
                Section {
                    LabeledContent("Network", value: model.network).font(.subheadline)
                } header: { Text("About") } footer: { Text("Technical detail for the curious. Swell routes messages through the Tor network.") }
                .listRowBackground(Ocean.card)
                Section {
                    Button(role: .destructive) { confirm = true } label: { Text(working ? "Deleting…" : "Delete account") }.disabled(working)
                    if let p = model.problem { Text(p).font(.footnote).foregroundStyle(.red) }
                } footer: { Text("Erases your contacts, messages and keys from this iPhone.") }
                .listRowBackground(Ocean.card)
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden).background(Ocean.background)
            .navigationTitle("Settings").navigationBarTitleDisplayMode(.large)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { model.setName(name); dismiss() }.disabled(working) } }
            .interactiveDismissDisabled(working)
            .onAppear { name = model.name }
            .onChange(of: pick) { _, item in
                guard let item else { return }
                Task {
                    if let d = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: d) { model.setPhoto(img) }
                    pick = nil
                }
            }
            .sheet(isPresented: $showTerms) { TermsSheet() }
            .sheet(isPresented: $showCode) { MyCodeSheet() }
            .confirmationDialog("Delete your account?", isPresented: $confirm, titleVisibility: .visible) {
                Button("Delete account", role: .destructive) {
                    working = true
                    Task { let ok = await model.deleteAccount(); working = false; if ok { dismiss() } }
                }
            } message: {
                Text("All your contacts, messages and keys are erased from this iPhone. Your 12 recovery words will no longer bring them back. Anything still waiting in your mailbox is no longer fetched and expires on its own within 14 days. This cannot be undone.")
            }
        }
        .presentationBackground(.ultraThinMaterial)
    }
}

/// Debug-only screen selection for device screenshots; always false in Release.
enum Debug {
    static func has(_ a: String) -> Bool {
        #if DEBUG
        return CommandLine.arguments.contains(a)
        #else
        return false
        #endif
    }
}
enum DemoIds {
    static var first: String {
        #if DEBUG
        return DemoData.contacts().first?.id ?? ""
        #else
        return ""
        #endif
    }
}
