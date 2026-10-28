import SwiftUI

struct MessagesView: View {
    @EnvironmentObject private var state: AppState
    @ViewState private var composing = false

    var body: some View {
        Group {
            if !state.connected {
                EmptyState(symbol: "iphone.gen3.slash", title: "Phone not connected",
                           message: "Your text conversations appear here once your phone connects.")
            } else {
                HStack(spacing: 0) {
                    threadList.frame(width: 290)
                    Divider().opacity(0.4)
                    conversation.frame(maxWidth: .infinity)
                }
            }
        }
        .sheet(isPresented: $composing) { ComposeSheet() }
        .onChange(of: state.selectedThread) { _, id in
            if let id { state.requestMessages(thread: id) }
        }
    }

    private var threadList: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Messages").font(.system(size: 24, weight: .bold, design: .rounded))
                Spacer()
                Button { state.requestThreads() } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.pressable(1.15))
                    .help("Refresh")
                Button { composing = true } label: { Image(systemName: "square.and.pencil") }
                    .buttonStyle(.pressable(1.15))
                    .help("New message")
            }
            .font(.system(size: 15, weight: .medium))
            .padding(.horizontal, 18)
            .padding(.top, 30)
            .padding(.bottom, 10)

            ScrollView {
                LazyVStack(spacing: 4) {
                    ForEach(state.threads) { t in
                        ThreadRow(thread: t, selected: state.selectedThread == t.id)
                            .onTapGesture { withAnimation(Theme.spring) { state.selectedThread = t.id } }
                    }
                }
                .padding(.horizontal, 10)
                .padding(.bottom, 12)
            }
            .overlay {
                if state.threads.isEmpty {
                    Text("No conversations yet.\nAllow SMS access in the Android app.")
                        .multilineTextAlignment(.center).foregroundStyle(.secondary)
                }
            }
        }
        .background(.ultraThinMaterial)
    }

    @ViewBuilder
    private var conversation: some View {
        if let id = state.selectedThread, let thread = state.threads.first(where: { $0.id == id }) {
            ConversationView(thread: thread, messages: state.messages[id] ?? [])
                .id(id)
                .transition(.opacity)
        } else {
            EmptyState(symbol: "bubble.left.and.bubble.right", title: "Pick a conversation",
                       message: "Read and reply to your texts with your Mac's keyboard.")
        }
    }
}

struct ThreadRow: View {
    let thread: SmsThread
    let selected: Bool
    @ViewState private var hovering = false

    var body: some View {
        HStack(spacing: 12) {
            Avatar(name: thread.name ?? thread.address, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(thread.name ?? thread.address)
                        .font(.callout.weight(thread.unread == true ? .bold : .semibold))
                        .lineLimit(1)
                    Spacer()
                    Text(Date(timeIntervalSince1970: thread.date / 1000), format: .dateTime.month(.abbreviated).day())
                        .font(.caption).foregroundStyle(selected ? .white.opacity(0.8) : .secondary)
                }
                Text(thread.snippet).font(.caption).lineLimit(2)
                    .foregroundStyle(selected ? .white.opacity(0.85) : .secondary)
            }
            if thread.unread == true && !selected {
                Circle().fill(Theme.cyan).frame(width: 8, height: 8)
            }
        }
        .padding(10)
        .foregroundStyle(selected ? .white : .primary)
        .background {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(selected ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.primary.opacity(hovering ? 0.06 : 0)))
        }
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .animation(Theme.spring, value: hovering)
    }
}

struct ConversationView: View {
    @EnvironmentObject private var state: AppState
    let thread: SmsThread
    let messages: [SmsMessage]
    @ViewState private var draft = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Avatar(name: thread.name ?? thread.address, size: 34)
                VStack(alignment: .leading, spacing: 0) {
                    Text(thread.name ?? thread.address).font(.headline)
                    if thread.name != nil { Text(thread.address).font(.caption).foregroundStyle(.secondary) }
                }
                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.top, 28)
            .padding(.bottom, 12)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(messages) { m in
                            Bubble(message: m)
                                .id(m.id)
                                .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                }
                .onAppear { proxy.scrollTo(messages.last?.id, anchor: .bottom) }
                .onChange(of: messages.last?.id) { _, last in
                    withAnimation(Theme.spring) { proxy.scrollTo(last, anchor: .bottom) }
                }
            }

            HStack(spacing: 10) {
                TextField("Text message", text: $draft, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(1...5)
                    .focused($focused)
                    .onSubmit(send)
                Button(action: send) {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 26)).foregroundStyle(Theme.accent)
                }
                .buttonStyle(.pressable(1.12))
                .keyboardShortcut(.return, modifiers: .command)
                .disabled(draft.trimmed.isEmpty)
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(Capsule().strokeBorder(focused ? Theme.cyan.opacity(0.6) : Color.white.opacity(0.08)))
            .animation(Theme.spring, value: focused)
            .padding(16)
        }
        .onAppear { focused = true }
    }

    private func send() {
        let text = draft.trimmed
        guard !text.isEmpty else { return }
        state.sendSms(to: thread.address, text: text)
        draft = ""
    }
}

private struct Bubble: View {
    let message: SmsMessage

    var body: some View {
        HStack {
            if message.outgoing { Spacer(minLength: 80) }
            Text(message.body)
                .textSelection(.enabled)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .foregroundStyle(message.outgoing ? .white : .primary)
                .background {
                    if message.outgoing {
                        RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.accent)
                    } else {
                        RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial)
                    }
                }
                .help(Date(timeIntervalSince1970: message.date / 1000).formatted(date: .abbreviated, time: .shortened))
            if !message.outgoing { Spacer(minLength: 80) }
        }
    }
}

struct ComposeSheet: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.dismiss) private var dismiss
    @ViewState private var to = ""
    @ViewState private var text = ""

    /// Contacts matching what's typed, hidden once a full number from a contact is chosen.
    private var suggestions: [PhoneContact] {
        let q = to.trimmed.lowercased()
        guard q.count >= 2 else { return [] }
        let digits = q.filter(\.isNumber)
        let matches = state.contacts.filter { c in
            c.name.lowercased().contains(q) || (!digits.isEmpty && c.numbers.contains { $0.filter(\.isNumber).contains(digits) })
        }
        if matches.count == 1, matches[0].numbers.contains(to) { return [] }
        return Array(matches.prefix(5))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                IconBadge(symbol: "square.and.pencil", size: 32)
                Text("New message").font(.title3.weight(.semibold))
            }
            TextField("Name or phone number", text: $to).textFieldStyle(.roundedBorder)
            if !suggestions.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(suggestions, id: \.self) { c in
                        Button {
                            to = c.numbers.first ?? to
                        } label: {
                            HStack(spacing: 10) {
                                Avatar(name: c.name, size: 26)
                                VStack(alignment: .leading, spacing: 0) {
                                    Text(c.name).font(.callout.weight(.medium))
                                    Text(c.numbers.first ?? "").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                            }
                            .padding(6)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.pressable(1.01))
                    }
                }
                .padding(4)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            TextField("Message", text: $text, axis: .vertical).textFieldStyle(.roundedBorder).lineLimit(3...8)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                Button("Send") {
                    state.sendSms(to: to, text: text)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .disabled(to.isEmpty || text.isEmpty)
            }
        }
        .padding(22)
        .frame(width: 400)
        .animation(Theme.spring, value: suggestions.count)
    }
}
