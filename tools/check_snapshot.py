"""Verify the explicit snapshot manifest; never print matched private values.

Maintainers update the manifest only after reviewing each staged path. This tool
does not import files, bless unknown paths or refresh expected hashes.
"""
from pathlib import Path
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = 'docs/export/snapshot-manifest.json'
# Reviewed synthetic offline demonstration. No wildcard media exemption.
# Provenance and visual review: docs/screenshots/current-20261109/README.md.
REVIEWED_VIDEO = ('docs/media/tamaqai-current-standalone.mp4',
                  'c7eea2569be9ead7c9119b7e565c4a6477e123251b9d2590f6f7f930370a89b9')


def artifact_allowed(name, digest):
    suffix = Path(name).suffix.lower()
    if suffix == '.mp4':
        return (name, digest) == REVIEWED_VIDEO
    return suffix not in {'.apk', '.db', '.sqlite3', '.keystore'}



def verify():
    document = json.loads((ROOT / MANIFEST).read_text(encoding='utf-8'))
    assert document['source_commit'] == '4d1473f8a34e230f0b0203d5f036bdb955eed603'
    entries = document['files']
    expected = {item['path']: item['sha256'] for item in entries}
    assert len(entries) == len(expected), 'Duplicate manifest paths'
    tracked = set(subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')) - {''}
    assert tracked == set(expected) | {MANIFEST}, 'Unreviewed or missing tracked paths'
    # Hash canonical Git blobs so text eol attributes behave identically on
    # Windows/Linux. Also reject unstaged changes rather than overlooking them.
    assert subprocess.run(['git', 'diff', '--quiet'], cwd=ROOT).returncode == 0, 'Unstaged snapshot changes'
    for name, digest in expected.items():
        path = (ROOT / name).resolve()
        assert path.is_relative_to(ROOT) and path.is_file(), 'Invalid manifest path'
        data = subprocess.check_output(['git', 'show', ':' + name], cwd=ROOT)
        assert hashlib.sha256(data).hexdigest() == digest, 'Snapshot hash differs: ' + name
    assert all(artifact_allowed(name, expected.get(name)) for name in tracked), 'Unreviewed binary artifact'
    print(json.dumps({'snapshot_files_verified': len(expected), 'unexpected_tracked_paths': 0,
                      'source_commit': document['source_commit']}))


if __name__ == '__main__':
    verify()
