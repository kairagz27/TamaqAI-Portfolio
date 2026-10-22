"""Bounded recording of the isolated demo; never reads pilot configuration.

Build/install demo + its test APK first. Values are sent over stdin, not argv.
Output must stay under ignored .android; review before distributing media.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import time
import urllib.request
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.tamaqai.demo'
REMOTE = '/sdcard/Android/data/' + PACKAGE + '/files/portfolio'


def matches_recording(cmdline: bytes, remote_video: str) -> bool:
    """Compare complete /proc arguments, including this run's unique output."""
    arguments = cmdline.rstrip(b'\0').split(b'\0')
    return (arguments[0].rsplit(b'/', 1)[-1] == b'screenrecord' and
            remote_video.encode() in arguments[1:])


def signal_script(pid: str, remote_video: str) -> str:
    """Recheck ownership on-device immediately before signalling the child."""
    return (f"if tr '\\000' '\\n' < /proc/{pid}/cmdline 2>/dev/null | "
            f"grep -F -x -q -- {shlex.quote(remote_video)}; then "
            f"kill -2 {pid} 2>/dev/null || true; fi")


class RemoteRecorder:
    """Own one bounded remote child and never enumerate other recorders."""

    def __init__(self, prefix: list[str]):
        self.prefix = prefix
        scope = '/data/local/tmp/tamaq-portfolio-' + uuid4().hex
        self.pidfile = scope + '.pid'
        self.remote_video = scope + '.mp4'
        self.process = None
        self.stopped = False

    def run(self, *command, **kwargs):
        return subprocess.run(self.prefix + list(command), capture_output=True,
                              timeout=5, **kwargs)

    def start(self):
        # The shell publishes $! before waiting. Its trap also handles an adb
        # shell disconnect; both paths validate the unique output argument.
        stop_child = signal_script('"$child"', self.remote_video)
        trap_body = (f'{stop_child}; wait "$child" 2>/dev/null; '
                     f'rm -f {shlex.quote(self.pidfile)}; exit')
        script = ("umask 077; child=; "
                  f'trap {shlex.quote(trap_body)} HUP INT TERM; '
                  'screenrecord --time-limit 90 --size 720x1600 --bit-rate 4000000 '
                  f'{shlex.quote(self.remote_video)} & child=$!; '
                  f'printf "%s\\n" "$child" > {shlex.quote(self.pidfile)}; '
                  'wait "$child"; result=$?; '
                  f'rm -f {shlex.quote(self.pidfile)}; exit "$result"')
        self.process = subprocess.Popen(self.prefix + ['shell', 'sh -c ' + shlex.quote(script)],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop(self, require_active: bool = False):
        if self.process is None or self.stopped:
            if require_active:
                raise RuntimeError('Recorder is not active for intentional stop')
            return
        if require_active and self.process.poll() is not None:
            raise RuntimeError('Recorder exited before intentional stop')
        # Cleanup may run immediately after Popen, before the shell writes $!.
        deadline = time.monotonic() + 3
        while True:
            result = self.run('shell', 'cat', self.pidfile)
            pid = result.stdout.strip()
            if result.returncode == 0 and pid:
                break
            if self.process.poll() is not None:
                if require_active:
                    raise RuntimeError('Recorder exited before intentional stop')
                self.stopped = True
                return
            if time.monotonic() >= deadline:
                raise RuntimeError('Recorder did not publish its own PID')
            time.sleep(.1)
        if not pid.isdigit() or int(pid) <= 1:
            raise RuntimeError('Recorder PID file is invalid')
        pid = pid.decode('ascii')
        result = self.run('shell', 'cat', f'/proc/{pid}/cmdline')
        if require_active and self.process.poll() is not None:
            raise RuntimeError('Recorder exited before intentional stop')
        if result.returncode == 0 and matches_recording(result.stdout, self.remote_video):
            script = signal_script(pid, self.remote_video)
            self.run('shell', 'sh -c ' + shlex.quote(script), check=True)
        elif self.process.poll() is None:
            raise RuntimeError('Recorder PID no longer belongs to this recording')
        status = self.process.wait(timeout=15)
        self.stopped = True
        if require_active and status != 0:
            raise RuntimeError('Recorder did not exit successfully')

    def cleanup(self):
        """Best effort after a failed journey; preserve the original exception."""
        try:
            self.stop()
        except (OSError, RuntimeError, subprocess.SubprocessError):
            pass
        finally:
            if self.process is not None and self.process.poll() is None:
                # This is only the local adb process. The remote shell trap
                # validates ownership before stopping its child.
                try:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        self.process.kill()
                        self.process.wait(timeout=5)
                except (OSError, subprocess.SubprocessError):
                    pass
            try:
                self.run('shell', 'rm', '-f', self.pidfile, self.remote_video)
            except (OSError, subprocess.SubprocessError):
                pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', default='.android/portfolio-recording')
    args = parser.parse_args()
    output = (ROOT / args.out).resolve()
    if not output.is_relative_to(ROOT / '.android'):
        parser.error('Output must be under ignored .android/')
    if output.exists() and any(output.iterdir()):
        parser.error('Use a new empty output directory; existing evidence is preserved')
    output.mkdir(parents=True, exist_ok=True)
    adb = shutil.which('adb')
    if not adb:
        sdk = os.environ.get('ANDROID_HOME', os.path.expandvars(r'%LOCALAPPDATA%/Android/Sdk'))
        adb = str(Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb'))
    prefix = [adb, '-s', args.serial]

    def run(*command, **kwargs):
        return subprocess.run(prefix + list(command), check=True, capture_output=True, **kwargs)

    with urllib.request.urlopen('http://127.0.0.1:8100/health', timeout=5) as response:
        assert json.load(response)['demo'] is True
    run('shell', 'run-as', PACKAGE, 'pwd')  # Separate debug package required.
    run('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files')
    user = json.loads((ROOT / '.demo/user.json').read_text(encoding='utf-8'))
    assert user['email'].endswith('@example.com')
    run('reverse', 'tcp:8100', 'tcp:8100')
    run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    run('shell', 'wm', 'dismiss-keyguard')
    run('shell', 'rm', '-f', REMOTE + '/phase', REMOTE + '/go')
    run('shell', 'run-as ' + PACKAGE + ' sh -c "cat > files/portfolio-input.json"',
        input=json.dumps({key: user[key] for key in ('email', 'password')}).encode())
    recorder = None
    with (output / 'instrumentation.log').open('wb') as log:
        process = subprocess.Popen(prefix + ['shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'com.tamaqai.ui.PortfolioDemoTest', '-e', 'portfolioDemoProbe', 'true',
            '-e', 'portfolioVideo', 'true', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                phase = subprocess.run(prefix + ['shell', 'cat', REMOTE + '/phase'], capture_output=True).stdout.strip()
                if phase == b'ready':
                    break
                if process.poll() is not None:
                    raise RuntimeError('Demo stopped before recording readiness')
                time.sleep(.3)
            else:
                raise TimeoutError('Demo readiness timeout')
            recorder = RemoteRecorder(prefix)
            recorder.start()
            started = time.monotonic()
            time.sleep(.8)
            run('shell', 'touch', REMOTE + '/go')
            process.wait(timeout=150)
            log.flush()
            if 'OK (1 test)' not in (output / 'instrumentation.log').read_text(errors='replace'):
                raise RuntimeError('Demo journey did not pass; inspect sanitized log')
            while time.monotonic() - started < 61:
                time.sleep(.3)
            elapsed = time.monotonic() - started
            if elapsed >= 89:
                raise RuntimeError('Journey exceeded recording window; recording not accepted')
            recorder.stop(require_active=True)
            run('pull', REMOTE, str(output / 'capture'))
            run('pull', recorder.remote_video, str(output / 'capture/demo.mp4'))
            (output / 'recording.json').write_text(json.dumps({'elapsed_seconds': round(elapsed, 2),
                'fake_ai': True, 'paid_calls': 0, 'journey_passed': True,
                'source_photo': 'original synthetic fixture; camera/picker not exercised'}, indent=2))
            print('Demo journey passed; recording and synthetic captures saved under ignored .android/')
        finally:
            if recorder is not None:
                recorder.cleanup()
            if process.poll() is None:
                process.terminate()
            run('shell', 'run-as', PACKAGE, 'rm', '-f', 'files/portfolio-input.json')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never serialize requests or entered credentials in an error message.
        print('Recording failed: ' + type(error).__name__ + '; inspect sanitized local evidence')
        raise SystemExit(1)
