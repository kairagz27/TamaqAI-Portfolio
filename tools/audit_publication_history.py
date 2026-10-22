"""Read-only Git history audit. Emits locations/types, never matched values.

Includes every locally reachable ref, unique blob, binary and commit metadata.
Findings are review candidates; synthetic fixtures are not live credentials.
This is a pattern/provenance audit, not a guarantee that every secret is detectable.
"""
import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SECRET = re.compile(rb'\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{30,}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)')
EMAIL = re.compile(rb'\b[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\b')
PRIVATE_KEY = re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----')
MEDIA = {'.png', '.jpg', '.jpeg', '.webp', '.gif', '.mp4'}
RESTRICTED = {'docs/design/dark/reference-forest.png', 'docs/design/dark/reference-vr.png', 'docs/design/figma/today.jpg'}


def git(*args, data=None):
    return subprocess.check_output(['git', *args], cwd=ROOT, input=data)


def forbidden_path(name):
    path = Path(name)
    example = path.name.endswith('.example')
    return (any(p in {'.demo', '.android', '.venv', '.gradle', '.gradle-user', 'private', 'backups', 'backup', 'uploads', 'build'} for p in path.parts)
        or path.suffix.lower() in {'.keystore', '.jks', '.db', '.sqlite', '.sqlite3', '.dump', '.backup', '.apk', '.aab', '.pem', '.p12', '.pfx', '.key', '.log'}
        or (not example and (path.name.startswith('.env') or path.suffix == '.env'))
        or path.name in {'local.properties', 'google-services.json'})


def kinds(data, name, live):
    result = []
    if SECRET.search(data): result.append('credential-pattern')
    if PRIVATE_KEY.search(data): result.append('private-key-material')
    if any(value in data for value in live): result.append('matches-current-private-configuration')
    if b'\0' not in data[:8192]:
        for domain in set(EMAIL.findall(data)):
            domain = domain.lower()
            if domain not in {b'example.com', b'example.org', b'example.net', b'example.test', b'tamaq.local', b'example.invalid'} and not domain.endswith((b'.example', b'.test', b'.invalid', b'.local', b'.example.com', b'.example.org', b'.example.net', b'noreply.github.com')):
                result.append('non-example-email-review'); break
        if re.search(rb'(?:postgres(?:ql)?|smtps?)://[^\s:/]+:[^\s@]+@', data):
            result.append('connection-string-review')
        if re.search(rb'(?i)(?:password|secret|invite(?:_token)?|api_key)\s*[=:]\s*[\x22\x27][^\x22\x27\r\n]{12,}', data):
            result.append('literal-credential-review')
    if forbidden_path(name): result.append('forbidden-file-type')
    if name in RESTRICTED: result.append('unconfirmed-reference-image-license')
    return sorted(set(result))


def live_values():
    values = set()
    for path in [*(ROOT/'backend').glob('.env*'), *(ROOT/'.demo').glob('*.env')]:
        if path.name.endswith('.example'): continue
        for line in path.read_text(encoding='utf-8').splitlines():
            if '=' not in line or line.lstrip().startswith('#'): continue
            key,value=line.split('=',1); value=value.strip().strip('\"\'')
            if re.search('KEY|SECRET|PASSWORD|TOKEN|INVITE',key) and len(value)>=12:
                values.add(value.encode())
                try:
                    parsed=json.loads(value)
                    if isinstance(parsed,list): values.update(v.encode() for v in parsed if isinstance(v,str) and len(v)>=12)
                except ValueError: pass
    user = ROOT / '.demo/user.json'
    if user.is_file():
        document = json.loads(user.read_text(encoding='utf-8'))
        for key in ('password', 'invitation'):
            value = document.get(key, '')
            if isinstance(value, str) and len(value) >= 12:
                values.add(value.encode())
    return values


def audit():
    refs=git('for-each-ref','--format=%(refname)').decode().splitlines()
    commits=git('rev-list','--all').decode().splitlines()
    # Names are metadata only; multiple paths can point at the same blob.
    objects={}
    for line in git('rev-list','--objects','--all').decode('utf-8',errors='replace').splitlines():
        sha,_,name=line.partition(' ')
        if name: objects[sha]=name
    current={line.split('\t',1)[0].split()[2]:line.split('\t',1)[1]
             for line in git('ls-tree','-r','HEAD').decode().splitlines()}
    live=live_values();findings=[];media=[];blobs=0;total=0;archives=0
    process=subprocess.Popen(['git','cat-file','--batch'],cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE)
    try:
        for sha,name in objects.items():
            process.stdin.write((sha+'\n').encode());process.stdin.flush()
            header=process.stdout.readline().split()
            size=int(header[2]);data=process.stdout.read(size);process.stdout.read(1)
            if header[1]!=b'blob':continue
            blobs+=1;total+=size
            result=kinds(data,name,live)
            if zipfile.is_zipfile(io.BytesIO(data)):
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    for member in archive.infolist():
                        if member.file_size>16_000_000:continue
                        nested=archive.read(member);archives+=1
                        for kind in kinds(nested,'archive-member',live):
                            if kind in {'credential-pattern','private-key-material','matches-current-private-configuration'}:result.append('archive-'+kind)
            if result:
                findings.append({'blob':sha,'path':name,'types':sorted(set(result)),'in_current_HEAD':sha in current,
                    'fixture_or_operator_document':name.startswith(('backend/tests/','app/src/androidTest/','app/src/test/','docs/','tools/'))})
            if Path(name).suffix.lower() in MEDIA:media.append({'blob':sha,'path':name,'bytes':size,'in_current_HEAD':sha in current})
    finally:
        process.stdin.close();process.wait(timeout=10)
    metadata=git('log','--all','--format=%ae%n%ce').splitlines()
    addresses=set(a.lower() for a in metadata if a and not a.lower().endswith((b'noreply.github.com',b'@example.com')))
    return {'refs':refs,'reachable_commits':len(commits),'unique_blobs_scanned':blobs,'bytes_scanned':total,
        'archive_members_scanned':archives,'findings':findings,'media_inventory':media,
        'non_noreply_commit_identity_count':len(addresses),'matched_values_recorded':False,
        'scope_limitations':['Only locally reachable refs; GitHub deleted refs/caches/forks are not available locally',
            'Pattern matches require classification; this does not OCR every video frame or prove absence of arbitrary secrets',
            'Media provenance requires visual review and license evidence'],
        'current_private_secret_matches':sum('matches-current-private-configuration' in f['types'] for f in findings)}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',required=True);args=p.parse_args()
    out=(ROOT/args.out).resolve()
    if not out.is_relative_to(ROOT/'.android'):p.error('Audit evidence must stay in ignored .android/')
    result=audit();out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ('reachable_commits','unique_blobs_scanned','bytes_scanned','archive_members_scanned','non_noreply_commit_identity_count','current_private_secret_matches')}))
    print(json.dumps({'finding_locations':len(result['findings']),'media_objects':len(result['media_inventory']),'matched_values_recorded':False}))


if __name__=='__main__':main()
