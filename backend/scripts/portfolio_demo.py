"""Isolated, no-cost portfolio setup. Never read the owner's pilot env/ledger.

Commands: init | smoke | catalog | test. Generated values remain in ignored .demo/.
The demo Compose network is internal; SMTP is captured only by local Mailpit.
"""
from pathlib import Path
import argparse
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.request
import urllib.error
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / '.demo'
ENV = PRIVATE / 'backend.env'
USER = PRIVATE / 'user.json'
ORIGIN = 'http://127.0.0.1:8100'
MAILPIT_ORIGIN = 'http://127.0.0.1:8125'


def init():
    PRIVATE.mkdir(mode=0o700, exist_ok=True)
    if ENV.exists() != USER.exists():
        raise RuntimeError('Incomplete demo configuration; preserve existing files and review .demo/')
    if ENV.exists():
        print('Demo configuration preserved; no values displayed')
        return
    password = secrets.token_urlsafe(32)
    user = {'email': 'portfolio@example.com', 'password': secrets.token_urlsafe(32),
            'invitation': secrets.token_urlsafe(40), 'link_operation': str(uuid4())}
    ENV.write_text('\n'.join([
        'POSTGRES_DB=portfolio', 'POSTGRES_USER=portfolio', 'POSTGRES_PASSWORD=' + password,
        'TAMAQ_DATABASE_URL=postgresql://portfolio:' + password + '@postgres:5432/portfolio',
        'TAMAQ_JWT_SECRET=' + secrets.token_urlsafe(48),
        'PILOT_INVITE_TOKENS=' + json.dumps([user['invitation']]),
        'TAMAQ_ENV=development', 'TAMAQ_PROVIDER=fake', 'OPENAI_API_KEY=',
    ]) + '\n', encoding='utf-8')
    USER.write_text(json.dumps(user, indent=2) + '\n', encoding='utf-8')
    for path in (ENV, USER):
        path.chmod(0o600)
    print('Created private .demo/backend.env and .demo/user.json; values withheld')


def call(path, body=None, headers=None, base=ORIGIN, method=None):
    request = urllib.request.Request(base + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json', **(headers or {})}, method=method)
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def smoke():
    user = json.loads(USER.read_text())
    health = call('/health')
    assert health['demo'] is True and health['status'] == 'ok'
    credentials = {k: user[k] for k in ('email', 'password')}
    created = False
    previous_message_ids = set()
    try:
        login = call('/v1/auth/login', credentials)
    except urllib.error.HTTPError as error:
        if error.code != 401:
            raise
        previous_message_ids = {message['ID'] for message in
            call('/api/v1/messages', base=MAILPIT_ORIGIN).get('messages', [])}
        call('/v1/auth/register', {**credentials, 'name': 'Portfolio Demo'})
        created = True
        login = call('/v1/auth/login', credentials)
    authorization = {'Authorization': 'Bearer ' + login['access_token']}
    profile = call('/v1/users/me', headers=authorization)
    if not profile['email_verified']:
        if not created:
            # A persistent account can outlive its original Mailpit inbox/code.
            # Resend once; backend rate limits are preserved and never retried.
            previous_message_ids = {message['ID'] for message in
                call('/api/v1/messages', base=MAILPIT_ORIGIN).get('messages', [])}
            call('/v1/auth/resend-verification', {}, authorization)
        token = None
        for _ in range(20):
            messages = call('/api/v1/messages', base=MAILPIT_ORIGIN).get('messages', [])
            for message in messages:
                if message['ID'] in previous_message_ids:
                    continue
                if not any(item.get('Address') == user['email'] for item in message.get('To', [])):
                    continue
                mail = call('/api/v1/message/' + message['ID'], base=MAILPIT_ORIGIN)
                match = re.search(r'(?m)^([A-Za-z0-9_-]{32,128})\s*$', mail.get('Text', ''))
                if match:
                    token = match.group(1)
                    break
            if token:
                break
            time.sleep(.25)
        if not token:
            raise RuntimeError('No local verification message')
        call('/v1/auth/verify-email', {'token': token})
        profile = call('/v1/users/me', headers=authorization)
    assert profile['email_verified']
    if not profile['pilot_linked']:
        guest = call('/v1/guests', {}, {'X-Pilot-Invite': user['invitation']})
        call('/v1/auth/link-guest', {'guest_token': guest['token'],
            'operation': user['link_operation']}, authorization)
    questionnaire = call('/v1/nutrition/questionnaire', headers=authorization)
    if not questionnaire['document']['completed']:
        document = {**questionnaire['document'], 'completed': True, 'step': 3, 'goal': 'diary'}
        call('/v1/nutrition/questionnaire', {'version': questionnaire['version'], 'document': document},
             authorization, method='PUT')
    # Password and tokens are never stored in evidence or printed.
    call('/v1/auth/logout', {}, authorization)
    print(json.dumps({'fake_provider': True, 'registration_created_now': created, 'existing_account_reused': not created, 'local_mailpit_verified': True,
        'login_logout': True, 'explicit_guest_link': True, 'external_email': False, 'paid_calls': 0}))


def configure_host():
    for line in ENV.read_text().splitlines():
        key, value = line.split('=', 1)
        os.environ[key] = value
    os.environ['TAMAQ_DATABASE_URL'] = os.environ['TAMAQ_DATABASE_URL'].replace('@postgres:5432/', '@127.0.0.1:8101/')
    sys.path.insert(0, str(ROOT / 'backend'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['init', 'smoke', 'catalog', 'test'])
    args = parser.parse_args()
    if args.command == 'init':
        init()
        return
    if not ENV.is_file() or not USER.is_file():
        parser.error('Run portfolio_demo.py init first; real pilot configuration is never used')
    if args.command == 'smoke':
        smoke()
    elif args.command == 'catalog':
        subprocess.run(['docker', 'compose', '-f', str(ROOT/'compose.demo.yaml'), 'exec', '-T', 'backend',
            'python', '-m', 'tamaq.catalog', 'import', 'catalog_seed/openfoodfacts-kz.json', '--apply'], check=True)
    else:
        subprocess.run(['docker', 'compose', '-f', str(ROOT/'compose.demo.yaml'), '--profile', 'test',
            'up', '-d', '--wait', 'postgres-test'], check=True)
        configure_host()
        import unittest
        suite = unittest.defaultTestLoader.discover(str(ROOT / 'backend/tests'))
        result = unittest.TextTestRunner(verbosity=1).run(suite)
        raise SystemExit(not result.wasSuccessful())


if __name__ == '__main__':
    try:
        main()
    except urllib.error.HTTPError as error:
        try:
            code = json.loads(error.read()).get('error', {}).get('code', '')
            code = code if re.fullmatch('[a-z_]{1,64}', code) else 'request_failed'
        except (ValueError, TypeError):
            code = 'request_failed'
        print(f'Demo command failed: HTTP {error.code} ({code}); submitted values withheld', file=sys.stderr)
        raise SystemExit(1)
    except Exception as error:
        # Connection errors may carry URLs. Expose only the type, never values.
        print('Demo command failed: ' + type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
