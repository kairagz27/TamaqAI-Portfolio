"""Check current engineering-doc links, without rewriting historical reports."""
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
paths = [ROOT / name for name in ('README.md', 'README.ru.md', 'SECURITY.md',
    'CONTRIBUTING.md', 'THIRD_PARTY_NOTICES.md', 'CHANGELOG.md')]
paths += [ROOT / 'docs' / name for name in ('README.md', 'ARCHITECTURE.md',
    'DEVELOPMENT.md', 'DATA_SOURCES.md', 'ROADMAP.md', 'DEMO.md')]
paths += list((ROOT / 'docs').glob('PUBLICATION_*.md'))
paths += list((ROOT / 'docs/adr').glob('*.md'))
checked = 0
for path in paths:
    text = path.read_text(encoding='utf-8-sig')
    for link in re.findall(r'\]\(([^)]+)\)', text):
        link = link.strip('<>')
        if urlsplit(link).scheme or link.startswith('#'):
            continue
        target = (path.parent / unquote(link.split('#', 1)[0])).resolve()
        if not target.is_relative_to(ROOT) or not target.is_file():
            raise RuntimeError('Broken current-doc link: ' + str(path.relative_to(ROOT)) + ' -> ' + link)
        checked += 1
print({'documents': len(paths), 'local_links_checked': checked, 'passed': True})
