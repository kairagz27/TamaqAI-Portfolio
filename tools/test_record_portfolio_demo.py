"""Mocked host checks: no adb, service, real credential or device is used."""
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import record_portfolio_demo as recording


def result(stdout=b'', code=0):
    return subprocess.CompletedProcess([], code, stdout, b'')


class RecorderTest(unittest.TestCase):
    def recorder(self):
        recorder = recording.RemoteRecorder(['fake-adb', '-s', 'isolated-emulator'])
        recorder.process = Mock()
        recorder.process.poll.return_value = None
        return recorder

    def test_output_paths_and_pidfiles_are_unique_per_run(self):
        first, second = self.recorder(), self.recorder()
        self.assertNotEqual(first.remote_video, second.remote_video)
        self.assertNotEqual(first.pidfile, second.pidfile)
        self.assertTrue(first.pidfile.startswith('/data/local/tmp/tamaq-portfolio-'))

    def test_start_publishes_only_its_child_pid_with_bounded_recording_and_trap(self):
        recorder = self.recorder()
        with patch.object(recording.subprocess, 'Popen') as popen:
            recorder.start()
        command = popen.call_args.args[0]
        script = shlex.split(command[-1])[2]
        self.assertIn('child=$!', script)
        self.assertIn(recorder.pidfile, script)
        self.assertIn('--time-limit 90', script)
        self.assertIn(recorder.remote_video, script)
        self.assertIn('HUP INT TERM', script)
        self.assertNotIn('pidof', script)

    def test_match_requires_exact_output_argument_and_recorder_executable(self):
        video = self.recorder().remote_video
        self.assertTrue(recording.matches_recording(b'/system/bin/screenrecord\0' + video.encode() + b'\0', video))
        for cmdline in (b'screenrecord\0' + video.encode() + b'.other\0',
                        b'screenrecord\0--comment=' + video.encode() + b'\0',
                        b'other\0' + video.encode() + b'\0', b''):
            self.assertFalse(recording.matches_recording(cmdline, video))

    def test_stop_retries_startup_pidfile_race_and_signals_only_registered_child(self):
        recorder = self.recorder()
        cmdline = b'screenrecord\0--time-limit\090\0' + recorder.remote_video.encode() + b'\0'
        with patch.object(recorder, 'run', side_effect=[result(code=1), result(b'4321\n'),
                                                       result(cmdline), result()]) as run, \
                patch.object(recording.time, 'sleep'):
            recorder.stop()
            recorder.stop()
        self.assertEqual(4, run.call_count)
        self.assertEqual(('shell', 'cat', '/proc/4321/cmdline'), run.call_args_list[2].args)
        script = shlex.split(run.call_args_list[3].args[1])[2]
        self.assertIn('grep -F -x -q -- ' + recorder.remote_video, script)
        self.assertIn('kill -2 4321', script)
        self.assertIn('/proc/4321/cmdline', script)
        self.assertNotIn('pidof', str(run.call_args_list))
        recorder.process.wait.assert_called_once_with(timeout=15)

    def test_reused_pid_or_other_recording_is_never_signalled(self):
        for cmdline in (b'screenrecord\0/sdcard/someone-else.mp4\0', b'screenrecord\0'):
            with self.subTest(cmdline=cmdline):
                recorder = self.recorder()
                with patch.object(recorder, 'run', side_effect=[result(b'4321'), result(cmdline)]) as run:
                    with self.assertRaisesRegex(RuntimeError, 'no longer belongs'):
                        recorder.stop()
                self.assertEqual(2, run.call_count)
                self.assertTrue(all(call.args[1] == 'cat' for call in run.call_args_list))

    def test_malformed_or_group_pid_is_never_signalled(self):
        for pid in (b'0', b'1', b'-1', b'4321 5678', b'123; kill -2 9'):
            with self.subTest(pid=pid):
                recorder = self.recorder()
                with patch.object(recorder, 'run', return_value=result(pid)) as run:
                    with self.assertRaisesRegex(RuntimeError, 'invalid'):
                        recorder.stop()
                run.assert_called_once_with('shell', 'cat', recorder.pidfile)

    def test_already_exited_child_and_missing_pidfile_need_no_signal(self):
        recorder = self.recorder()
        recorder.process.poll.return_value = 0
        with patch.object(recorder, 'run', return_value=result(code=1)) as run:
            recorder.stop()
            recorder.stop()
        run.assert_called_once_with('shell', 'cat', recorder.pidfile)

    def test_acceptance_rejects_recorder_that_exited_before_intentional_stop(self):
        for status in (0, 1):
            with self.subTest(status=status):
                recorder = self.recorder()
                recorder.process.poll.return_value = status
                with patch.object(recorder, 'run') as run:
                    with self.assertRaisesRegex(RuntimeError, 'before intentional stop'):
                        recorder.stop(require_active=True)
                run.assert_not_called()

    def test_acceptance_rechecks_exit_before_signalling(self):
        recorder = self.recorder()
        recorder.process.poll.side_effect = [None, 0]
        cmdline = b'screenrecord\0' + recorder.remote_video.encode() + b'\0'
        with patch.object(recorder, 'run', side_effect=[result(b'4321'), result(cmdline)]) as run:
            with self.assertRaisesRegex(RuntimeError, 'before intentional stop'):
                recorder.stop(require_active=True)
        self.assertEqual(2, run.call_count)
        recorder.process.wait.assert_not_called()

    def test_acceptance_rejects_nonzero_exit_but_cleanup_remains_tolerant(self):
        recorder = self.recorder()
        recorder.process.wait.return_value = 1
        cmdline = b'screenrecord\0' + recorder.remote_video.encode() + b'\0'
        with patch.object(recorder, 'run', side_effect=[result(b'4321'), result(cmdline),
                                                       result(), result()]) as run:
            with self.assertRaisesRegex(RuntimeError, 'exit successfully'):
                recorder.stop(require_active=True)
            recorder.process.poll.return_value = 1
            recorder.cleanup()
        self.assertEqual(1, sum('kill -2 4321' in str(call) for call in run.call_args_list))
        recorder.process.terminate.assert_not_called()

    def test_missing_pidfile_timeout_is_bounded(self):
        recorder = self.recorder()
        with patch.object(recorder, 'run', return_value=result(code=1)) as run, \
                patch.object(recording.time, 'monotonic', side_effect=[0, 4]):
            with self.assertRaisesRegex(RuntimeError, 'own PID'):
                recorder.stop()
        self.assertEqual(1, run.call_count)

    def test_cleanup_stops_own_child_and_removes_only_own_remote_files(self):
        recorder = self.recorder()
        cmdline = b'screenrecord\0' + recorder.remote_video.encode() + b'\0'
        recorder.process.poll.side_effect = [0, 0]
        with patch.object(recorder, 'run', side_effect=[result(b'4321'), result(cmdline),
                                                       result(), result(), result()]) as run:
            recorder.cleanup()
            recorder.cleanup()
        self.assertEqual(('shell', 'rm', '-f', recorder.pidfile, recorder.remote_video), run.call_args.args)
        self.assertEqual(1, sum('kill -2 4321' in str(call) for call in run.call_args_list))
        recorder.process.terminate.assert_not_called()

    def test_cleanup_refuses_unrelated_pid_even_when_local_adb_needs_termination(self):
        recorder = self.recorder()
        with patch.object(recorder, 'run', side_effect=[result(b'4321'),
                result(b'screenrecord\0/unrelated.mp4\0'), result()]) as run:
            recorder.cleanup()
        recorder.process.terminate.assert_called_once()
        self.assertFalse(any('kill -2' in str(call) for call in run.call_args_list))

    def test_failed_journey_always_cleans_up_the_started_recorder(self):
        with tempfile.TemporaryDirectory(prefix='mocked-recording-') as folder:
            root = Path(folder)
            (root / '.demo').mkdir()
            (root / '.demo/user.json').write_text(json.dumps({'email': 'demo@example.com',
                'password': 'synthetic-test-only'}), encoding='utf-8')
            journey = Mock()
            journey.poll.return_value = None
            journey.wait.side_effect = subprocess.TimeoutExpired('mocked-instrumentation', 150)
            health = Mock()
            health.__enter__ = Mock(return_value=io.BytesIO(b'{"demo":true}'))
            health.__exit__ = Mock(return_value=False)
            with patch.object(recording, 'ROOT', root), \
                    patch.object(sys, 'argv', ['record', '--serial', 'mocked-device']), \
                    patch.object(recording.shutil, 'which', return_value='fake-adb'), \
                    patch.object(recording.urllib.request, 'urlopen', return_value=health), \
                    patch.object(recording.subprocess, 'run', return_value=result(b'ready')), \
                    patch.object(recording.subprocess, 'Popen', return_value=journey), \
                    patch.object(recording, 'RemoteRecorder') as recorder_type, \
                    patch.object(recording.time, 'sleep'):
                with self.assertRaises(subprocess.TimeoutExpired):
                    recording.main()
            recorder_type.return_value.start.assert_called_once()
            recorder_type.return_value.cleanup.assert_called_once()
            journey.terminate.assert_called_once()
            self.assertFalse((root / '.android/portfolio-recording/recording.json').exists())


if __name__ == '__main__':
    unittest.main()
