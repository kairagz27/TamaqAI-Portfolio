"""Regression tests for the offline boundary and catalogue-provenance audit."""
from copy import deepcopy
import hashlib
from pathlib import Path
import tempfile
import unittest

from check_standalone import check_assets, check_catalog, check_packaged_manifest


class StandaloneAuditTest(unittest.TestCase):
    def setUp(self):
        self.source = [{'id': 'off:123', 'name': 'Synthetic audit fixture', 'nutrients': {'calories': 90},
                        'source': {'namespace': 'openfoodfacts', 'status': 'open',
                                   'url': 'https://world.openfoodfacts.org/product/123', 'checked_at': '2026-10-20',
                                   'license': 'ODbL-1.0; DbCL-1.0'}}]
        self.bundled = [dict(deepcopy(self.source[0]), version_id='standalone-off:123-1', revision=1)]
        self.notice = 'Open Food Facts ODbL Database Contents License. No Open Food Facts product photographs'

    def test_only_version_metadata_changes_are_allowed(self):
        self.assertEqual(check_catalog(self.source, self.bundled, self.notice), 1)

    def test_changed_nutrients_are_rejected(self):
        self.bundled[0]['nutrients']['calories'] = 0
        with self.assertRaisesRegex(AssertionError, 'changed reviewed source fields'):
            check_catalog(self.source, self.bundled, self.notice)

    def test_duplicate_records_are_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'Duplicate standalone catalogue IDs'):
            check_catalog(self.source, self.bundled * 2, self.notice)

    def test_missing_notice_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'attribution notice incomplete'):
            check_catalog(self.source, self.bundled, '')

    def manifest(self, permission=''):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / 'manifest.xml'
        path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                        'package="com.tamaqai.standalone" android:versionName="1.4.0-standalone.1">' + permission +
                        '<application android:name="com.tamaqai.TamaqStandaloneApplication" android:allowBackup="false"/>'
                        '</manifest>', encoding='utf-8')
        return path

    def test_packaged_manifest_without_internet_passes(self):
        check_packaged_manifest(self.manifest())

    def test_internet_returning_through_dependency_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'INTERNET permission'):
            check_packaged_manifest(self.manifest('<uses-permission android:name="android.permission.INTERNET"/>'))

    def test_wrong_package_is_rejected(self):
        path = self.manifest()
        path.write_text(path.read_text().replace('package="com.tamaqai.standalone"', 'package="com.tamaqai"'))
        with self.assertRaisesRegex(AssertionError, 'not isolated'):
            check_packaged_manifest(path)

    def asset_fixture(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        image = b'Original synthetic audit bytes, not a food photograph'
        sha = hashlib.sha256(image).hexdigest()
        (root / 'fixture.jpg').write_bytes(image)
        (root / 'notice.txt').write_text('Synthetic Author CC0 ' + sha)
        (root / 'legal.txt').write_text('Synthetic CC0 Waiver test fixture')
        record = {'assets': [{'path': 'fixture.jpg', 'sha256': sha, 'license': 'CC0-1.0',
                              'source_page': 'https://isorepublic.com/photo/synthetic-audit/',
                              'creator': 'Synthetic Author', 'notice': 'notice.txt', 'legalcode': 'legal.txt'}],
                  'license_files': [{'path': 'legal.txt', 'sha256': hashlib.sha256((root / 'legal.txt').read_bytes()).hexdigest()}]}
        return root, record

    def test_reviewed_asset_and_notice_pass(self):
        root, record = self.asset_fixture()
        self.assertEqual(check_assets(root, record), 1)

    def test_changed_asset_bytes_are_rejected(self):
        root, record = self.asset_fixture()
        (root / 'fixture.jpg').write_bytes(b'Unreviewed replacement')
        with self.assertRaisesRegex(AssertionError, 'checksum differs'):
            check_assets(root, record)

    def test_missing_legal_code_is_rejected(self):
        root, record = self.asset_fixture()
        (root / 'legal.txt').unlink()
        with self.assertRaisesRegex(AssertionError, 'Missing or unsafe'):
            check_assets(root, record)


if __name__ == '__main__':
    unittest.main()
