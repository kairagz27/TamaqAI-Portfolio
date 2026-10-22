"""Host-only smoke recovery tests: no configuration, services or database access."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, call, patch
from urllib.error import HTTPError


SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'portfolio_demo.py'
SPEC = importlib.util.spec_from_file_location('portfolio_demo_helper', SCRIPT)
demo = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(demo)


class PortfolioDemoSmokeTest(unittest.TestCase):
    def setUp(self):
        self.user = {'email': 'portfolio@example.com', 'password': 'synthetic-password-fixture',
                     'invitation': 'synthetic-invitation-fixture', 'link_operation': 'fixture-operation'}
        private_file = Mock()
        private_file.read_text.return_value = json.dumps(self.user)
        self.enterContext(patch.object(demo, 'USER', private_file))
        self.http = self.enterContext(patch.object(demo, 'call', autospec=True))
        self.sleep = self.enterContext(patch.object(demo.time, 'sleep', autospec=True))
        self.output = self.enterContext(patch('builtins.print'))
        self.enterContext(patch.object(demo.urllib.request, 'urlopen',
                                      side_effect=AssertionError('Network access is forbidden')))
        self.health = {'status': 'ok', 'demo': True}
        self.login = {'access_token': 'synthetic-access-fixture'}
        self.authorization = {'Authorization': 'Bearer synthetic-access-fixture'}
        self.unverified = {'email_verified': False, 'pilot_linked': True}
        self.verified = {'email_verified': True, 'pilot_linked': True}
        self.questionnaire = {'document': {'completed': True}}
        self.stale = self.message('stale')

    def message(self, message_id, email='portfolio@example.com'):
        return {'ID': message_id, 'To': [{'Address': email}]}

    def request_paths(self):
        return [request.args[0] for request in self.http.call_args_list]

    def assert_summary(self, created):
        summary = json.loads(self.output.call_args.args[0])
        self.assertEqual(created, summary['registration_created_now'])
        self.assertEqual(not created, summary['existing_account_reused'])
        self.assertEqual(0, summary['paid_calls'])
        for private in (self.user['email'], self.user['password'], self.user['invitation'],
                        self.login['access_token']):
            self.assertNotIn(private, self.output.call_args.args[0])

    def test_verified_account_reuse_does_not_read_mail_or_request_resend(self):
        self.http.side_effect = [self.health, self.login, self.verified,
                                 self.questionnaire, {'revoked': True}]

        demo.smoke()

        self.assertEqual(['/health', '/v1/auth/login', '/v1/users/me',
                          '/v1/nutrition/questionnaire', '/v1/auth/logout'], self.request_paths())
        self.sleep.assert_not_called()
        self.assert_summary(created=False)

    def test_new_account_uses_initial_email_without_resending_or_consuming_stale_mail(self):
        initial_token = 'initial-verification-fixture-token-123456'
        self.http.side_effect = [
            self.health, HTTPError(demo.ORIGIN + '/v1/auth/login', 401, '', {}, None),
            {'messages': [self.stale]}, {'accepted': True}, self.login, self.unverified,
            {'messages': [self.stale, self.message('other', 'other@example.com'), self.message('initial')]},
            {'Text': initial_token}, {'completed': True}, self.verified,
            self.questionnaire, {'revoked': True},
        ]

        demo.smoke()

        paths = self.request_paths()
        self.assertLess(paths.index('/api/v1/messages'), paths.index('/v1/auth/register'))
        self.assertNotIn('/v1/auth/resend-verification', paths)
        self.assertNotIn('/api/v1/message/stale', paths)
        self.assertNotIn('/api/v1/message/other', paths)
        self.assertIn(call('/v1/auth/verify-email', {'token': initial_token}), self.http.call_args_list)
        self.assert_summary(created=True)

    def test_unverified_existing_account_resends_once_and_waits_for_new_mail(self):
        fresh_token = 'resent-verification-fixture-token-1234567'
        self.http.side_effect = [
            self.health, self.login, self.unverified, {'messages': [self.stale]},
            {'accepted': True}, {'messages': [self.stale]},
            {'messages': [self.stale, self.message('fresh')]}, {'Text': fresh_token},
            {'completed': True}, self.verified, self.questionnaire, {'revoked': True},
        ]

        demo.smoke()

        paths = self.request_paths()
        self.assertEqual(1, paths.count('/v1/auth/resend-verification'))
        self.assertLess(paths.index('/api/v1/messages'), paths.index('/v1/auth/resend-verification'))
        self.assertNotIn('/api/v1/message/stale', paths)
        self.assertNotIn('/v1/auth/register', paths)
        self.assertIn(call('/v1/auth/resend-verification', {}, self.authorization), self.http.call_args_list)
        self.assertIn(call('/v1/auth/verify-email', {'token': fresh_token}), self.http.call_args_list)
        self.sleep.assert_called_once_with(.25)
        self.assert_summary(created=False)

    def test_missing_new_mail_has_bounded_polling_and_no_resend_retry(self):
        stale_inbox = {'messages': [self.stale]}
        self.http.side_effect = [self.health, self.login, self.unverified, stale_inbox,
                                 {'accepted': True}] + [stale_inbox] * 20

        with self.assertRaisesRegex(RuntimeError, '^No local verification message$'):
            demo.smoke()

        paths = self.request_paths()
        self.assertEqual(1, paths.count('/v1/auth/resend-verification'))
        self.assertEqual(21, paths.count('/api/v1/messages'))
        self.assertNotIn('/api/v1/message/stale', paths)
        self.assertNotIn('/v1/auth/verify-email', paths)
        self.assertEqual(20, self.sleep.call_count)
        self.output.assert_not_called()

    def test_unverified_account_recovers_after_mailpit_inbox_is_lost(self):
        fresh_token = 'replacement-verification-fixture-12345678'
        self.http.side_effect = [
            self.health, self.login, self.unverified, {'messages': []}, {'accepted': True},
            {'messages': [self.message('replacement')]}, {'Text': fresh_token},
            {'completed': True}, self.verified, self.questionnaire, {'revoked': True},
        ]

        demo.smoke()

        self.assertEqual(1, self.request_paths().count('/v1/auth/resend-verification'))
        self.assertIn(call('/v1/auth/verify-email', {'token': fresh_token}), self.http.call_args_list)
        self.assert_summary(created=False)

    def test_resend_rate_limit_is_propagated_without_retry_or_stale_mail_consumption(self):
        limited = HTTPError(demo.ORIGIN + '/v1/auth/resend-verification', 429, '', {}, None)
        self.http.side_effect = [self.health, self.login, self.unverified,
                                 {'messages': [self.stale]}, limited]

        with self.assertRaises(HTTPError) as raised:
            demo.smoke()

        self.assertEqual(429, raised.exception.code)
        paths = self.request_paths()
        self.assertEqual(1, paths.count('/v1/auth/resend-verification'))
        self.assertEqual(1, paths.count('/api/v1/messages'))
        self.assertNotIn('/v1/auth/verify-email', paths)
        self.sleep.assert_not_called()
        self.output.assert_not_called()


if __name__ == '__main__':
    unittest.main()
