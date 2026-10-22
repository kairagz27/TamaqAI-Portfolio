"""Snapshot gate regressions in disposable Git repositories, no user files."""
from contextlib import redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_snapshot


class SnapshotGateTest(unittest.TestCase):
    def setUp(self):
        self.private = tempfile.TemporaryDirectory()
        self.addCleanup(self.private.cleanup)
        self.root = Path(self.private.name)
        self.git('init', '-q')
        self.git('config', 'core.autocrlf', 'false')
        (self.root / 'payload.txt').write_bytes(b'synthetic fixture\n')
        self.prepare()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.DEVNULL)

    def prepare(self):
        self.git('add', '.')
        paths = self.git('ls-files', '-z').decode().split('\0')
        files = [{'path': name, 'sha256': hashlib.sha256(self.git('show', ':' + name)).hexdigest()}
                 for name in paths if name and name != check_snapshot.MANIFEST]
        manifest = self.root / check_snapshot.MANIFEST
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(json.dumps({'source_commit': '4d1473f8a34e230f0b0203d5f036bdb955eed603',
                                       'files': files}), encoding='utf-8')
        self.git('add', '.')

    def verify(self):
        with patch.object(check_snapshot, 'ROOT', self.root), redirect_stdout(io.StringIO()):
            check_snapshot.verify()

    def test_reviewed_snapshot_passes(self):
        self.verify()

    def test_unstaged_change_is_rejected(self):
        (self.root / 'payload.txt').write_bytes(b'changed\n')
        with self.assertRaisesRegex(AssertionError, 'Unstaged'):
            self.verify()

    def test_staged_change_is_rejected(self):
        (self.root / 'payload.txt').write_bytes(b'changed\n')
        self.git('add', 'payload.txt')
        with self.assertRaisesRegex(AssertionError, 'hash differs'):
            self.verify()

    def test_unreviewed_tracked_file_is_rejected(self):
        (self.root / 'unexpected.txt').write_bytes(b'not reviewed\n')
        self.git('add', 'unexpected.txt')
        with self.assertRaisesRegex(AssertionError, 'Unreviewed'):
            self.verify()

    def test_eol_attributes_use_canonical_git_bytes(self):
        (self.root / '.gitattributes').write_bytes(b'payload.txt text eol=crlf\n')
        (self.root / 'payload.txt').write_bytes(b'synthetic fixture\r\n')
        self.prepare()
        self.verify()


if __name__ == '__main__':
    unittest.main()
