"""
Comparing two drawings, or two models.

<p>Both answer the same question — what changed between these two revisions
— but they answer it from structure rather than from pixels: entities and
layers for a drawing, elements and their properties for an IFC model. The
text and image comparisons live in ``document_comparison.py`` and
``image_comparison.py``, and the dispatch that picks between all of them
stays in ``app.py``.
"""
import os
import shutil
import tempfile
import time
from pathlib import Path

from dwg_oda import find_oda
from dxf_text_layer import mtext_string
from toolchain import make_temp_dir, safe_rmtree, run_cmd


# ── CAD Comparison (DXF/DWG) ──────────────────────────────────
def _load_dxf_for_compare(path: str, ext: str):
    """
    Load a DXF or DWG file, converting DWG via ODA where needed.

    The DWG branch used to do the work twice. It called ``dwg_via_oda``,
    which runs the ODA converter *and* renders the result to SVG, purely to
    read ``success`` off it; opened a temp file it then neither wrote to nor
    deleted; imported ezdxf and never used it; and finally threw all of that
    away and called the function below, which runs ODA again. Its own comment
    said so — "For simplicity, re-run ODA and get the DXF path directly".

    So every DWG-against-DWG comparison converted twice, rendered an SVG
    nobody read, and leaked a temp file. The function below already reports
    a missing converter and a failed conversion itself, so the first pass
    contributed nothing but the cost.
    """
    if ext == 'dwg':
        return _load_dxf_from_path_via_oda(path)
    else:
        try:
            import ezdxf as _ezdxf
            doc = _ezdxf.readfile(path)
            return doc, None
        except Exception as e:
            try:
                import ezdxf.recover as _recover
                doc, _ = _recover.read(path)
                return doc, None
            except Exception as e2:
                return None, str(e2)

def _load_dxf_from_path_via_oda(dwg_path: str):
    """Returns (ezdxf_doc, error_str)"""
    import ezdxf as _ezdxf, ezdxf.recover as _recover
    oda = find_oda()
    if not oda:
        return None, "ODA not installed"
    in_dir  = make_temp_dir()
    out_dir = make_temp_dir()
    try:
        fname = Path(dwg_path).name
        shutil.copy2(dwg_path, os.path.join(in_dir, fname))
        cmd = [oda, in_dir, out_dir, "ACAD2018", "DXF", "0", "1"]
        run_cmd(cmd, timeout=120)
        time.sleep(1)
        dxf_files = list(Path(out_dir).rglob("*.dxf")) + list(Path(out_dir).rglob("*.DXF"))
        if not dxf_files:
            return None, "ODA produced no DXF"
        content = dxf_files[0].read_text(encoding='utf-8', errors='replace')
        tmp = tempfile.NamedTemporaryFile(mode='w', suffix='.dxf',
                                          encoding='utf-8', delete=False)
        tmp.write(content); tmp.close()
        try:
            doc = _ezdxf.readfile(tmp.name)
        except:
            doc, _ = _recover.read(tmp.name)
        os.unlink(tmp.name)
        return doc, None
    finally:
        safe_rmtree(in_dir); safe_rmtree(out_dir)


def _analyze_dxf(doc) -> dict:
    """Extract structured data from a DXF modelspace for comparison."""
    msp = doc.modelspace()
    info = {
        'total': 0,
        'by_type': {},
        'by_layer': {},
        'layers': [],
        'texts': [],
        'blocks_used': {},
        'circles': [],
        'lines_count': 0,
        'dimensions': [],
        'hatches': 0,
        'block_defs': [],
    }

    for e in msp:
        info['total'] += 1
        t = e.dxftype()
        info['by_type'][t] = info['by_type'].get(t, 0) + 1
        layer = e.dxf.get('layer', '0')
        info['by_layer'][layer] = info['by_layer'].get(layer, 0) + 1

        if t in ('TEXT', 'MTEXT'):
            try:
                txt = e.dxf.text if t == 'TEXT' else mtext_string(e)
                if txt and txt.strip():
                    info['texts'].append(txt.strip()[:80])
            except Exception as exc:
                print(f"[analyse] skipped {t}: {exc}", flush=True)
        elif t == 'INSERT':
            name = e.dxf.get('name', '?')
            info['blocks_used'][name] = info['blocks_used'].get(name, 0) + 1
        elif t == 'CIRCLE':
            info['circles'].append(round(e.dxf.radius, 2))
        elif t == 'LINE':
            info['lines_count'] += 1
        elif t == 'DIMENSION':
            try: info['dimensions'].append(round(e.dxf.actual_measurement, 2))
            except: pass
        elif t == 'HATCH':
            info['hatches'] += 1

    info['layers'] = sorted(set(info['by_layer'].keys()))

    # Block definitions
    try:
        info['block_defs'] = [b.name for b in doc.blocks
                               if not b.name.startswith('*')]
    except: pass

    return info


def _compare_cad(path1, path2, ext1, ext2) -> dict:
    """Compare two CAD files (DXF or DWG)."""
    doc1, err1 = _load_dxf_for_compare(path1, ext1)
    doc2, err2 = _load_dxf_for_compare(path2, ext2)

    if doc1 is None:
        return {"success": False, "error": f"Cannot read file 1: {err1}"}
    if doc2 is None:
        return {"success": False, "error": f"Cannot read file 2: {err2}"}

    a1 = _analyze_dxf(doc1)
    a2 = _analyze_dxf(doc2)

    changes = []
    summary_stats = {
        'file1_entities': a1['total'],
        'file2_entities': a2['total'],
        'file1_layers':   len(a1['layers']),
        'file2_layers':   len(a2['layers']),
    }

    # ── Entity type changes ─────────────────────────────────
    all_types = set(list(a1['by_type'].keys()) + list(a2['by_type'].keys()))

    # Map entity types to human-readable names
    TYPE_NAMES = {
        'LINE': 'Line', 'CIRCLE': 'Circle', 'ARC': 'Arc',
        'LWPOLYLINE': 'Polyline', 'POLYLINE': 'Polyline',
        'TEXT': 'Text Label', 'MTEXT': 'Text Label',
        'DIMENSION': 'Dimension', 'INSERT': 'Block Reference',
        'HATCH': 'Hatch/Fill', 'SPLINE': 'Spline Curve',
        'ELLIPSE': 'Ellipse', 'SOLID': 'Solid Fill',
        'POINT': 'Point', 'LEADER': 'Leader/Arrow',
        'IMAGE': 'Raster Image', 'XREF': 'External Reference',
    }

    for t in sorted(all_types):
        c1 = a1['by_type'].get(t, 0)
        c2 = a2['by_type'].get(t, 0)
        if c1 == c2: continue
        diff = c2 - c1
        name = TYPE_NAMES.get(t, t)
        severity = 'high' if abs(diff) >= 5 else 'medium' if abs(diff) >= 2 else 'low'
        changes.append({
            'category': 'GEOMETRY',
            'severity': severity,
            'type': 'added' if diff > 0 else 'removed',
            'icon': '➕' if diff > 0 else '➖',
            'change': f"{'Added' if diff > 0 else 'Removed'} {abs(diff)} {name}{'s' if abs(diff)>1 else ''}",
            'detail': f"File 1: {c1}  →  File 2: {c2}",
        })

    # ── Block reference changes (DOOR, WINDOW, etc.) ─────────
    all_blocks = set(list(a1['blocks_used'].keys()) + list(a2['blocks_used'].keys()))
    for block in sorted(all_blocks):
        c1 = a1['blocks_used'].get(block, 0)
        c2 = a2['blocks_used'].get(block, 0)
        if c1 == c2: continue
        diff = c2 - c1
        # Guess what the block is from its name
        bname = block.upper()
        kind = ('door' if any(k in bname for k in ['DOOR','DR','DOR']) else
                'window' if any(k in bname for k in ['WIND','WIN','WN','WDW']) else
                'column' if any(k in bname for k in ['COL','COLUMN','PILLAR']) else
                'stair' if any(k in bname for k in ['STAIR','STEP']) else
                'furniture' if any(k in bname for k in ['FURN','CHAIR','TABLE','DESK']) else
                f"'{block}' symbol")
        changes.append({
            'category': 'SYMBOL',
            'severity': 'high',
            'type': 'added' if diff > 0 else 'removed',
            'icon': '🚪' if 'door' in kind else '🪟' if 'window' in kind else '🏛' if 'column' in kind else '🔷',
            'change': f"{'Added' if diff > 0 else 'Removed'} {abs(diff)} {kind}",
            'detail': f"Block '{block}': {c1} → {c2}",
        })

    # ── Layer changes ────────────────────────────────────────
    new_layers = sorted(set(a2['layers']) - set(a1['layers']))
    removed_layers = sorted(set(a1['layers']) - set(a2['layers']))
    for l in new_layers:
        n = a2['by_layer'].get(l, 0)
        changes.append({
            'category': 'LAYER',
            'severity': 'medium',
            'type': 'added',
            'icon': '📋',
            'change': f"New layer added: '{l}'",
            'detail': f"{n} entities on this layer",
        })
    for l in removed_layers:
        changes.append({
            'category': 'LAYER',
            'severity': 'medium',
            'type': 'removed',
            'icon': '📋',
            'change': f"Layer removed: '{l}'",
            'detail': f"Layer with {a1['by_layer'].get(l,0)} entities no longer present",
        })

    # ── Text label changes ───────────────────────────────────
    t1_set = set(a1['texts'])
    t2_set = set(a2['texts'])
    for t in sorted(t2_set - t1_set)[:10]:
        changes.append({
            'category': 'TEXT',
            'severity': 'low',
            'type': 'added',
            'icon': '📝',
            'change': f"New text label: \"{t}\"",
            'detail': 'Text annotation added',
        })
    for t in sorted(t1_set - t2_set)[:10]:
        changes.append({
            'category': 'TEXT',
            'severity': 'low',
            'type': 'removed',
            'icon': '📝',
            'change': f"Removed text label: \"{t}\"",
            'detail': 'Text annotation removed',
        })

    # ── Dimension changes ────────────────────────────────────
    dc1 = a1['by_type'].get('DIMENSION', 0)
    dc2 = a2['by_type'].get('DIMENSION', 0)
    if dc1 != dc2:
        diff = dc2 - dc1
        changes.append({
            'category': 'DIMENSION',
            'severity': 'medium',
            'type': 'added' if diff > 0 else 'removed',
            'icon': '📐',
            'change': f"{'Added' if diff>0 else 'Removed'} {abs(diff)} dimension annotation{'s' if abs(diff)>1 else ''}",
            'detail': f"{dc1} → {dc2} dimensions",
        })

    # ── Overall summary ──────────────────────────────────────
    total_diff = a2['total'] - a1['total']
    overall = ('identical' if not changes else
               'minor differences' if len(changes) <= 2 else
               'moderate changes' if len(changes) <= 6 else
               'significant changes')

    return {
        'success': True,
        'fileType': 'CAD Drawing',
        'overall': overall,
        'totalChanges': len(changes),
        'added': sum(1 for c in changes if c['type'] == 'added'),
        'removed': sum(1 for c in changes if c['type'] == 'removed'),
        'changes': changes,
        'stats': {**summary_stats,
                  'entity_diff': total_diff,
                  'file1_blocks': len(a1['blocks_used']),
                  'file2_blocks': len(a2['blocks_used']),
                  'new_layers': new_layers,
                  'removed_layers': removed_layers},
    }


# ── IFC Comparison ────────────────────────────────────────────
def _compare_ifc(path1, path2) -> dict:
    try:
        import ifcopenshell
    except ImportError:
        return {"success": False, "error": "ifcopenshell not installed"}

    try:
        ifc1 = ifcopenshell.open(path1)
        ifc2 = ifcopenshell.open(path2)
    except Exception as e:
        return {"success": False, "error": f"Cannot open IFC: {e}"}

    changes = []
    stats = {}

    # Count elements by type
    ifc_types = [
        'IfcWall','IfcWallStandardCase','IfcSlab','IfcRoof','IfcColumn',
        'IfcBeam','IfcDoor','IfcWindow','IfcStair','IfcRamp',
        'IfcFurnishingElement','IfcSpace','IfcBuildingStorey',
        'IfcFlowTerminal','IfcFlowSegment','IfcOpeningElement',
        'IfcPlate','IfcMember','IfcCovering',
    ]

    ifc_icons = {
        'IfcWall': '🧱', 'IfcWallStandardCase': '🧱', 'IfcSlab': '⬜',
        'IfcRoof': '🏠', 'IfcColumn': '🏛', 'IfcBeam': '━',
        'IfcDoor': '🚪', 'IfcWindow': '🪟', 'IfcStair': '🪜',
        'IfcSpace': '📐', 'IfcFurnishingElement': '🪑',
        'IfcBuildingStorey': '🏢', 'IfcFlowTerminal': '💡',
    }

    for ifc_type in ifc_types:
        try:
            c1 = len(ifc1.by_type(ifc_type))
            c2 = len(ifc2.by_type(ifc_type))
        except:
            continue
        if c1 == c2: continue
        diff = c2 - c1
        human = ifc_type.replace('Ifc','').replace('StandardCase','')
        changes.append({
            'category': 'IFC_ELEMENT',
            'severity': 'high' if abs(diff) >= 3 else 'medium',
            'type': 'added' if diff > 0 else 'removed',
            'icon': ifc_icons.get(ifc_type, '🔷'),
            'change': f"{'Added' if diff>0 else 'Removed'} {abs(diff)} {human}{'s' if abs(diff)>1 else ''}",
            'detail': f"{c1} → {c2}",
        })

    # Storey changes
    def get_storeys(ifc):
        try:
            return {s.Name: s for s in ifc.by_type('IfcBuildingStorey')}
        except: return {}

    s1 = get_storeys(ifc1); s2 = get_storeys(ifc2)
    for name in set(s2.keys()) - set(s1.keys()):
        changes.append({'category':'STOREY','severity':'high','type':'added',
                        'icon':'🏢','change':f"New building storey: '{name}'",
                        'detail':'New floor/level added to model'})
    for name in set(s1.keys()) - set(s2.keys()):
        changes.append({'category':'STOREY','severity':'high','type':'removed',
                        'icon':'🏢','change':f"Storey removed: '{name}'",
                        'detail':'Floor/level removed from model'})

    # Total element counts
    def total_products(ifc):
        try: return len(ifc.by_type('IfcProduct'))
        except: return 0

    tp1 = total_products(ifc1); tp2 = total_products(ifc2)
    stats = {
        'file1_elements': tp1, 'file2_elements': tp2,
        'file1_storeys': len(s1), 'file2_storeys': len(s2),
        'schema1': ifc1.schema, 'schema2': ifc2.schema,
    }

    overall = ('identical' if not changes else
               'minor differences' if len(changes) <= 2 else
               'moderate changes' if len(changes) <= 6 else
               'significant changes')

    return {
        'success': True, 'fileType': 'IFC BIM Model',
        'overall': overall,
        'totalChanges': len(changes),
        'added': sum(1 for c in changes if c['type'] == 'added'),
        'removed': sum(1 for c in changes if c['type'] == 'removed'),
        'changes': changes, 'stats': stats,
    }


