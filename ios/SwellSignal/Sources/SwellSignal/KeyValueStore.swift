import Foundation
import SwellCore

/// Namespaced byte storage, same shape as the Kotlin `Kv`. The app backs it with an encrypted file
/// or database; tests use [MemoryKeyValueStore]. All secrets (identity-derived records, sessions) live here.
public protocol KeyValueStore: AnyObject {
    func get(_ ns: String, _ key: String) -> [UInt8]?
    func put(_ ns: String, _ key: String, _ value: [UInt8])
    func delete(_ ns: String, _ key: String)
    func list(_ ns: String) -> [(key: String, value: [UInt8])]
    /// Applies all writes made inside `body` as one durable step: either all are on disk or none.
    func batch(_ body: () throws -> Void) throws
}

public extension KeyValueStore {
    func batch(_ body: () throws -> Void) throws { try body() }
}

public final class MemoryKeyValueStore: KeyValueStore {
    private var m: [String: [UInt8]] = [:]
    private let lock = NSLock()
    public init() {}
    private func k(_ ns: String, _ key: String) -> String { "\(ns)\u{0}\(key)" }
    public func get(_ ns: String, _ key: String) -> [UInt8]? { lock.withLock { m[k(ns, key)] } }
    public func put(_ ns: String, _ key: String, _ value: [UInt8]) { lock.withLock { m[k(ns, key)] = value } }
    public func delete(_ ns: String, _ key: String) { lock.withLock { _ = m.removeValue(forKey: k(ns, key)) } }
    public func list(_ ns: String) -> [(key: String, value: [UInt8])] {
        lock.withLock {
            m.filter { $0.key.hasPrefix(ns + "\u{0}") }.map { (String($0.key.dropFirst(ns.count + 1)), $0.value) }
        }
    }

    /// Snapshot in the cross-platform fixture layout: `i32 n, (str ns, str key, bytes value) * n`, sorted by key.
    public func dump() -> [UInt8] {
        lock.withLock {
            let keys = m.keys.sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }
            let o = Out().i32(Int32(keys.count))
            for key in keys {
                let parts = key.split(separator: "\u{0}", maxSplits: 1, omittingEmptySubsequences: false)
                o.str(String(parts[0])).str(String(parts[1])).bytes(m[key]!)
            }
            return o.done()
        }
    }

    public static func load(_ b: [UInt8]) throws -> MemoryKeyValueStore {
        let r = In(b), kv = MemoryKeyValueStore()
        for _ in 0..<Int(try r.i32()) { kv.put(try r.str(), try r.str(), try r.bytes()) }
        try r.end()
        return kv
    }
}
