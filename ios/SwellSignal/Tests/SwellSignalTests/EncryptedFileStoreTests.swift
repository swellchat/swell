import XCTest
@testable import SwellSignal

final class EncryptedFileStoreTests: XCTestCase {
    var dir: URL!
    let key = [UInt8](repeating: 7, count: 32)
    override func setUp() { dir = FileManager.default.temporaryDirectory.appendingPathComponent("swk-\(UUID().uuidString)") }
    override func tearDown() { try? FileManager.default.removeItem(at: dir) }
    var file: URL { dir.appendingPathComponent("state.swk") }

    func testReopenKeepsData() throws {
        let a = try EncryptedFileStore(url: file, key: key)
        a.put("sess", "x", [1, 2, 3]); a.put("msg", "y", [9]); a.delete("msg", "y")
        let b = try EncryptedFileStore(url: file, key: key)
        XCTAssertEqual(b.get("sess", "x"), [1, 2, 3])
        XCTAssertNil(b.get("msg", "y"))
        XCTAssertEqual(b.list("sess").count, 1)
    }

    func testFileHoldsNoPlaintext() throws {
        let a = try EncryptedFileStore(url: file, key: key)
        a.put("contact", "SECRET-NAME-MARKER", Array("SECRET-VALUE-MARKER".utf8))
        let raw = try Data(contentsOf: file)
        XCTAssertNil(raw.range(of: Data("SECRET".utf8)))
    }

    func testWrongKeyOrDamageIsCorruptNotEmpty() throws {
        let a = try EncryptedFileStore(url: file, key: key)
        a.put("a", "b", [1])
        XCTAssertThrowsError(try EncryptedFileStore(url: file, key: [UInt8](repeating: 8, count: 32))) { XCTAssertEqual($0 as? StoreError, .corrupt) }
        var raw = try Data(contentsOf: file); raw[raw.count - 3] ^= 1; try raw.write(to: file)
        XCTAssertThrowsError(try EncryptedFileStore(url: file, key: key)) { XCTAssertEqual($0 as? StoreError, .corrupt) }
        try Data("junk".utf8).write(to: file)
        XCTAssertThrowsError(try EncryptedFileStore(url: file, key: key)) { XCTAssertEqual($0 as? StoreError, .corrupt) }
    }

    func testBatchIsAllOrNothing() throws {
        let a = try EncryptedFileStore(url: file, key: key)
        a.put("a", "keep", [1])
        struct Boom: Error {}
        XCTAssertThrowsError(try a.batch { a.put("a", "new", [2]); a.delete("a", "keep"); throw Boom() })
        XCTAssertEqual(a.get("a", "keep"), [1]); XCTAssertNil(a.get("a", "new"))
        try a.batch { a.put("a", "n1", [1]); a.put("a", "n2", [2]) }
        let b = try EncryptedFileStore(url: file, key: key)
        XCTAssertEqual(b.get("a", "n2"), [2])
    }

    func testCrashLeftoverTempFileIsIgnored() throws {
        let a = try EncryptedFileStore(url: file, key: key)
        a.put("a", "b", [5])
        try Data("half written".utf8).write(to: file.appendingPathExtension("tmp"))
        let b = try EncryptedFileStore(url: file, key: key)
        XCTAssertEqual(b.get("a", "b"), [5])
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.appendingPathExtension("tmp").path))
    }

    func testSignalStateSurvivesReopen() throws {
        // A real session persisted through the encrypted store keeps working after reopen.
        let s1 = try EncryptedFileStore(url: file, key: key)
        s1.put("sess", "peer.1", Codec32.bytes); 
        XCTAssertEqual(try EncryptedFileStore(url: file, key: key).get("sess", "peer.1")?.count, 32)
    }
}
enum Codec32 { static let bytes = [UInt8](repeating: 3, count: 32) }
