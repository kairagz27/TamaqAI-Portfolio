import unittest
from audit_publication_history import forbidden_path,kinds

class HistoryAuditTest(unittest.TestCase):
    def test_private_paths_and_templates(self):
        for name in ('.demo/backend.env','backend/.env.local','x/debug.keystore','db/data.sqlite','app/build/a.apk'):
            self.assertTrue(forbidden_path(name))
        self.assertFalse(forbidden_path('backend/.env.example'))
        self.assertFalse(forbidden_path('backend/tamaq/db/migrations/001_usage.sql'))
    def test_live_value_does_not_appear_in_report(self):
        secret=b'generated-fixture-value-do-not-use'
        result=kinds(b'prefix '+secret,'source.txt',{secret})
        self.assertIn('matches-current-private-configuration',result)
        self.assertNotIn(secret.decode(),str(result))
    def test_reserved_fixture_address_is_not_personal(self):
        self.assertNotIn('non-example-email-review',kinds(b'demo@example.com','test.py',set()))
        self.assertIn('non-example-email-review',kinds(b'fixture@unverified-domain.io','x.md',set()))
    def test_reference_license_requires_review(self):
        self.assertIn('unconfirmed-reference-image-license',kinds(b'fake','docs/design/dark/reference-forest.png',set()))

if __name__=='__main__':unittest.main()
