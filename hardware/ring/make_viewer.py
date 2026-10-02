"""Embed the Blender mesh export in a standalone HTML viewer; no CDN/runtime dependencies."""
from pathlib import Path
p=Path(__file__).resolve().parent
template=(p/'viewer-template.html').read_text(encoding='utf-8')
data=(p/'model-data.json').read_text(encoding='utf-8')
(p/'ring-viewer.html').write_text(template.replace('/*MODEL_DATA*/[]',data),encoding='utf-8')
print('Created offline ring-viewer.html')
