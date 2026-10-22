import unittest
from unittest.mock import MagicMock, patch
from tamaq.server import main


class ContainerLauncherTest(unittest.TestCase):
    def run_launcher(self, symlink=False):
        mount, files, database = MagicMock(), MagicMock(), MagicMock()
        files.is_symlink.return_value = symlink
        database.parent.resolve.return_value = mount.resolve.return_value
        calls = MagicMock()
        with patch('tamaq.server.Path', side_effect=lambda value: {
            '/data': mount, '/files': files, '/data/tamaq.sqlite3': database
        }[value]), patch.dict('os.environ', {
            'TAMAQ_FILES_ROOT': '/files', 'TAMAQ_DATABASE_URL': 'test',
            'TAMAQ_DATABASE': '/data/tamaq.sqlite3'
        }), patch('os.geteuid', return_value=0, create=True), \
             patch('os.chown', create=True) as chown, \
             patch('os.chmod') as chmod, patch('os.setgroups', create=True), \
             patch('os.setgid', create=True), patch('os.setuid', create=True) as setuid, \
             patch('os.umask'), patch('os.execvp') as execute:
            calls.attach_mock(chown, 'chown')
            calls.attach_mock(setuid, 'setuid')
            calls.attach_mock(chmod, 'chmod')
            calls.attach_mock(execute, 'exec')
            if symlink:
                with self.assertRaisesRegex(RuntimeError, 'symlinks'):
                    main()
                setuid.assert_not_called()
                execute.assert_not_called()
            else:
                main()
                chmod.assert_called_once_with(files, 0o700)
                names = [item[0] for item in calls.mock_calls]
                self.assertLess(names.index('setuid'), names.index('chmod'))
                self.assertLess(names.index('chmod'), names.index('exec'))
                self.assertEqual(10001, setuid.call_args.args[0])

    def test_private_file_permissions_applied_after_dropping_to_owner(self):
        self.run_launcher()

    def test_avatar_mount_symlink_rejected_before_serving(self):
        self.run_launcher(symlink=True)
