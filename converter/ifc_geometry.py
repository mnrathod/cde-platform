"""
Reading an IFC model's geometry, and getting it to the browser cheaply.

<p>Models are large, so the shape of the payload matters more than anything
clever in the extraction. Triangles are grouped by element type and given
one colour per group rather than one per vertex, and the whole thing is
packed into a binary container, because a JSON array of floats for a real
model is tens of megabytes of text the browser then has to parse.

<p>The JSON form is kept alongside it for callers that cannot take the
binary one, and for the tests, where being able to read the payload is
worth more than its size.
"""
import base64
import json
import os
import struct


def is_revit(path: str) -> bool:
    """Detect Revit .rvt/.rfa files by OLE2 magic bytes."""
    try:
        with open(path, "rb") as f:
            magic = f.read(8)
        return magic[:4] == bytes([0xD0, 0xCF, 0x11, 0xE0])
    except Exception:
        return False


def extract_ifc_geometry(ifc_path: str) -> dict:
    """
    Pull renderable geometry out of an IFC file, grouped by element type.

    Returns either {"success": False, "error": ...} or a dict holding the
    numpy arrays and the group table. Serialising it is somebody else's job —
    `ifc_geometry_json` and `ifc_geometry_binary` are the two callers, and
    keeping the extraction free of either encoding is what lets the binary
    one exist without duplicating any of this.

    Geometry is accumulated **per IFC type rather than in iterator order**, so
    that each type ends up as one contiguous run of indices. That run is what
    a renderer needs to draw the type with its own material.

    This replaced a per-vertex colour attribute. Every vertex used to carry a
    float32x3 copy of its element type's colour — twelve bytes each, to encode
    one of sixteen values that are a property of the type, not of the vertex.
    On a million-vertex model that is 12 MB of tiling, and it also fused the
    whole building into a single material, which is why nothing could hide a
    layer.
    """
    try:
        import ifcopenshell
        import ifcopenshell.geom
        import numpy as np
    except ImportError as e:
        return {"success": False, "error": f"ifcopenshell not installed: {e}\nRun: pip install ifcopenshell"}

    try:
        ifc = ifcopenshell.open(ifc_path)
        print(f"[IFC] Opened: schema={ifc.schema}, products={len(ifc.by_type('IfcProduct'))}", flush=True)
    except Exception as e:
        return {"success": False, "error": f"Cannot open IFC: {e}"}

    # Colour map by IFC type
    TYPE_COLORS = {
        "IfcWall":          [0.85, 0.82, 0.78, 1.0],
        "IfcWallStandardCase": [0.85, 0.82, 0.78, 1.0],
        "IfcSlab":          [0.75, 0.75, 0.75, 1.0],
        "IfcRoof":          [0.62, 0.45, 0.35, 1.0],
        "IfcColumn":        [0.80, 0.75, 0.70, 1.0],
        "IfcBeam":          [0.70, 0.65, 0.60, 1.0],
        "IfcDoor":          [0.65, 0.45, 0.25, 1.0],
        "IfcWindow":        [0.55, 0.75, 0.90, 0.5],
        "IfcStair":         [0.80, 0.78, 0.75, 1.0],
        "IfcRamp":          [0.78, 0.76, 0.72, 1.0],
        "IfcFurnishingElement": [0.60, 0.50, 0.40, 1.0],
        "IfcFlowTerminal":  [0.40, 0.65, 0.80, 1.0],
        "IfcFlowSegment":   [0.70, 0.70, 0.30, 1.0],
        "IfcPlate":         [0.75, 0.73, 0.70, 1.0],
        "IfcMember":        [0.65, 0.60, 0.55, 1.0],
    }
    DEFAULT_COLOR = [0.70, 0.68, 0.65, 1.0]

    settings = ifcopenshell.geom.settings()
    settings.set(settings.USE_WORLD_COORDS, True)
    settings.set(settings.WELD_VERTICES, True)

    # type -> its own arrays, so each type can be emitted as one run.
    buckets    = {}
    mesh_count = 0

    try:
        # Filter to only element types that have geometry
        include_types = [
            'IfcWall','IfcWallStandardCase','IfcSlab','IfcRoof','IfcColumn',
            'IfcBeam','IfcDoor','IfcWindow','IfcStair','IfcRamp',
            'IfcFurnishingElement','IfcPlate','IfcMember','IfcCovering',
            'IfcFlowTerminal','IfcFlowSegment','IfcOpeningElement',
        ]
        # Try with type filter first, fall back to all types
        it = None
        for attempt in ['filtered', 'all']:
            try:
                if attempt == 'filtered':
                    it = ifcopenshell.geom.iterator(settings, ifc, include_entities=include_types)
                else:
                    it = ifcopenshell.geom.iterator(settings, ifc)
                if it.initialize():
                    print(f"[IFC] Iterator initialized ({attempt})", flush=True)
                    break
                else:
                    it = None
            except Exception as e:
                print(f"[IFC] Iterator ({attempt}) error: {e}", flush=True)
                it = None

        if it is None:
            prod_count = len(ifc.by_type('IfcProduct'))
            return {"success": False, "error":
                f"IFC file has no renderable geometry ({prod_count} products found). "
                f"The file may contain only 2D data or have no geometric representations."}

        while True:
            shape = it.get()
            geo   = shape.geometry
            verts  = np.array(geo.verts,  dtype=np.float32).reshape(-1, 3)
            norms  = np.array(geo.normals,dtype=np.float32).reshape(-1, 3) if geo.normals else np.zeros_like(verts)
            faces  = np.array(geo.faces,  dtype=np.uint32).reshape(-1, 3)

            if len(verts) == 0 or len(faces) == 0:
                if not it.next(): break
                continue

            # Faces are offset within the bucket here and shifted again to
            # global positions at assembly, because a bucket's final place in
            # the vertex array is not known until every element has been read.
            bucket = buckets.setdefault(shape.type, {
                "positions": [], "normals": [], "faces": [],
                "vertexCount": 0, "elementCount": 0,
            })
            bucket["positions"].append(verts)
            bucket["normals"].append(norms)
            bucket["faces"].append(faces + bucket["vertexCount"])
            bucket["vertexCount"] += len(verts)
            # How many elements of this type the model holds, counted rather
            # than guessed. The viewer's model tree used to invent this with
            # Math.random() when no hierarchy endpoint answered, so a reader
            # was shown fabricated quantities for their own building.
            bucket["elementCount"] += 1
            mesh_count += 1

            if not it.next():
                break

    except Exception as e:
        return {"success": False, "error": f"Geometry extraction failed: {e}"}

    if mesh_count == 0:
        return {"success": False, "error": "No geometry found in IFC file. "
                "Ensure the file contains IfcWall, IfcSlab or other building elements with geometry."}

    assembled = assemble_geometry_buckets(buckets, TYPE_COLORS, DEFAULT_COLOR)

    print(f"[IFC] Total: {assembled['vertexCount']} verts, "
          f"{assembled['triangleCount']} triangles, {mesh_count} elements, "
          f"{len(assembled['groups'])} groups", flush=True)

    assembled["elementCount"] = int(mesh_count)
    assembled["schema"] = ifc.schema
    return assembled


def assemble_geometry_buckets(buckets: dict, type_colors: dict, default_color: list) -> dict:
    """
    Flatten per-type buckets into one set of buffers plus a group table.

    Each type becomes one contiguous run of indices, and one group naming it.
    Two offsets are in play and they are not interchangeable:

      * Faces arrive numbered relative to their own bucket, so each run is
        shifted by the number of vertices already emitted before it. Miss this
        and every type after the first indexes into the wrong vertices.
      * A group's `start`/`count` are in INDEX units — what three.js
        `addGroup` wants on indexed geometry. Vertex units there would draw
        the wrong elements rather than raising anything.

    Separate from the extraction above so it can be tested without an IFC file
    or ifcopenshell: this arithmetic is where the silent-corruption bugs live.
    """
    import numpy as np

    positions     = []
    normals       = []
    indices       = []
    groups        = []
    vertex_offset = 0
    index_offset  = 0

    for ifc_type, bucket in sorted(buckets.items()):
        type_faces = np.concatenate(bucket["faces"], axis=0) + vertex_offset
        positions.append(np.concatenate(bucket["positions"], axis=0))
        normals.append(np.concatenate(bucket["normals"], axis=0))
        indices.append(type_faces)

        colour = type_colors.get(ifc_type, default_color)
        groups.append({
            "type":    ifc_type,
            "start":   int(index_offset),
            "count":   int(type_faces.size),
            # Elements of this type, not triangles. `count` above is an index
            # count and the two are nothing like each other — a single wall is
            # hundreds of indices — so they are named apart deliberately.
            "elementCount": int(bucket.get("elementCount", 0)),
            "color":   [float(channel) for channel in colour[:3]],
            # The colour table always carried an alpha and `col[:3]` always
            # dropped it, so a window rendered as solid as a wall. One
            # material per type is what makes honouring it possible.
            "opacity": float(colour[3]) if len(colour) > 3 else 1.0,
        })
        vertex_offset += bucket["vertexCount"]
        index_offset  += type_faces.size

    positions = np.concatenate(positions, axis=0).astype("<f4")
    normals   = np.concatenate(normals,   axis=0).astype("<f4")
    indices   = np.concatenate(indices,   axis=0).astype("<u4")

    return {
        "success":       True,
        "type":          "ifc3d",
        "positions":     positions,
        "normals":       normals,
        "indices":       indices,
        "groups":        groups,
        "vertexCount":   int(len(positions)),
        "triangleCount": int(len(indices)),
        "bounds": {
            "min": positions.min(axis=0).tolist(),
            "max": positions.max(axis=0).tolist(),
        },
    }


# Header of the binary geometry container, in order. Little-endian throughout,
# which `<f4`/`<u4`/`<I` state explicitly rather than inheriting from whatever
# the host happens to be — a format that only works on x86 is not a format.
GEOMETRY_MAGIC   = b"CDEG"
GEOMETRY_VERSION = 1


def _geometry_meta(geometry: dict) -> dict:
    """The parts of an extraction that describe it, without the buffers."""
    return {key: value for key, value in geometry.items()
            if key not in ("positions", "normals", "indices")}


def ifc_geometry_json(ifc_path: str) -> dict:
    """
    The extraction as JSON with base64 buffers.

    Kept for `GET /api/viewer3d/{documentId}`, whose media type is part of a
    published contract (§3.4) even though the geometry inside it is documented
    as opaque. New clients use the binary container instead: base64 is four
    bytes of transfer for every three of payload, which on a real model is
    tens of megabytes spent encoding numbers that were already bytes.
    """
    geometry = extract_ifc_geometry(ifc_path)
    if not geometry.get("success"):
        return geometry

    def to_b64(array):
        return base64.b64encode(array.tobytes()).decode()

    payload = _geometry_meta(geometry)
    payload.pop("success", None)
    payload.pop("type", None)
    payload.update({
        "positions": to_b64(geometry["positions"]),
        "normals":   to_b64(geometry["normals"]),
        "indices":   to_b64(geometry["indices"]),
    })
    return {"success": True, "type": "ifc3d", "gltfData": payload}


def encode_geometry_container(geometry: dict) -> bytes:
    """
    The extraction as a self-describing binary blob.

    Layout, all little-endian:

        magic        4   b"CDEG"
        version      4   uint32
        headerLength 4   uint32
        header       n   UTF-8 JSON — counts, schema, bounds, groups
        padding      0-3 zero bytes, to a 4-byte boundary
        positions        float32 x 3 x vertexCount
        normals          float32 x 3 x vertexCount
        indices          uint32  x 3 x triangleCount

    The padding is not decoration. A reader takes typed-array views directly
    over the received bytes, and both `Float32Array` and `Uint32Array` refuse
    a byteOffset that is not a multiple of four — so a header of the wrong
    length would throw rather than merely being untidy.

    Takes an extraction rather than a path, so it can be exercised on
    synthetic geometry without an IFC file and without ifcopenshell present.
    """
    header = json.dumps(_geometry_meta(geometry), separators=(",", ":")).encode("utf-8")
    padding = (-len(header)) % 4

    return b"".join([
        GEOMETRY_MAGIC,
        struct.pack("<I", GEOMETRY_VERSION),
        struct.pack("<I", len(header)),
        header,
        b"\0" * padding,
        geometry["positions"].tobytes(),
        geometry["normals"].tobytes(),
        geometry["indices"].tobytes(),
    ])


def ifc_geometry_binary(ifc_path: str):
    """
    Extract one IFC file and encode it.

    Returns `(bytes, None)` on success or `(None, error_dict)` on failure, so
    the caller can send the error as JSON rather than as a broken model.
    """
    geometry = extract_ifc_geometry(ifc_path)
    if not geometry.get("success"):
        return None, geometry
    return encode_geometry_container(geometry), None


