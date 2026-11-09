"""Offline safety checks for the current standalone recording controller."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools' / 'record_current_standalone.py'


class RecordingGuards(unittest.TestCase):
    def reject(self, *args):
        result = subprocess.run([sys.executable, str(SCRIPT), '--adb', 'must-not-execute', *args],
                                capture_output=True, timeout=10)
        self.assertEqual(2, result.returncode)
        return result.stderr.decode(errors='replace')

    def test_physical_device_is_refused(self):
        self.assertIn('physical devices are refused', self.reject('--serial', 'not-an-emulator', '--out', '.android/unused'))

    def test_output_outside_ignored_directory_is_refused(self):
        self.assertIn('Output must be under ignored', self.reject('--serial', 'emulator-5556', '--out', 'docs/not-allowed'))

    def test_existing_evidence_is_not_overwritten(self):
        (ROOT / '.android').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / '.android') as directory:
            sentinel = Path(directory) / 'evidence.txt'
            sentinel.write_text('preserve', encoding='utf-8')
            self.assertIn('existing evidence is preserved', self.reject('--serial', 'emulator-5556', '--out', directory))
            self.assertEqual('preserve', sentinel.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
