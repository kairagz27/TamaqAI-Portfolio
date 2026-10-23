"""Static standalone boundary/provenance checks; runtime verification is a separate step.

Optionally pass XML produced from the *packaged APK* by `apkanalyzer manifest print`.
This tool does not start Docker, use the network, read private data or contact an AI.
"""
import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'


def check_catalog(source, bundled, notice):
    """Only version identifiers may differ from the separately attributed OFF seed."""
    assert isinstance(bundled, list) and bundled, 'Standalone catalogue must not be empty'
    expected = {item['id']: item for item in source}
    assert len(expected) == len(source), 'Duplicate source catalogue IDs'
    actual = {item['id']: item for item in bundled}
    assert len(actual) == len(bundled), 'Duplicate standalone catalogue IDs'
    assert actual.keys() == expected.keys(), 'Standalone catalogue differs from reviewed source selection'
    versions = set()
    for entry in bundled:
        version = entry['version_id']
        assert version not in versions, 'Duplicate standalone version IDs'
        versions.add(version)
        assert version == 'standalone-' + entry['id'] + '-1' and entry['revision'] == 1, 'Unexpected catalogue version mapping'
        original = {key: value for key, value in entry.items() if key not in {'version_id', 'revision'}}
        assert original == expected[entry['id']], 'Standalone catalogue changed reviewed source fields'
        origin = entry['source']
        assert origin['namespace'] == 'openfoodfacts' and origin['status'] == 'open', 'Unreviewed catalogue origin'
        assert origin['url'].startswith('https://world.openfoodfacts.org/product/'), 'Catalogue source link missing'
        assert 'ODbL-1.0' in origin['license'] and 'DbCL-1.0' in origin['license'], 'Per-record attribution missing'
        date.fromisoformat(origin['checked_at'])
    assert all(value in notice for value in ('Open Food Facts', 'ODbL', 'Database Contents License', 'No Open Food Facts product photographs')), 'Bundled attribution notice incomplete'
    return len(bundled)


def check_packaged_manifest(path):
    root = ET.parse(path).getroot()
    assert root.get('package') == 'com.tamaqai.standalone', 'Packaged application ID is not isolated'
    assert root.get(ANDROID + 'versionName') == '1.4.0-standalone.1', 'Wrong standalone APK version'
    assert not any(node.get(ANDROID + 'name') == 'android.permission.INTERNET'
                   for node in root if node.tag.startswith('uses-permission')), 'Packaged standalone APK has INTERNET permission'
    app = root.find('application')
    assert app is not None, 'Packaged application is missing'
    assert app.get(ANDROID + 'name', '').endswith('.TamaqStandaloneApplication'), 'Wrong standalone composition root'
    assert app.get(ANDROID + 'allowBackup') == 'false', 'Standalone backups must stay disabled'


def check_assets(root, record):
    def local(path):
        value = (root / path).resolve()
        assert value.is_relative_to(root.resolve()) and value.is_file(), 'Missing or unsafe licensed-asset path'
        return value

    assert record['assets'], 'Standalone asset provenance missing'
    for asset in record['assets']:
        path = local(asset['path'])
        assert hashlib.sha256(path.read_bytes()).hexdigest() == asset['sha256'], 'Bundled asset checksum differs from reviewed bytes'
        assert asset['license'] == 'CC0-1.0', 'Unreviewed standalone image license'
        assert asset['source_page'].startswith('https://isorepublic.com/photo/'), 'Source page missing'
        notice = local(asset['notice']).read_text(encoding='utf-8')
        assert asset['creator'] in notice and asset['sha256'] in notice and 'CC0' in notice, 'Standalone image notice incomplete'
        legal = local(asset['legalcode']).read_text(encoding='utf-8')
        assert 'CC0' in legal and 'Waiver' in legal, 'Full CC0 legal code missing'
    for item in record['license_files']:
        assert hashlib.sha256(local(item['path']).read_bytes()).hexdigest() == item['sha256'], 'License text checksum differs'
    return len(record['assets'])


def verify(root=ROOT, packaged_manifest=None):
    standalone = root / 'app/src/standaloneDemo'
    manifest = ET.parse(standalone / 'AndroidManifest.xml').getroot()
    assert any(node.get(ANDROID + 'name') == 'android.permission.INTERNET' and node.get(TOOLS + 'node') == 'remove'
               for node in manifest.findall('uses-permission')), 'Standalone source manifest must remove INTERNET'
    build = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
    assert 'create("standaloneDemo")' in build and '"tamaqStandaloneTest"' in build, 'Standalone variant/test selector missing'
    assert 'sourceSets["standaloneDemo"].java.srcDir("src/debug/java")' not in build, 'Server debug provider inherited by standalone'
    app = (standalone / 'java/com/tamaqai/TamaqStandaloneApplication.kt').read_text(encoding='utf-8')
    assert re.search(r'AppContainer\s*\([^)]*offline\s*=\s*true', app), 'Standalone application does not select local composition'
    # Parsing Kotlin is the compiler's job. This narrower static guard rejects direct
    # network transports/adapters in local implementations without forbidding shared DTOs.
    local_sources = list((root / 'app/src/main/java/com/tamaqai/data/standalone').glob('*.kt'))
    assert local_sources, 'Local repository implementations missing'
    forbidden = re.compile(r'import\s+(?:java\.net\.|okhttp|retrofit)|\b(?:SessionClient|SecureSessions|BackendFoodAnalysisRepository)\s*\(')
    for path in local_sources:
        assert not forbidden.search(path.read_text(encoding='utf-8')), 'Direct networking in a local implementation: ' + path.name
    assets = standalone / 'assets/standalone'
    count = check_catalog(json.loads((root / 'backend/catalog_seed/openfoodfacts-kz.json').read_text(encoding='utf-8'))['entries'],
                          json.loads((assets / 'catalog.json').read_text(encoding='utf-8')),
                          (assets / 'catalog-LICENSE.txt').read_text(encoding='utf-8'))
    image_count = check_assets(root, json.loads((root / 'docs/licenses/standalone-assets.json').read_text(encoding='utf-8')))
    if packaged_manifest:
        check_packaged_manifest(packaged_manifest)
    return {'static_boundary_checked': True, 'local_implementation_files': len(local_sources),
            'attributed_catalogue_records': count, 'catalogue_fields_match_reviewed_seed': True,
            'licensed_sample_images': image_count,
            'packaged_manifest_checked': packaged_manifest is not None,
            'runtime_verified_by_this_tool': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, help='XML from apkanalyzer manifest print of the built standalone APK')
    arguments = parser.parse_args()
    print(json.dumps(verify(packaged_manifest=arguments.manifest)))
