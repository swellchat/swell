import SwiftUI
import SwellCore

struct OnboardingView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        switch model.stage {
        case .welcome: WelcomeStep()
        case .phrase: PhraseStep()
        case .restore: RestoreStep()
        case .name: NameStep()
        case .ready: EmptyView()
        case .locked: LockedStep()
        }
    }
}

private struct Page<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 16) { content }.padding(.horizontal, 20).padding(.vertical, 24) }
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
    }
}

private struct Heading: View {
    let title: String
    let detail: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.largeTitle.bold())
            if let detail { Text(detail).font(.body).foregroundStyle(.secondary) }
        }
    }
}

struct TermsSheet: View {
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Terms of Use").font(.title2.bold())
                    Text("Swell is for kind, lawful conversations. There is no tolerance for abusive content or abusive people.").font(.body)
                    Text("You agree not to send spam, scams, harassment, hate, threats, sexual content involving children, or anything illegal.").font(.body)
                    Text("Every chat has Block and Report. Jiji Labs reviews reports and acts on them within 24 hours, including removing access for people who break these rules.").font(.body)
                    Text("Your messages are end-to-end encrypted, so Jiji Labs cannot read them. A report only includes what you choose to send.").font(.body)
                    Text("Questions or complaints: support@jijilabsdigital.com. Privacy: privacy@jijilabsdigital.com.").font(.footnote).foregroundStyle(.secondary)
                    Link("Email support", destination: URL(string: "mailto:support@jijilabsdigital.com")!).font(.body.weight(.medium))
                }.padding(20)
            }
            .background(Ocean.background.ignoresSafeArea())
            .navigationTitle("Terms").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

private struct WelcomeStep: View {
    @EnvironmentObject var model: AppModel
    @State private var agreed = false
    @State private var showTerms = false
    var body: some View {
        Page {
            HStack { Spacer(); BrandMark(size: 120); Spacer() }.padding(.top, 8)
            VStack(spacing: 8) {
                Text("Swell").font(.largeTitle.bold())
                Text("Private chat without a phone number.").font(.title3).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }.frame(maxWidth: .infinity)
            VStack(alignment: .leading, spacing: 14) {
                Point(symbol: "person.crop.circle.badge.xmark", text: "No phone number, email or username.")
                Point(symbol: "lock.fill", text: "Messages are end-to-end encrypted.")
                Point(symbol: "network", text: "Messages are routed privately, so your contacts never see where you are.")
                Point(symbol: "hand.raised.fill", text: "Free. No ads. No tracking.")
            }.padding(.vertical, 8)
            Toggle(isOn: $agreed) {
                HStack(spacing: 0) {
                    Text("I agree to the ").font(.footnote)
                    Button("Terms of Use") { showTerms = true }.font(.footnote.weight(.semibold))
                }
            }.toggleStyle(CheckToggle())
            Button("Get started") { model.createAccount() }.buttonStyle(PrimaryButton()).disabled(!agreed)
            Button("I have a recovery phrase") { model.stage = .restore }.buttonStyle(SecondaryButton()).disabled(!agreed)
        }
        .sheet(isPresented: $showTerms) { TermsSheet() }
    }
}

private struct Point: View {
    let symbol: String
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol).font(.body).foregroundStyle(Ocean.accent).frame(width: 28)
            Text(text).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct PhraseStep: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        let words = model.account?.phrase ?? []
        Page {
            Heading(title: "Your recovery phrase", detail: "Write these 12 words on paper and keep them safe. They are the only way to get your account back on a new phone. Anyone with these words can become you.")
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 2), spacing: 8) {
                ForEach(Array(words.enumerated()), id: \.offset) { i, w in
                    HStack(spacing: 8) {
                        Text("\(i + 1)").font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(minWidth: 18, alignment: .trailing)
                        Text(w).font(.body.weight(.medium)).lineLimit(1).minimumScaleFactor(0.7)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Ocean.card))
                }
            }
            Button("I wrote the 12 words down") { model.stage = .name }.buttonStyle(PrimaryButton()).padding(.top, 8)
        }
        .privacySensitive()
    }
}

private struct RestoreStep: View {
    @EnvironmentObject var model: AppModel
    @State private var text = ""
    @State private var problem: String?
    var body: some View {
        Page {
            Heading(title: "Restore your account", detail: "Type your 12 words, separated by spaces.")
            TextField("12 words", text: $text, axis: .vertical)
                .font(.body).lineLimit(3...6)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .fieldBox()
            if let problem { Text(problem).font(.footnote).foregroundStyle(.red) }
            Text("The words bring back your identity only. Your contacts and old messages are not restored: you add your contacts again with new codes.")
                .font(.footnote).foregroundStyle(.secondary)
            Button("Restore") {
                do { try model.restore(words: text); problem = nil }
                catch SwellError.format(let m) { problem = m }
                catch { problem = "That did not work." }
            }.buttonStyle(PrimaryButton()).disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("Back") { model.stage = .welcome }.buttonStyle(SecondaryButton())
        }
    }
}

private struct NameStep: View {
    @EnvironmentObject var model: AppModel
    @State private var name = ""
    var body: some View {
        Page {
            Heading(title: "Your name", detail: "Optional. Only people you add will see it.")
            TextField("Name", text: $name).font(.body).fieldBox().submitLabel(.done).onSubmit { model.saveName(name) }
            Button("Continue") { model.saveName(name) }.buttonStyle(PrimaryButton())
            Button("Skip") { model.saveName("") }.buttonStyle(SecondaryButton())
        }
    }
}

private struct LockedStep: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        Page {
            Image(systemName: "lock.trianglebadge.exclamationmark").font(.system(size: 44)).foregroundStyle(Ocean.accent)
            Heading(title: "Swell cannot open its secure storage", detail: "The iPhone keychain did not answer. Your data is safe and nothing was changed. Unlock your iPhone and try again.")
            if let p = model.problem { Text(p).font(.footnote).foregroundStyle(.red) }
            Button("Try again") { model.retryStart() }.buttonStyle(PrimaryButton())
        }
    }
}

private struct CheckToggle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 10) {
            Button { configuration.isOn.toggle() } label: {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square").font(.title3)
                    .foregroundStyle(configuration.isOn ? Ocean.accent : Color.secondary)
            }.buttonStyle(.plain).accessibilityLabel("Agree to terms").accessibilityValue(configuration.isOn ? "on" : "off")
            configuration.label
            Spacer(minLength: 0)
        }
    }
}
