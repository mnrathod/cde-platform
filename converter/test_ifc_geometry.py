"""
The IFC geometry payload: how it is grouped, and how it is framed.

These exercise the two halves that do not need an IFC file — the container
encoding, and the shape of what the extractor promises to hand it. The
extraction itself needs ifcopenshell and a real model, and is covered by
test_app.py where that is available.

Two things here are load-bearing and easy to get silently wrong:

  * Group offsets are in INDEX units, not vertices. A renderer given vertex
    offsets draws the wrong elements rather than failing, so it is asserted
    directly.
  * Buffers must start on a 4-byte boundary. A reader takes typed-array views
    straight over these bytes; a misaligned offset throws. The padding is
    computed from a header whose length varies with the model, so this is not
    a constant that can be eyeballed once.
"""
import json
import struct

import numpy as np
import pytest

from app import (
    GEOMETRY_MAGIC,
    GEOMETRY_VERSION,
    assemble_geometry_buckets,
    encode_geometry_container,
)

COLORS = {
    "IfcWall":   [0.85, 0.82, 0.78, 1.0],
    "IfcWindow": [0.55, 0.75, 0.90, 0.5],
}
DEFAULT_COLOR = [0.70, 0.68, 0.65, 1.0]


def bucket(vertex_count, triangles, element_count=1):
    """
    One type's accumulated arrays, as the extractor builds them.

    `triangles` are numbered relative to this bucket, which is the whole point
    — assembly has to shift them by the vertices emitted before it.

    `element_count` is how many elements of the type went into the bucket, and
    is a separate quantity from anything derivable from the arrays: one wall is
    hundreds of indices, and two walls welded into one bucket are
    indistinguishable from one long wall by looking at the geometry.
    """
    return {
        "positions": [np.arange(vertex_count * 3, dtype=np.float32).reshape(-1, 3)],
        "normals": [np.zeros((vertex_count, 3), dtype=np.float32)],
        "faces": [np.array(triangles, dtype=np.uint32).reshape(-1, 3)],
        "vertexCount": vertex_count,
        "elementCount": element_count,
    }


def geometry(groups=None, vertices=4, triangles=2):
    """A synthetic extraction, shaped exactly as the extractor returns one."""
    return {
        "success": True,
        "type": "ifc3d",
        "positions": np.arange(vertices * 3, dtype="<f4"),
        "normals": np.zeros(vertices * 3, dtype="<f4"),
        "indices": np.arange(triangles * 3, dtype="<u4"),
        "groups": groups if groups is not None else [
            {"type": "IfcWall", "start": 0, "count": 3,
             "color": [0.85, 0.82, 0.78], "opacity": 1.0},
            {"type": "IfcWindow", "start": 3, "count": 3,
             "color": [0.55, 0.75, 0.90], "opacity": 0.5},
        ],
        "vertexCount": vertices,
        "triangleCount": triangles,
        "elementCount": 2,
        "schema": "IFC4",
        "bounds": {"min": [0.0, 0.0, 0.0], "max": [1.0, 1.0, 1.0]},
    }


def parse(blob):
    """Read a container back, the way the browser reader does."""
    assert blob[:4] == GEOMETRY_MAGIC
    version, header_length = struct.unpack_from("<II", blob, 4)
    header = json.loads(blob[12:12 + header_length].decode("utf-8"))
    offset = 12 + header_length
    offset += (-header_length) % 4

    vertex_floats = header["vertexCount"] * 3
    index_count = header["triangleCount"] * 3

    positions = np.frombuffer(blob, dtype="<f4", count=vertex_floats, offset=offset)
    offset += vertex_floats * 4
    normals = np.frombuffer(blob, dtype="<f4", count=vertex_floats, offset=offset)
    offset += vertex_floats * 4
    indices = np.frombuffer(blob, dtype="<u4", count=index_count, offset=offset)
    offset += index_count * 4

    return version, header, positions, normals, indices, offset


class TestContainerFraming:

    def test_round_trips_every_buffer_unchanged(self):
        source = geometry()

        _, _, positions, normals, indices, _ = parse(encode_geometry_container(source))

        np.testing.assert_array_equal(positions, source["positions"])
        np.testing.assert_array_equal(normals, source["normals"])
        np.testing.assert_array_equal(indices, source["indices"])

    def test_consumes_the_whole_blob_with_nothing_left_over(self):
        # A trailing byte means an offset is wrong somewhere; without this the
        # buffers could each read correctly and still be framed incorrectly.
        blob = encode_geometry_container(geometry())

        *_, end = parse(blob)

        assert end == len(blob)

    def test_declares_its_version(self):
        version, *_ = parse(encode_geometry_container(geometry()))

        assert version == GEOMETRY_VERSION

    @pytest.mark.parametrize("schema_name", [
        "IFC4", "IFC4X3", "IFC2X3", "IFC4X3_ADD2", "IFC4X1_XYZ",
    ])
    def test_buffers_stay_4_byte_aligned_whatever_the_header_length(self, schema_name):
        # The header is JSON whose length moves with the model, so alignment
        # cannot be assumed from one example. Each of these produces a
        # different header length, and a Float32Array view over a byteOffset
        # that is not a multiple of 4 throws in the browser.
        source = geometry()
        source["schema"] = schema_name

        blob = encode_geometry_container(source)
        header_length = struct.unpack_from("<I", blob, 8)[0]
        first_buffer = 12 + header_length + (-header_length) % 4

        assert first_buffer % 4 == 0
        # And it really does parse from there.
        parse(blob)

    def test_pads_with_zeros_rather_than_whatever_was_in_memory(self):
        source = geometry()
        source["schema"] = "IFC4X3"

        blob = encode_geometry_container(source)
        header_length = struct.unpack_from("<I", blob, 8)[0]
        padding = blob[12 + header_length:12 + header_length + (-header_length) % 4]

        assert padding == b"\0" * len(padding)

    def test_carries_no_colour_buffer(self):
        # The point of the change: colour is per type in the header, not a
        # float32x3 tiled across every vertex. Asserted on the byte count so
        # it fails if a colours array is reintroduced anywhere.
        source = geometry()

        blob = encode_geometry_container(source)
        header_length = struct.unpack_from("<I", blob, 8)[0]
        framing = 12 + header_length + (-header_length) % 4
        buffers = len(blob) - framing

        floats_per_vertex = 6  # position and normal only
        assert buffers == (source["vertexCount"] * floats_per_vertex * 4
                           + source["triangleCount"] * 3 * 4)


class TestGroupTable:

    def test_header_carries_the_groups_and_not_the_buffers(self):
        _, header, *_ = parse(encode_geometry_container(geometry()))

        assert [group["type"] for group in header["groups"]] == ["IfcWall", "IfcWindow"]
        assert "positions" not in header
        assert "normals" not in header
        assert "indices" not in header

    def test_group_ranges_are_index_offsets_covering_every_index(self):
        # Contiguous, non-overlapping, and exactly as long as the index
        # buffer: the three properties that make the ranges drawable.
        source = geometry()

        _, header, _, _, indices, _ = parse(encode_geometry_container(source))

        cursor = 0
        for group in header["groups"]:
            assert group["start"] == cursor
            cursor += group["count"]
        assert cursor == len(indices)

    def test_carries_the_group_table_through_to_the_header(self):
        _, header, *_ = parse(encode_geometry_container(geometry()))

        by_type = {group["type"]: group for group in header["groups"]}

        assert by_type["IfcWindow"]["opacity"] == 0.5
        assert by_type["IfcWall"]["color"] == [0.85, 0.82, 0.78]


class TestBucketAssembly:
    """
    The arithmetic that turns per-type buckets into buffers and groups.

    Distinct from the framing tests above, which assert over geometry this
    file constructs and so could only ever verify the round-trip. These feed
    the real function real buckets and check what it computes.
    """

    def test_shifts_each_type_past_the_vertices_already_emitted(self):
        # The bug this exists for: faces are bucket-relative on the way in.
        # Without the shift, IfcWindow's triangle indexes IfcWall's vertices
        # and the model renders as garbage rather than failing.
        buckets = {
            "IfcWall":   bucket(3, [[0, 1, 2]]),
            "IfcWindow": bucket(3, [[0, 1, 2]]),
        }

        assembled = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)

        # Sorted order puts IfcWall first, so IfcWindow starts at vertex 3.
        np.testing.assert_array_equal(
            assembled["indices"], np.array([[0, 1, 2], [3, 4, 5]], dtype="<u4"))

    def test_group_ranges_are_index_units_not_vertex_units(self):
        # Two triangles is six indices but only four vertices, so a group
        # built in vertex units would say count=4 here and silently drop two
        # indices off the end of the run.
        buckets = {"IfcWall": bucket(4, [[0, 1, 2], [1, 2, 3]])}

        groups = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)["groups"]

        assert groups[0]["count"] == 6

    def test_groups_tile_the_index_buffer_exactly(self):
        buckets = {
            "IfcWall":   bucket(4, [[0, 1, 2], [1, 2, 3]]),
            "IfcWindow": bucket(3, [[0, 1, 2]]),
            "IfcSlab":   bucket(6, [[0, 1, 2], [3, 4, 5]]),
        }

        assembled = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)

        cursor = 0
        for group in assembled["groups"]:
            assert group["start"] == cursor
            cursor += group["count"]
        assert cursor == assembled["indices"].size

    def test_counts_vertices_not_floats(self):
        buckets = {"IfcWall": bucket(4, [[0, 1, 2]]),
                   "IfcWindow": bucket(3, [[0, 1, 2]])}

        assembled = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)

        assert assembled["vertexCount"] == 7
        assert assembled["triangleCount"] == 2

    def test_reads_opacity_off_the_colour_table(self):
        # The original bug, and the one a synthetic fixture cannot catch: the
        # per-vertex encoder took `col[:3]` and threw the alpha away, so every
        # window rendered as solid as a wall. This has to run the real
        # assembly against the real table to mean anything.
        buckets = {"IfcWall":   bucket(3, [[0, 1, 2]]),
                   "IfcWindow": bucket(3, [[0, 1, 2]])}

        groups = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)["groups"]
        by_type = {group["type"]: group for group in groups}

        assert by_type["IfcWindow"]["opacity"] == 0.5
        assert by_type["IfcWall"]["opacity"] == 1.0

    def test_carries_the_rgb_the_colour_table_names(self):
        buckets = {"IfcWindow": bucket(3, [[0, 1, 2]])}

        groups = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)["groups"]

        assert groups[0]["color"] == [0.55, 0.75, 0.90]

    def test_falls_back_to_the_default_colour_for_an_unknown_type(self):
        buckets = {"IfcSomethingNew": bucket(3, [[0, 1, 2]])}

        groups = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)["groups"]

        assert groups[0]["color"] == DEFAULT_COLOR[:3]
        assert groups[0]["opacity"] == 1.0

    def test_emits_little_endian_buffers_whatever_the_host_is(self):
        # The container is a defined format, not whatever this machine uses.
        assembled = assemble_geometry_buckets(
            {"IfcWall": bucket(3, [[0, 1, 2]])}, COLORS, DEFAULT_COLOR)

        assert assembled["positions"].dtype.byteorder in ("<", "=")
        assert assembled["positions"].dtype.str == "<f4"
        assert assembled["indices"].dtype.str == "<u4"

    def test_bounds_span_three_axes(self):
        assembled = assemble_geometry_buckets(
            {"IfcWall": bucket(4, [[0, 1, 2]])}, COLORS, DEFAULT_COLOR)

        assert len(assembled["bounds"]["min"]) == 3
        assert len(assembled["bounds"]["max"]) == 3


class TestElementCounts:
    """
    How many elements of each type the model holds.

    The viewer's model tree shows this as a quantity. It used to invent it —
    `Math.floor(Math.random() * 20) + 1`, per type, for a fixed list of ten
    types the model need not contain — whenever no hierarchy endpoint answered.
    §1A.4 makes that tree the accessible equivalent of a WebGL canvas, so it is
    the one route that must not be guessed at. Carrying a counted number here
    is what let the guess be deleted.
    """

    def test_reports_the_number_of_elements_the_bucket_held(self):
        buckets = {"IfcWall": bucket(3, [[0, 1, 2]], element_count=17)}

        groups = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)["groups"]

        assert groups[0]["elementCount"] == 17

    def test_counts_each_type_separately(self):
        buckets = {
            "IfcWall":   bucket(3, [[0, 1, 2]], element_count=12),
            "IfcWindow": bucket(3, [[0, 1, 2]], element_count=4),
        }

        groups = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)["groups"]

        # Sorted order: IfcWall then IfcWindow.
        assert [group["elementCount"] for group in groups] == [12, 4]

    def test_element_count_is_not_the_index_count(self):
        # The two are named apart deliberately. One wall of two triangles is
        # six indices; reading `count` as a quantity would tell a reader their
        # model holds six walls.
        buckets = {"IfcWall": bucket(4, [[0, 1, 2], [1, 2, 3]], element_count=1)}

        group = assemble_geometry_buckets(buckets, COLORS, DEFAULT_COLOR)["groups"][0]

        assert group["count"] == 6
        assert group["elementCount"] == 1

    def test_survives_a_bucket_written_without_a_count(self):
        # Assembly is called with buckets the extractor built, but it is also
        # the testable seam, so it should not raise on one that predates the
        # field. Zero reads downstream as "not known".
        legacy = bucket(3, [[0, 1, 2]])
        del legacy["elementCount"]

        groups = assemble_geometry_buckets({"IfcWall": legacy}, COLORS, DEFAULT_COLOR)["groups"]

        assert groups[0]["elementCount"] == 0
