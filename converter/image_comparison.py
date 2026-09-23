"""
Comparing two images, and comparing two files that are not alike.

<p>The image path is a pixel difference: it says how much of the picture
changed and where, which is the only honest thing to say about two rasters
with no structure to diff. The metadata path is the fallback for a pair the
service cannot compare on content at all — a PDF against a DWG, say — and it
deliberately reports only what it can stand behind: size, type, and name.
"""
import os
from pathlib import Path


def _compare_images(path1, path2) -> dict:
    try:
        import numpy as np
        from PIL import Image
    except ImportError:
        return {"success": False, "error": "Pillow not installed. Run: pip install Pillow"}

    try:
        img1 = np.array(Image.open(path1).convert('RGB'), dtype=np.float32)
        img2 = np.array(Image.open(path2).convert('RGB'), dtype=np.float32)
    except Exception as e:
        return {"success": False, "error": f"Cannot open images: {e}"}

    changes = []
    h1, w1 = img1.shape[:2]
    h2, w2 = img2.shape[:2]

    if (h1, w1) != (h2, w2):
        changes.append({'category':'SIZE','severity':'high','type':'modified',
                        'icon':'📐',
                        'change':f"Image dimensions changed: {w1}×{h1} → {w2}×{h2}",
                        'detail':'Different canvas size'})
        # Resize for pixel comparison
        from PIL import Image as PILImage
        img2_resized = np.array(PILImage.open(path2).convert('RGB').resize((w1,h1)), dtype=np.float32)
        diff = np.abs(img1 - img2_resized)
    else:
        diff = np.abs(img1 - img2)

    total_pixels = img1.shape[0] * img1.shape[1]
    changed_pixels = int(np.sum(np.any(diff > 10, axis=2)))
    pct = changed_pixels / total_pixels * 100

    if pct > 0.1:
        severity = 'high' if pct > 20 else 'medium' if pct > 5 else 'low'
        changes.append({'category':'PIXELS','severity':severity,'type':'modified',
                        'icon':'🖼',
                        'change':f"{pct:.1f}% of pixels differ between images",
                        'detail':f"{changed_pixels:,} of {total_pixels:,} pixels changed"})

    overall = ('identical' if pct < 0.1 else
               f'{pct:.1f}% pixel difference')

    return {
        'success': True, 'fileType': 'Image',
        'overall': overall,
        'totalChanges': len(changes),
        'added': 0, 'removed': 0,
        'changes': changes,
        'stats': {'width1':w1,'height1':h1,'width2':w2,'height2':h2,
                  'pixel_diff_pct': round(pct,2)},
    }


# ── Metadata comparison (mixed types) ────────────────────────
def _compare_metadata(path1, path2) -> dict:
    s1 = os.path.getsize(path1)
    s2 = os.path.getsize(path2)
    changes = []
    if s1 != s2:
        diff_pct = abs(s2-s1)/s1*100 if s1 > 0 else 100
        changes.append({'category':'SIZE','severity':'medium','type':'modified',
                        'icon':'💾',
                        'change':f"File size changed by {diff_pct:.1f}%",
                        'detail':f"{s1:,} bytes → {s2:,} bytes"})
    return {
        'success': True, 'fileType': 'File',
        'overall': 'different file sizes' if changes else 'same size',
        'totalChanges': len(changes),
        'added': 0, 'removed': 0,
        'changes': changes,
        'stats': {'size1': s1, 'size2': s2},
    }
