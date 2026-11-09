"""Record only an isolated emulator's guarded, offline standalone demo.

No server configuration, secrets or paid providers are read. Output remains local.
The device must be an emulator; the test resets only its synthetic demo package.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time
from record_portfolio_demo import RemoteRecorder

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.tamaqai.standalone'
REMOTE = '/sdcard/Android/data/' + PACKAGE + '/files/standalone'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('A separate isolated emulator is required; physical devices are refused')
    if not args.adb:
        parser.error('Specify the installed adb executable')
    output = (ROOT / args.out).resolve()
    if not output.is_relative_to(ROOT / '.android'):
        parser.error('Output must be under ignored .android/')
    if output.exists() and any(output.iterdir()):
        parser.error('Use an empty output directory; existing evidence is preserved')
    output.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb, '-s', args.serial]

    def run(*command, **kwargs):
        return subprocess.run(prefix + list(command), check=True, capture_output=True, timeout=15, **kwargs)

    run('shell', 'run-as', PACKAGE, 'pwd')
    run('shell', 'rm', '-f', REMOTE + '/phase', REMOTE + '/go')
    recorder = None
    process = None
    with (output / 'instrumentation.log').open('wb') as log:
        process = subprocess.Popen(prefix + ['shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'com.tamaqai.ui.StandaloneDemoUiTest#portfolioCurrentDesignVideo',
            '-e', 'standaloneVideo', 'true', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                phase = subprocess.run(prefix + ['shell', 'cat', REMOTE + '/phase'], capture_output=True, timeout=5).stdout.strip()
                if phase == b'ready':
                    break
                if process.poll() is not None:
                    raise RuntimeError('Journey stopped before recording readiness')
                time.sleep(.2)
            else:
                raise TimeoutError('Journey readiness timeout')
            recorder = RemoteRecorder(prefix)
            recorder.start()
            started = time.monotonic()
            time.sleep(.5)
            run('shell', 'touch', REMOTE + '/go')
            while time.monotonic() - started < 80:
                phase = subprocess.run(prefix + ['shell', 'cat', REMOTE + '/phase'], capture_output=True, timeout=5).stdout.strip()
                if phase == b'video-complete':
                    break
                if process.poll() is not None:
                    raise RuntimeError('Journey exited before final recording frame')
                time.sleep(.15)
            else:
                raise TimeoutError('Journey exceeded bounded recording window')
            time.sleep(.5)
            recorder.stop(require_active=True)
            process.wait(timeout=15)
            log.flush()
            if 'OK (1 test)' not in (output / 'instrumentation.log').read_text(errors='replace'):
                raise RuntimeError('Journey test failed; recording not accepted')
            run('pull', recorder.remote_video, str(output / 'tamaqai-current-standalone.mp4'))
            captures = output / 'captures'
            captures.mkdir()
            names = ['welcome', 'home-light', 'editor-light', 'editor-portion-and-component',
                     'confirmed-light', 'macros-light', 'history-light', 'profile-light',
                     'current-drawer-light', 'current-drawer-dark', 'current-home-dark',
                     'current-weight-curve-dark', 'current-home-light']
            for name in names:
                run('pull', REMOTE + '/' + name + '.png', str(captures / (name + '.png')))
            run('pull', REMOTE + '/current-portfolio-video.json', str(output / 'journey.json'))
            (output / 'recording.json').write_text(json.dumps({
                'duration_upper_bound_seconds': round(time.monotonic() - started, 2),
                'package': PACKAGE, 'isolated_emulator': True, 'prepared_example_only': True,
                'real_photo_analysis_demonstrated': False, 'paid_calls': 0,
                'journey_passed': True, 'approved_original_design': True,
                'capture_has_audio': False}, indent=2) + '\n', encoding='utf-8')
            print('Current standalone recording and synthetic captures verified locally: ' + str(output))
        finally:
            if recorder is not None:
                recorder.cleanup()
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=10)


if __name__ == '__main__':
    main()
