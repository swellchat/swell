import SwiftUI
import AVFoundation
import SwellCore
import SwellSignal

struct ChatView: View {
    @EnvironmentObject var model: AppModel
    let contactId: String
    @State private var draft = ""
    @State private var showInfo = Debug.has("-swellInfo") || Debug.has("-swellInfoSafety")
    @State private var replyTo: ChatMessage?
    @State private var editing: ChatMessage?
    @State private var deleting: ChatMessage?
    @State private var lastTyping = Date.distantPast
    @State private var typingOff: Task<Void, Never>?
    @StateObject private var recorder = VoiceRecorder()
    @FocusState private var focused: Bool
    @State private var showAttach = false
    @State private var picker: AttachKind?
    @State private var pendingPicker: AttachKind?
    @State private var cameraDenied = false
    @State private var attachNote: String?

    private var isNote: Bool { contactId == Engine.noteId }
    private var contact: Contact? { model.contacts.first { $0.id == contactId } }
    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var title: String { isNote ? "Note to Self" : contact?.displayName ?? "Chat" }
    private var lastOutgoing: String? { model.messages.last(where: { !$0.incoming && !$0.deleted })?.id }

    static func timerName(_ s: Int32) -> String {
        switch s { case 0: return "Off"; case 3600: return "1 hour"; case 86400: return "1 day"; case 7 * 86400: return "7 days"
        default: return s % 86400 == 0 ? "\(s / 86400) days" : s % 3600 == 0 ? "\(s / 3600) hours" : "\(s) seconds" }
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 4) {
                    if let c = contact, c.expireSec > 0 {
                        Label("Messages disappear after \(Self.timerName(c.expireSec))", systemImage: "timer")
                            .font(.footnote).foregroundStyle(.secondary).padding(.vertical, 6)
                    }
                    if model.messages.isEmpty && model.messagesLoaded {
                        VStack(spacing: 8) {
                            Image(systemName: isNote ? "note.text" : "lock.fill").font(.title2).foregroundStyle(Ocean.accent)
                            Text(isNote ? "Save notes for yourself" : "Say hello").font(.headline)
                            Text(isNote ? "Only you can see this chat. Nothing is sent." : "Messages are end-to-end encrypted.")
                                .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        }.padding(.top, 60)
                    }
                    ForEach(model.messages) { m in
                        Bubble(message: m, quoted: quote(for: m), isLast: m.id == lastOutgoing, isNote: isNote,
                               peerCanModify: isNote || (contact?.supportsV11 ?? false),
                               retry: { model.retry(m) }, reply: { replyTo = m; editing = nil; focused = true },
                               edit: { editing = m; replyTo = nil; draft = m.text; focused = true },
                               delete: { deleting = m })
                            .id(m.id)
                    }
                }.padding(.horizontal, 16).padding(.vertical, 12)
            }
            .scrollDismissesKeyboard(.interactively)
            .defaultScrollAnchor(.bottom)
            .onChange(of: model.messages.last?.id) { _, id in if let id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } } }
            .onChange(of: model.messages.last?.status) { _, _ in if let id = model.messages.last?.id { proxy.scrollTo(id, anchor: .bottom) } }
        }
        .background(Ocean.background.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) { composer }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: 8) {
                    if isNote { Image(systemName: "note.text").accessibilityHidden(true).foregroundStyle(Ocean.accent) }
                    else { Avatar(name: title, size: 28, photo: model.avatars[contactId]) }
                    VStack(spacing: 0) {
                        Text(title).font(.headline).lineLimit(1)
                        if let c = contact, c.expireSec > 0 {
                            Text("Disappearing: \(Self.timerName(c.expireSec))").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            if !isNote {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { showInfo = true } label: { Label("Contact info", systemImage: "info.circle") }
                        Menu {
                            ForEach([Int32(0), 3600, 86400, 7 * 86400], id: \.self) { s in
                                Button { model.setDisappearing(contactId, seconds: Int(s)) } label: {
                                    if (contact?.expireSec ?? 0) == s { Label(Self.timerName(s), systemImage: "checkmark") } else { Text(Self.timerName(s)) }
                                }
                            }
                        } label: { Label("Disappearing messages", systemImage: "timer") }
                    } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("Chat options")
                }
            }
        }
        .sheet(isPresented: $showInfo) { ContactInfo(contactId: contactId) }
        .confirmationDialog("Delete this message?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible, presenting: deleting) { m in
            Button("Delete for me", role: .destructive) { model.deleteHere(m) }
            if !m.incoming && (isNote || (contact?.supportsV11 ?? false)) {
                Button("Delete for everyone", role: .destructive) { model.deleteForEveryone(m) }
            }
        } message: { m in
            Text(!m.incoming && !isNote && !(contact?.supportsV11 ?? false) ? "This contact's app is older, so the message can only be removed from this iPhone." : "")
        }
        .sheet(isPresented: $showAttach, onDismiss: { picker = pendingPicker; pendingPicker = nil }) {
            AttachSheet { k in
                if k == .camera {
                    let st = AVCaptureDevice.authorizationStatus(for: .video)
                    if st == .denied || st == .restricted { showAttach = false; cameraDenied = true; return }
                    if !UIImagePickerController.isSourceTypeAvailable(.camera) { showAttach = false; attachNote = "This iPhone has no camera available."; return }
                }
                pendingPicker = k; showAttach = false
            }
                .presentationDetents([.height(300)]).presentationDragIndicator(.visible)
        }
        .fullScreenCover(item: $picker) { k in
            switch k {
            case .photos: PhotoPicker { d in picker = nil; sendPhoto(d) }.ignoresSafeArea()
            case .camera: CameraPicker { d in picker = nil; sendPhoto(d) }.ignoresSafeArea()
            case .files: FilePicker { f, note in
                    picker = nil
                    if let f { sendPicked(f.data, name: f.name, mime: f.mime) } else if let note { attachNote = note }
                }.ignoresSafeArea()
            }
        }
        .alert("Not sent", isPresented: Binding(get: { attachNote != nil }, set: { if !$0 { attachNote = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(attachNote ?? "") }
        .onChange(of: model.problem) { _, p in if let p { attachNote = p; model.problem = nil } }
        .alert("Camera is off", isPresented: $cameraDenied) {
            Button("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Allow the camera for Swell in Settings to take photos.") }
        .alert("Microphone is off", isPresented: $recorder.denied) {
            Button("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Allow the microphone for Swell in Settings to record voice notes.") }
        .onAppear {
            model.open(contactId)
            recorder.onFinish = { d, ms in model.sendVoice(d, ms: ms, to: contactId, replyTo: replyTo?.id ?? ""); replyTo = nil }
        }
        .onDisappear { model.close(contactId); if recorder.recording { recorder.finish(send: false) }; VoicePlayer.shared.stop(); if typingOff != nil { model.typing(false, in: contactId) } }
    }

    private func sendPhoto(_ d: Data?) {
        guard let d else { attachNote = "That photo could not be used."; return }
        sendPicked(d, name: PhotoPrep.name(), mime: "image/jpeg")
    }

    private func sendPicked(_ d: Data, name: String, mime: String) {
        let caption = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        model.sendFile(d, name: name, mime: mime, caption: caption, to: contactId, replyTo: replyTo?.id ?? "")
        draft = ""; replyTo = nil
    }

    private func quote(for m: ChatMessage) -> Quote? {
        guard !m.replyTo.isEmpty else { return nil }
        guard let q = model.messages.first(where: { $0.id == m.replyTo }), !q.deleted else { return Quote(text: "Original message unavailable", mine: false, missing: true) }
        return Quote(text: q.preview, mine: !q.incoming, missing: false)
    }

    private var composer: some View {
        VStack(spacing: 0) {
            if let r = replyTo ?? editing {
                HStack(spacing: 8) {
                    Image(systemName: editing != nil ? "pencil" : "arrowshape.turn.up.left").foregroundStyle(Ocean.accent)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(editing != nil ? "Edit message" : (r.incoming ? "Reply to \(title)" : "Reply to yourself")).font(.caption.weight(.semibold)).foregroundStyle(Ocean.accent)
                        Text(r.preview).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Button { if editing != nil { draft = "" }; replyTo = nil; editing = nil } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                        .accessibilityLabel("Cancel")
                }.padding(.horizontal, 16).padding(.vertical, 8)
            }
            if recorder.recording {
                HStack(spacing: 12) {
                    Button { recorder.finish(send: false) } label: { Image(systemName: "trash").font(.title3).foregroundStyle(.red) }.accessibilityLabel("Discard recording")
                    Circle().fill(Color.red).frame(width: 10, height: 10)
                    Text(VoiceBubble.clock(recorder.seconds)).font(.body.monospacedDigit())
                    Text("Recording").font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    Button { recorder.finish(send: true) } label: {
                        Image(systemName: "arrow.up").font(.body.weight(.bold)).foregroundStyle(Ocean.onAccent)
                            .frame(width: 38, height: 38).background(Circle().fill(Ocean.bubble))
                    }.accessibilityLabel("Send voice note")
                }.padding(.horizontal, 16).padding(.vertical, 8)
            } else {
            HStack(alignment: .bottom, spacing: 8) {
                if editing == nil {
                    Button { focused = false; showAttach = true } label: {
                        Image(systemName: "plus").font(.body.weight(.semibold)).foregroundStyle(Ocean.accent)
                            .frame(width: 38, height: 38).background(Circle().fill(Ocean.card))
                            .overlay(Circle().stroke(Ocean.hairline, lineWidth: 0.5))
                    }.accessibilityLabel("Attach")
                }
                TextField("Message", text: $draft, axis: .vertical)
                    .font(.body).lineLimit(1...5).focused($focused)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Ocean.card))
                    .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Ocean.hairline, lineWidth: 0.5))
                    .onChange(of: draft) { _, d in typingTick(d) }
                Button {
                    if let e = editing {
                        model.edit(e, draft); editing = nil
                    } else {
                        model.send(draft, to: contactId, replyTo: replyTo?.id ?? "")
                    }
                    draft = ""; replyTo = nil
                    if typingOff != nil { typingOff?.cancel(); typingOff = nil; model.typing(false, in: contactId) }
                } label: {
                    Image(systemName: editing != nil ? "checkmark" : "arrow.up").font(.body.weight(.bold)).foregroundStyle(Ocean.onAccent)
                        .frame(width: 38, height: 38).background(Circle().fill(Ocean.bubble))
                }
                .disabled(!canSend).opacity(canSend ? 1 : 0.35)
                .opacity(canSend || editing != nil ? 1 : 0).frame(width: canSend || editing != nil ? 38 : 0)
                .accessibilityLabel(editing != nil ? "Save" : "Send")
                if !canSend && editing == nil {
                    Button { focused = false; recorder.start() } label: {
                        Image(systemName: "mic.fill").font(.body.weight(.semibold)).foregroundStyle(Ocean.onAccent)
                            .frame(width: 38, height: 38).background(Circle().fill(Ocean.bubble))
                    }.accessibilityLabel("Record a voice note")
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            }
        }
        .background(.ultraThinMaterial)
    }

    /// Typing: "on" at most every 5 s while the user types, "off" after 3 s of quiet. Direct path only; off by default.
    private func typingTick(_ d: String) {
        guard model.typing, !isNote, editing == nil, !d.isEmpty else { return }
        if Date().timeIntervalSince(lastTyping) > 5 { lastTyping = Date(); model.typing(true, in: contactId) }
        typingOff?.cancel()
        typingOff = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if !Task.isCancelled { model.typing(false, in: contactId); typingOff = nil; lastTyping = .distantPast }
        }
    }
}

struct Quote { let text: String; let mine: Bool; let missing: Bool }

private struct Bubble: View {
    @EnvironmentObject var model: AppModel
    let message: ChatMessage
    let quoted: Quote?
    let isLast: Bool
    let isNote: Bool
    let peerCanModify: Bool
    let retry: () -> Void
    let reply: () -> Void
    let edit: () -> Void
    let delete: () -> Void
    @State private var canEdit = false

    static let quick = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

    /// Two steps for our messages: stored in the mailbox (waiting for the other phone), then delivered to the phone.
    private var status: (symbol: String, text: String, color: Color)? {
        guard !message.incoming, !isNote, !message.deleted else { return nil }
        if message.status == .failed { return ("exclamationmark.circle.fill", "Not sent. Tap to try again", .red) }
        if message.status == .sending { return ("clock", "Sending", .secondary) }
        if message.read { return ("checkmark.circle.fill", "Read", Ocean.accent) }
        if message.onTheirPhone { return ("checkmark.circle", "Delivered to their phone", .secondary) }
        return ("tray.and.arrow.down", "Stored in the mailbox. Waits up to 14 days", .secondary)
    }

    var body: some View {
        let mine = !message.incoming
        HStack {
            if mine { Spacer(minLength: 56) }
            VStack(alignment: mine ? .trailing : .leading, spacing: 3) {
                VStack(alignment: .leading, spacing: 6) {
                    if let q = quoted {
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 1.5).fill(mine ? Color.white.opacity(0.8) : Ocean.accent).frame(width: 3)
                            Text(q.text).font(.footnote).italic(q.missing).lineLimit(2)
                                .foregroundStyle(mine ? Color.white.opacity(0.9) : Color.secondary)
                        }.fixedSize(horizontal: false, vertical: true)
                    }
                    if message.deleted {
                        Label("This message was deleted", systemImage: "nosign").font(.subheadline.italic())
                            .foregroundStyle(mine ? Color.white.opacity(0.85) : Color.secondary)
                    } else if message.isVoice {
                        VoiceBubble(message: message, mine: mine)
                    } else if message.hasFile {
                        if message.isImage { PhotoBubble(message: message, mine: mine) } else { FileBubble(message: message, mine: mine) }
                        if !message.text.isEmpty {
                            Text(message.text).font(.body).foregroundStyle(mine ? Ocean.onAccent : Color.primary).multilineTextAlignment(.leading).textSelection(.enabled)
                        }
                    } else {
                        Text(message.text).font(.body).foregroundStyle(mine ? Ocean.onAccent : Color.primary)
                            .multilineTextAlignment(.leading).textSelection(.enabled)
                    }
                }
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(mine ? AnyShapeStyle(Ocean.bubble) : AnyShapeStyle(Ocean.incoming)))
                .overlay(alignment: mine ? .bottomLeading : .bottomTrailing) { reactions.offset(y: 14) }
                .contextMenu { menu }
                HStack(spacing: 4) {
                    Text(message.sentAt.time).font(.caption2).foregroundStyle(.secondary)
                    if message.edited && !message.deleted { Text("Edited").font(.caption2).foregroundStyle(.secondary) }
                    if let s = status {
                        Image(systemName: s.symbol).font(.caption2).foregroundStyle(s.color)
                        if isLast || message.status == .failed { Text(s.text).font(.caption2).foregroundStyle(s.color) }
                    }
                }
                .padding(.top, message.myReaction.isEmpty && message.theirReaction.isEmpty ? 0 : 14)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(status?.text ?? "")
            }
            .padding(.bottom, 6)
            .onTapGesture { if message.status == .failed { retry() } }
            if !mine { Spacer(minLength: 56) }
        }
        .task { canEdit = await model.canEdit(message) }
    }

    @ViewBuilder private var reactions: some View {
        let list = [message.theirReaction, message.myReaction].filter { !$0.isEmpty }
        if !list.isEmpty, !message.deleted {
            HStack(spacing: 2) { ForEach(Array(list.enumerated()), id: \.offset) { Text($0.element).font(.footnote) } }
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Capsule().fill(Ocean.card)).overlay(Capsule().stroke(Ocean.hairline, lineWidth: 0.5))
                .padding(.horizontal, 8)
        }
    }

    @ViewBuilder private var menu: some View {
        if !message.deleted {
            if !isNote {
                ForEach(Self.quick, id: \.self) { e in
                    Button { model.react(message, e) } label: { Text(message.myReaction == e ? "\(e)  \u{2713}" : e) }
                }
            }
            Button(action: reply) { Label("Reply", systemImage: "arrowshape.turn.up.left") }
            if !message.hasFile || !message.text.isEmpty { Button { UIPasteboard.general.string = message.text } label: { Label("Copy", systemImage: "doc.on.doc") } }
            if canEdit { Button(action: edit) { Label("Edit", systemImage: "pencil") } }
        }
        Button(role: .destructive, action: delete) { Label("Delete", systemImage: "trash") }
    }
}

private extension Int64 {
    var time: String {
        let d = Date(timeIntervalSince1970: Double(self) / 1000)
        return d.formatted(Calendar.current.isDateInToday(d) ? .dateTime.hour().minute() : .dateTime.day().month().hour().minute())
    }
}

private struct ContactInfo: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let contactId: String
    @State private var safety = ""
    @State private var confirm = false
    @State private var newName = ""
    @State private var showReport = false
    @State private var showScan = Debug.has("-swellScan")
    @State private var result: VerifyResult?

    enum VerifyResult: Identifiable { case match, mismatch, notCode
        var id: Int { hashValue } }

    static func payload(_ number: String) -> String { "swell-verify:1:" + number.filter(\.isNumber) }

    var body: some View {
        let c = model.contacts.first { $0.id == contactId }
        NavigationStack {
            ScrollViewReader { sp in
            List {
                Section {
                    VStack(spacing: 8) {
                        Avatar(name: c?.displayName ?? "?", size: 84, photo: model.avatars[contactId])
                        Text(c?.displayName ?? "").font(.title3.weight(.semibold))
                        if let c, !c.nickname.isEmpty, !c.name.isEmpty { Text(c.name).font(.footnote).foregroundStyle(.secondary) }
                    }.frame(maxWidth: .infinity).padding(.vertical, 6)
                }.listRowBackground(Color.clear)
                Section {
                    TextField(c?.name.isEmpty == false ? c!.name : "Name", text: $newName)
                        .onSubmit { model.rename(contactId, newName) }
                } header: { Text("Nickname") } footer: { Text("Only you see this name.") }
                .listRowBackground(Ocean.card)
                Section {
                    Label("You and \(c?.displayName ?? "this contact") have your own private channel. Only the two of you can read what goes through it.", systemImage: "lock.fill")
                    Label("If their phone is off, messages wait for up to 14 days and are delivered when it is back.", systemImage: "tray")
                } header: { Text("Private channel") }
                .font(.subheadline).listRowBackground(Ocean.card)
                Section {
                    Picker(selection: Binding(get: { c?.expireSec ?? 0 }, set: { model.setDisappearing(contactId, seconds: Int($0)) })) {
                        ForEach([Int32(0), 3600, 86400, 7 * 86400], id: \.self) { Text(ChatView.timerName($0)).tag($0) }
                    } label: { Label("Disappearing messages", systemImage: "timer") }
                } footer: { Text("New messages in this chat are removed from both phones after this time.") }
                .listRowBackground(Ocean.card)
                Section {
                    Text(safety.isEmpty ? "…" : safety).font(.system(.body, design: .monospaced))
                    if !safety.isEmpty {
                        HStack { Spacer(); QRCodeView(text: Self.payload(safety), width: 220); Spacer() }
                            .listRowSeparator(.hidden)
                    }
                    Button { showScan = true } label: { Label("Verify in person", systemImage: "qrcode.viewfinder") }.disabled(safety.isEmpty)
                    Toggle("Verified", isOn: Binding(get: { c?.verified ?? false }, set: { model.setVerified(contactId, $0) }))
                } header: { Text("Safety number").id("safety") } footer: {
                    Text("Meet in person: each of you scans the other's code. If the numbers match, nobody is listening in.")
                }
                .listRowBackground(Ocean.card)
                Section {
                    Toggle("Block", isOn: Binding(get: { c?.blocked ?? false }, set: { model.setBlocked(contactId, $0) }))
                    Button("Report this contact", role: .destructive) { showReport = true }
                    Button("Delete contact", role: .destructive) { confirm = true }
                }.listRowBackground(Ocean.card)
            }
            .onAppear { if Debug.has("-swellInfoSafety") { DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { sp.scrollTo("safety", anchor: .top) } } }
            }
            .listStyle(.insetGrouped).scrollContentBackground(.hidden).background(Ocean.background)
            .navigationTitle("Contact").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { model.rename(contactId, newName); dismiss() } } }
            .confirmationDialog("Delete this contact and the chat?", isPresented: $confirm) {
                Button("Delete", role: .destructive) { model.delete(contactId); dismiss() }
            }
            .sheet(isPresented: $showReport) { ReportSheet(contactId: contactId) }
            .fullScreenCover(isPresented: $showScan) {
                ScanPage(title: "Verify in person", hint: "Scan the safety-number code on your contact's phone") { code in
                    showScan = false
                    if !code.hasPrefix("swell-verify:") { result = .notCode }
                    else if code == Self.payload(safety) { result = .match; model.setVerified(contactId, true) }
                    else { result = .mismatch }
                }
            }
            .alert(item: $result) { r in
                switch r {
                case .match: return Alert(title: Text("Verified"), message: Text("The numbers match. This chat is private between you and \(c?.displayName ?? "your contact")."))
                case .mismatch: return Alert(title: Text("Numbers do not match"), message: Text("Do not trust this chat until you have checked in person. Someone may be in the middle."))
                case .notCode: return Alert(title: Text("Not a safety-number code"), message: Text("Scan the code under Safety number on your contact's phone."))
                }
            }
            .task {
                safety = await model.safetyNumber(contactId) ?? ""
                #if DEBUG
                if safety.isEmpty, CommandLine.arguments.contains("-swellDemo") { safety = "31415 92653 58979 32384 62643 38327 95028 84197 16939 93751 05820 97494" }
                #endif
                newName = c?.nickname ?? ""
            }
        }
    }
}

/// Report: reason, optional note, and the contact's last messages only if the switch stays on.
/// The user sees exactly what is sent. The report is sealed to Jiji Labs and does not say who sent it.
private struct ReportSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let contactId: String
    static let reasons = ["Spam or scam", "Harassment or hate", "Threats or violence", "Sexual content involving a child", "Something else"]
    @State private var reason = ReportSheet.reasons[0]
    @State private var note = ""
    @State private var include = true
    @State private var block = true
    @State private var recent: [ChatMessage] = []
    @State private var sending = false
    @State private var error: String?
    @State private var sent = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("The report goes encrypted to Jiji Labs. It does not say who you are.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Reason") {
                    ForEach(Self.reasons, id: \.self) { r in
                        Button { reason = r } label: {
                            HStack { Text(r).font(.body).foregroundStyle(Color.primary); Spacer()
                                if reason == r { Image(systemName: "checkmark").foregroundStyle(Ocean.accent) } }
                        }
                    }
                }
                Section("What happened? (optional)") {
                    TextField("Note", text: $note, axis: .vertical).lineLimit(1...4)
                        .onChange(of: note) { _, n in if n.count > 500 { note = String(n.prefix(500)) } }
                }
                Section {
                    Toggle("Include their last \(recent.count) messages", isOn: $include).disabled(recent.isEmpty)
                    if include { ForEach(recent) { Text("• " + $0.text.prefix(120)).font(.caption).foregroundStyle(.secondary) } }
                    Toggle("Also block this contact", isOn: $block)
                }
                Section {
                    if let error { Text(error).font(.footnote).foregroundStyle(.red) }
                    if sending { HStack(spacing: 10) { ProgressView(); Text("Sending…").font(.footnote).foregroundStyle(.secondary) } }
                    Button(sent ? "Report sent. Thank you." : "Send report", role: .destructive) {
                        error = nil; sending = true
                        Task {
                            let text = reason
                            if let e = await model.report(contactId, reason: text, note: note, includeLast: include ? recent.count : 0, block: block) { error = e }
                            else { sent = true; try? await Task.sleep(nanoseconds: 900_000_000); dismiss() }
                            sending = false
                        }
                    }.disabled(sending || sent)
                }
            }
            .listStyle(.insetGrouped).scrollContentBackground(.hidden).background(Ocean.background)
            .navigationTitle("Report").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(sending) } }
            .interactiveDismissDisabled(sending)
            .task { recent = await model.recentIncoming(contactId, 10) }
        }
    }
}


enum AttachKind: String, Identifiable { case photos, camera, files; var id: String { rawValue } }

private struct AttachSheet: View {
    let choose: (AttachKind) -> Void
    var body: some View {
        VStack(spacing: 0) {
            Text("Send").font(.headline).padding(.top, 22).padding(.bottom, 10)
            row("photo.on.rectangle", "Photos", .photos)
            row("camera", "Camera", .camera)
            row("doc", "Files", .files)
            Spacer(minLength: 0)
        }
    }
    private func row(_ icon: String, _ title: String, _ k: AttachKind) -> some View {
        Button { choose(k) } label: {
            HStack(spacing: 14) {
                Image(systemName: icon).font(.title3).foregroundStyle(Ocean.onAccent).frame(width: 40, height: 40).background(Circle().fill(Ocean.bubble))
                Text(title).font(.body).foregroundStyle(Color.primary)
                Spacer()
            }.padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}
