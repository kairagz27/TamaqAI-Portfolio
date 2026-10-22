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
    assert not any((ROOT / name).suffix.lower() in {'.apk', '.mp4', '.db', '.sqlite3', '.keystore'} for name in tracked)
    print(json.dumps({'snapshot_files_verified': len(expected), 'unexpected_tracked_paths': 0,
                      'source_commit': document['source_commit']}))


if __name__ == '__main__':
    verify()
