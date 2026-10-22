"""Validate repository-local README links and the actual screenshot gallery."""
import json
import re
from html import unescape
from pathlib import Path
from urllib.parse import unquote, urlsplit
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
text = (ROOT / 'README.md').read_text(encoding='utf-8')
if re.search(r'(?<![A-Za-z])[A-Za-z]:[\\/]|file://', text):
    raise RuntimeError('README must not contain machine-local paths')
targets = re.findall(r'\]\(([^)]+)\)', text)
images = re.findall(r'<img\s+[^>]*src="([^"]+)"[^>]*>', text)
sizes = set()
checked = 0
for target in targets + images:
    target = unescape(target).strip('<>')
    if urlsplit(target).scheme or target.startswith('#'):
        continue
    path = (ROOT / unquote(target.split('#', 1)[0])).resolve()
    if not path.is_relative_to(ROOT) or not path.is_file():
        raise RuntimeError('Broken local README link: ' + target)
    checked += 1
for target in images:
    with Image.open(ROOT / target) as picture:
        picture.verify()
    with Image.open(ROOT / target) as picture:
        sizes.add(picture.size)
        if picture.info:
            raise RuntimeError('Gallery image contains metadata: ' + target)
if len(sizes) != 1 or len(images) < 7:
    raise RuntimeError('Screenshot gallery must be complete and uniformly sized')
print(json.dumps({'local_links_valid': True, 'links_checked': checked,
                  'gallery_images': len(images), 'image_sizes': sorted(sizes),
                  'no_machine_local_paths': True, 'image_metadata_absent': True}))
