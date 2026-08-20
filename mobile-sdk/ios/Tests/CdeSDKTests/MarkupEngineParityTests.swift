import XCTest
@testable import CdeSDK

/// Parity with the web viewer's markup engine.
///
/// These carry the same vectors as `markup-engine.service.spec.ts` and the
/// Android `MarkupEngineParityTest`. All three clients write the same
/// `shapeData` and the server never parses it, so a divergence does not fail
/// anywhere — it produces markup that reads differently depending on which
/// client drew it. Asserting the same inputs give the same answers is the only
/// thing keeping them honest.
final class MarkupEngineParityTests: XCTestCase {

    private let square = [
        CdePoint(x: 100, y: 100), CdePoint(x: 200, y: 100), CdePoint(x: 200, y: 200)
    ]

    private func area(_ points: [CdePoint]? = nil, tool: MarkupTool = .area) -> ShapeData {
        ShapeData(
            id: "c1", tool: tool, pageNumber: 1, color: "#F00",
            strokeWidth: 2, opacity: 0.15, points: points ?? square)
    }

    // MARK: - finishesShape

    func testClosesWhenTapLandsOnFirstVertex() {
        XCTAssertTrue(MarkupEngine.finishesShape(
            area(), at: CdePoint(x: 100, y: 100), tolerance: 10))
        // A finger is not exact — near enough must also close, or the gesture
        // is no more usable than the double-click it replaces.
        XCTAssertTrue(MarkupEngine.finishesShape(
            area(), at: CdePoint(x: 106, y: 96), tolerance: 10))
    }

    func testClosesWhenTapLandsOnVertexJustPlaced() {
        XCTAssertTrue(MarkupEngine.finishesShape(
            area(), at: CdePoint(x: 200, y: 200), tolerance: 10))
        XCTAssertTrue(MarkupEngine.finishesShape(
            area(), at: CdePoint(x: 197, y: 204), tolerance: 10))
    }

    func testAddsVertexForTapThatIsMerelyNearby() {
        XCTAssertFalse(MarkupEngine.finishesShape(
            area(), at: CdePoint(x: 240, y: 150), tolerance: 10))
    }

    func testWillNotCloseShapeWithTooFewPoints() {
        let twoPoints = [CdePoint(x: 100, y: 100), CdePoint(x: 200, y: 100)]
        XCTAssertFalse(MarkupEngine.finishesShape(
            area(twoPoints), at: CdePoint(x: 200, y: 100), tolerance: 10))
    }

    func testLeavesFixedClickCountToolsAlone() {
        // Radius and Calibrate complete on their own second point. Closing
        // them early would cost the point that defines them.
        for tool in [MarkupTool.radius, .calibrate] {
            let shape = area([CdePoint(x: 100, y: 100)], tool: tool)
            XCTAssertFalse(
                MarkupEngine.finishesShape(shape, at: CdePoint(x: 100, y: 100), tolerance: 10),
                "\(tool.rawValue) should not close early")
        }
    }

    func testIgnoresToolsThatAreDragged() {
        let rect = ShapeData(
            id: "r1", tool: .rect, pageNumber: 1, color: "#F00",
            strokeWidth: 2, opacity: 0, x: 100, y: 100, width: 50, height: 50)
        XCTAssertFalse(MarkupEngine.finishesShape(
            rect, at: CdePoint(x: 100, y: 100), tolerance: 10))
    }

    func testClosesNothingWhenNoShapeIsBeingDrawn() {
        XCTAssertFalse(MarkupEngine.finishesShape(
            nil, at: CdePoint(x: 1, y: 1), tolerance: 10))
    }

    /// The web engine also treats a recognised double-click as a close. Touch
    /// does not: a double *tap* collides with the platform's own zoom gesture
    /// and demands two precise touches in one spot. The two positional
    /// gestures are kept, and the viewer offers a Done control besides.
    ///
    /// Recorded as a test so the divergence is a decision on the record rather
    /// than something that looks like an omission to whoever reads this next.
    func testTouchDropsDoubleClickDeliberately() {
        XCTAssertFalse(MarkupEngine.finishesShape(
            area(), at: CdePoint(x: 500, y: 500), tolerance: 10))
    }

    // MARK: - canFinish

    func testRequiresThreePointsForAreaAndTwoForLine() {
        func withPoints(_ tool: MarkupTool, _ count: Int) -> ShapeData {
            ShapeData(
                id: "f1", tool: tool, pageNumber: 1, color: "#F00",
                strokeWidth: 2, opacity: 0,
                points: (0..<count).map { CdePoint(x: Double($0) * 10, y: 0) })
        }

        XCTAssertFalse(MarkupEngine.canFinish(withPoints(.area, 2)))
        XCTAssertTrue(MarkupEngine.canFinish(withPoints(.area, 3)))
        XCTAssertFalse(MarkupEngine.canFinish(withPoints(.dimension, 1)))
        XCTAssertTrue(MarkupEngine.canFinish(withPoints(.dimension, 2)))
        XCTAssertFalse(MarkupEngine.canFinish(nil))
    }

    // MARK: - Geometry

    func testPolygonAreaMatchesTheShoelaceResultTheWebViewerReports() {
        let closed = [
            CdePoint(x: 100, y: 100), CdePoint(x: 200, y: 100),
            CdePoint(x: 200, y: 200), CdePoint(x: 100, y: 200)
        ]
        XCTAssertEqual(MarkupEngine.polygonArea(closed), 10_000, accuracy: 0.001)
        XCTAssertEqual(MarkupEngine.polygonPerimeter(closed), 400, accuracy: 0.001)
    }

    func testShapeClosedOnOpeningVertexMeasuresTheSame() {
        // Tapping the first vertex to close must not add a zero-length edge or
        // a duplicate point that changes the area.
        let open = [CdePoint(x: 0, y: 0), CdePoint(x: 100, y: 0), CdePoint(x: 100, y: 100)]
        let explicitlyClosed = open + [CdePoint(x: 0, y: 0)]
        XCTAssertEqual(
            MarkupEngine.polygonArea(open),
            MarkupEngine.polygonArea(explicitlyClosed),
            accuracy: 0.001)
    }

    func testPathLengthSumsTheSegments() {
        let path = [CdePoint(x: 0, y: 0), CdePoint(x: 3, y: 4), CdePoint(x: 3, y: 14)]
        XCTAssertEqual(MarkupEngine.pathLength(path), 15, accuracy: 0.001)
    }

    // MARK: - Identity and round-tripping

    func testIdsAreUniqueAndCarryTheWebViewersPrefix() {
        let ids = Set((0..<500).map { _ in MarkupEngine.newId() })
        XCTAssertEqual(ids.count, 500)
        XCTAssertTrue(ids.allSatisfy { $0.hasPrefix("s-") })
    }

    /// The whole point of the shared format: what one client writes, another
    /// must read. This asserts the encoder produces the field names the web
    /// viewer looks for, not merely that it round-trips through itself.
    func testShapeDataEncodesTheFieldNamesTheWebViewerReads() throws {
        let shape = ShapeData(
            id: "s-1", tool: .area, pageNumber: 1, color: "#FF0000",
            strokeWidth: 2, opacity: 0.15, points: square,
            measurement: "15300 px²", measurementDetail: "1051.5 px")

        let encoded = try MarkupCodec.encode(shape)
        for key in ["\"id\"", "\"tool\"", "\"pageNumber\"", "\"color\"",
                    "\"strokeWidth\"", "\"opacity\"", "\"points\"",
                    "\"measurement\"", "\"measurementDetail\""] {
            XCTAssertTrue(encoded.contains(key), "missing \(key) in \(encoded)")
        }
        XCTAssertTrue(encoded.contains("\"area\""), "tool must serialise as its web name")

        let decoded = MarkupCodec.decode(encoded)
        XCTAssertEqual(decoded?.tool, .area)
        XCTAssertEqual(decoded?.points?.count, 3)
    }

    func testUnreadableMarkupIsSkippedRatherThanThrowing() {
        // One annotation written by a future client must not hide every other
        // annotation on the document.
        XCTAssertNil(MarkupCodec.decode("not json"))
        XCTAssertNil(MarkupCodec.decode(""))
    }

    // MARK: - Measurement

    func testAreaScalesWithTheSquareOfTheLinearFactor() {
        // The failure this guards is a number that looks plausible and is out
        // by the scale itself — the worst kind of wrong on a take-off.
        let scale = MeasurementScale(unitsPerPixel: 0.5, unit: .metre)
        XCTAssertEqual(Measurement.formatArea(100, scale: scale), "25 m²")
        XCTAssertEqual(Measurement.formatLength(100, scale: scale), "50 m")
    }

    func testCalibrationRefusesInputThatCannotDefineAScale() {
        XCTAssertNil(Measurement.calibrate(pixels: 0, realDistance: 5, unit: .metre))
        XCTAssertNil(Measurement.calibrate(pixels: 100, realDistance: 0, unit: .metre))
        XCTAssertNotNil(Measurement.calibrate(pixels: 100, realDistance: 5, unit: .metre))
    }
}
