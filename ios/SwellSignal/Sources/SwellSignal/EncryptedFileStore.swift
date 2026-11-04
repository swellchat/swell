import Foundation
import CryptoKit
import SwellCore

public enum StoreError: Error, Equatable {
    case corrupt          // file exists but cannot be opened with this key: never replaced by an empty store
    case write(String)
}

/// Persistent [KeyValueStore]: the whole state is one AES-256-GCM sealed file.
///
/// - Key: 32 bytes supplied by the caller (the app keeps it in the Keychain, this device only).
/// - Crash safety: every change writes a temporary file, fsyncs it (F_FULLFSYNC), then renames it over
///   the old file. A crash leaves either the old or the new file, never half of one. `batch` applies
///   several puts/deletes with one such write, so a ratchet step and its stored message commit together.
/// - On iOS the file has data protection "complete until first user authentication": readable after the
///   first unlock since boot, so a locked phone can still be used by short background work later.
/// - A file that cannot be opened (wrong key, damaged) throws [StoreError.corrupt]; it is never
///   silently replaced by an empty store.
public final class EncryptedFileStore: KeyValueStore, @unchecked Sendable {
    public let url: URL
    private let key: SymmetricKey
    private var m: [String: [UInt8]] = [:]
    private let lock = NSRecursiveLock()
    private var depth = 0
    private var dirty = false
    /// Set when the last write to disk failed (disk full ...). A failed write is rolled back in memory and
    /// reported to the caller (`batch` throws), so memory never gets ahead of what is on disk.
    public private(set) var lastWriteError: String?
    /// Set by `invalidate()` / `destroy()`: every later write fails, so a late task cannot recreate the file.
    private var closed = false
    static let maxFileBytes = 64 << 20

    static let magic = Array("SWKV1".utf8)

    public init(url: URL, key: [UInt8]) throws {
        guard key.count == 32 else { throw StoreError.write("key size") }
        self.url = url
        self.key = SymmetricKey(data: key)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: tmpURL) // leftover of a crash before the rename
        if FileManager.default.fileExists(atPath: url.path) { try loadFile() }
    }

    private var tmpURL: URL { url.appendingPathExtension("tmp") }
    private func k(_ ns: String, _ key: String) -> String { "\(ns)\u{0}\(key)" }

    // MARK: file format

    private func plain() -> [UInt8] {
        let keys = m.keys.sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }
        let o = Out().i32(Int32(keys.count))
        for key in keys {
            let parts = key.split(separator: "\u{0}", maxSplits: 1, omittingEmptySubsequences: false)
            o.str(String(parts[0])).str(String(parts[1])).bytes(m[key]!)
        }
        return o.done()
    }

    private func loadFile() throws {
        guard let data = try? Data(contentsOf: url), data.count > Self.magic.count + 28,
              Array(data.prefix(Self.magic.count)) == Self.magic else { throw StoreError.corrupt }
        do {
            let box = try AES.GCM.SealedBox(combined: data.dropFirst(Self.magic.count))
            let opened = try AES.GCM.open(box, using: key, authenticating: Data(Self.magic))
            let r = In([UInt8](opened))
            var loaded: [String: [UInt8]] = [:]
            // Sizes: Signal records and messages are small; the whole file is capped at 64 MB.
            for _ in 0..<Int(try r.i32()) {
                let ns = try r.str(), key = try r.str()
                loaded[k(ns, key)] = try r.bytes(max: 64 << 20)
            }
            try r.end()
            m = loaded
        } catch { throw StoreError.corrupt }
    }

    private func flushLocked() throws {
        guard depth == 0, dirty else { return }
        do {
            if closed { throw StoreError.write("store closed") }
            let plainBytes = plain()
            guard plainBytes.count <= Self.maxFileBytes else { throw StoreError.write("store full") }
            let sealed = try AES.GCM.seal(Data(plainBytes), using: key, authenticating: Data(Self.magic))
            var file = Data(Self.magic)
            file.append(sealed.combined!)
            let fm = FileManager.default
            fm.createFile(atPath: tmpURL.path, contents: nil)
            let h = try FileHandle(forWritingTo: tmpURL)
            try h.truncate(atOffset: 0)
            try h.write(contentsOf: file)
            if fcntl(h.fileDescriptor, F_FULLFSYNC) != 0 { try h.synchronize() }
            try h.close()
            #if os(iOS)
            try fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: tmpURL.path)
            #endif
            if rename(tmpURL.path, url.path) != 0 { throw StoreError.write("rename \(errno)") }
            // The rename itself must survive power loss: sync the directory entry too.
            let dfd = open(url.deletingLastPathComponent().path, O_RDONLY)
            if dfd >= 0 { _ = fcntl(dfd, F_FULLFSYNC); close(dfd) }
            dirty = false
            lastWriteError = nil
        } catch {
            lastWriteError = "\(error)"
            throw (error as? StoreError) ?? StoreError.write("\(error)")
        }
    }

    // MARK: KeyValueStore

    public func get(_ ns: String, _ key: String) -> [UInt8]? { lock.withLock { m[k(ns, key)] } }

    public func put(_ ns: String, _ key: String, _ value: [UInt8]) {
        lock.withLock {
            let old = m[k(ns, key)]
            m[k(ns, key)] = value; dirty = true
            if (try? flushLocked()) == nil, depth == 0 { m[k(ns, key)] = old; dirty = false } // memory never ahead of disk
        }
    }

    public func delete(_ ns: String, _ key: String) {
        lock.withLock {
            guard let old = m.removeValue(forKey: k(ns, key)) else { return }
            dirty = true
            if (try? flushLocked()) == nil, depth == 0 { m[k(ns, key)] = old; dirty = false }
        }
    }

    public func list(_ ns: String) -> [(key: String, value: [UInt8])] {
        lock.withLock {
            m.filter { $0.key.hasPrefix(ns + "\u{0}") }.map { (String($0.key.dropFirst(ns.count + 1)), $0.value) }
        }
    }

    /// All-or-nothing: if `body` throws, memory is rolled back and nothing is written.
    public func batch(_ body: () throws -> Void) throws {
        try lock.withLock {
            let snapshot = m, wasDirty = dirty
            depth += 1
            do { try body() } catch {
                depth -= 1; m = snapshot; dirty = wasDirty
                throw error
            }
            depth -= 1
            do { try flushLocked() } catch {
                m = snapshot; dirty = wasDirty // the write failed: nothing is committed, the caller must not ACK or go on
                throw error
            }
        }
    }

    /// Makes every later write fail (identity being deleted). Waits for a write in progress.
    public func invalidate() { lock.withLock { closed = true } }

    /// Removes the file (identity deleted) and returns whether it is gone. Later writes fail.
    @discardableResult
    public func destroy() -> Bool {
        lock.withLock {
            closed = true; m = [:]; dirty = false
            try? FileManager.default.removeItem(at: url); try? FileManager.default.removeItem(at: tmpURL)
            return !FileManager.default.fileExists(atPath: url.path) && !FileManager.default.fileExists(atPath: tmpURL.path)
        }
    }
}
