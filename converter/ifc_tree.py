"""
The hierarchy of an IFC model, as a tree a person can navigate.

<p>§1A.4 is the reason this exists in the shape it does: a WebGL canvas
cannot be made conformant on its own, so the model's structure has to be
available as something a keyboard and a screen reader can walk. That makes
this the primary interface to a model rather than a sidebar beside the
picture, and the extraction has to be complete rather than indicative.
"""
import os


def extract_ifc_tree(path: str) -> dict:
    """
    Extract a hierarchical spatial structure from an IFC file.
    Returns: [{id, name, type, children, expanded, selected, visible}]
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    try:
        import ifcopenshell

        ifc = ifcopenshell.open(path)

        def get_children(element) -> list:
            children = []
            try:
                for rel in getattr(element, 'IsDecomposedBy', []):
                    for child in rel.RelatedObjects:
                        children.append(element_to_node(child))
            except Exception:
                pass
            # Also include contained elements for spaces/storeys
            try:
                for rel in getattr(element, 'ContainsElements', []):
                    for child in rel.RelatedElements:
                        children.append(element_to_node(child))
            except Exception:
                pass
            return children

        def element_to_node(element) -> dict:
            name = getattr(element, 'Name', None) or element.is_a()
            return {
                "id":       str(element.GlobalId),
                "name":     str(name),
                "type":     element.is_a(),
                "expanded": False,
                "selected": False,
                "visible":  True,
                "children": get_children(element)
            }

        # Build tree from IfcProject root
        projects = ifc.by_type("IfcProject")
        if not projects:
            return {"success": False, "error": "No IfcProject found in file"}

        tree = [element_to_node(p) for p in projects]
        return {"success": True, "tree": tree, "schema": ifc.schema}

    except ImportError:
        return {"success": False, "error": "ifcopenshell required"}
    except Exception as e:
        return {"success": False, "error": str(e)}


