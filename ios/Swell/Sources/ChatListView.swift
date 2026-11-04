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
                    Image(systemName: "note.text").accessibilityHidden(true).font(.title3).foregroundStyle(.white).frame(width: 50, height: 50).background(Circle().fill(Ocean.bubble))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Note to Self").font(.headline)
                        Text("Only you can see this chat").font(.subheadline).foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 4)
            }.listRowBackground(Ocean.background).listRowSeparatorTint(Ocean.hairline)
            ForEach(listed) { c in row(c) }
            if archivedCount > 0 {
                NavigationLink { ArchivedChats() } label: {
                    Label("Archived chats (\(archivedCount))", systemImage: "archivebox").font(.subheadline).foregroundStyle(.secondary).padding(.vertical, 6)
                }.listRowBackground(Ocean.background).listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private var listed: [Contact] {
        model.contacts.filter { (model.flags[$0.id] ?? 0) & 2 == 0 }.sorted { a, b in
            let pa = (model.flags[a.id] ?? 0) & 1, pb = (model.flags[b.id] ?? 0) & 1
            return pa != pb ? pa > pb : a.lastAt > b.lastAt
        }
    }
    private var archivedCount: Int { model.contacts.filter { (model.flags[$0.id] ?? 0) & 2 != 0 }.count }

    @ViewBuilder fileprivate func row(_ c: Contact) -> some View {
        let f = model.flags[c.id] ?? 0
        Group {
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
                                if c.unread == 0 && f & 4 != 0 { Circle().fill(Ocean.bubble).frame(width: 12, height: 12).accessibilityLabel("Marked unread") }
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
                    Button { model.setFlag(c.id, 2, f & 2 == 0) } label: { Label(f & 2 == 0 ? "Archive" : "Unarchive", systemImage: "archivebox") }.tint(.indigo)
                    Button { model.setBlocked(c.id, !c.blocked) } label: { Label(c.blocked ? "Unblock" : "Block", systemImage: "nosign") }.tint(.orange)
                }
                .swipeActions(edge: .leading) {
                    Button { model.setFlag(c.id, 1, f & 1 == 0) } label: { Label(f & 1 == 0 ? "Pin" : "Unpin", systemImage: f & 1 == 0 ? "pin" : "pin.slash") }.tint(Ocean.accent)
                    Button { model.setFlag(c.id, 4, f & 4 == 0) } label: { Label(f & 4 == 0 ? "Mark unread" : "Mark read", systemImage: "envelope.badge") }.tint(.teal)
                }
                .contextMenu {
                    Button { model.setFlag(c.id, 1, f & 1 == 0) } label: { Label(f & 1 == 0 ? "Pin" : "Unpin", systemImage: "pin") }
                    Button { model.setFlag(c.id, 4, f & 4 == 0) } label: { Label(f & 4 == 0 ? "Mark unread" : "Mark read", systemImage: "envelope.badge") }
                    Button { model.setFlag(c.id, 2, f & 2 == 0) } label: { Label(f & 2 == 0 ? "Archive" : "Unarchive", systemImage: "archivebox") }
                    Button { model.setVerified(c.id, !c.verified) } label: { Label(c.verified ? "Unverify" : "Verify", systemImage: "checkmark.seal") }
                }
                .listRowBackground(Ocean.background)
                .listRowSeparatorTint(Ocean.hairline)
        }
    }
}

struct ArchivedChats: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        List {
            ForEach(model.contacts.filter { (model.flags[$0.id] ?? 0) & 2 != 0 }) { c in
                NavigationLink(value: c.id) {
                    HStack(spacing: 14) { Avatar(name: c.displayName, size: 44, photo: model.avatars[c.id]); Text(c.displayName).font(.headline) }
                }
                .swipeActions { Button { model.setFlag(c.id, 2, false) } label: { Label("Unarchive", systemImage: "archivebox") }.tint(.indigo) }
                .listRowBackground(Ocean.background)
            }
        }.listStyle(.plain).scrollContentBackground(.hidden).background(Ocean.background).navigationTitle("Archived")
    }
}

private struct EmptyChats: View {
    let add: () -> Void
    let invite: () -> Void
    var body: some View {
        ZStack {
            WaveBackdrop().ignoresSafeArea()
            VStack(spacing: 16) {
                Spacer()
                BrandMark(size: 92)
                Text("Your chats will live here").font(.title2.bold())
                Text("Invite a friend with a link. When they open it, you are connected, with no phone number or sign-up.")
                    .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button(action: invite) { Label("Invite a friend", systemImage: "person.badge.plus").font(.title3.weight(.semibold)) }
                    .buttonStyle(PrimaryButton()).padding(.top, 6)
                Button("I have a link or code", action: add).buttonStyle(SecondaryButton())
                Spacer()
            }.padding(.horizontal, 32)
        }
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
