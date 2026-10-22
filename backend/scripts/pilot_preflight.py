"""Check a private server env without printing any values or making API calls."""
import argparse, json
from pathlib import Path
from urllib.parse import urlsplit, unquote

def inspect(path):
    values={}
    if path.is_file():
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key,value=line.split('=',1); values[key.strip()]=value.strip().strip('\"\'')
    required=('POSTGRES_PASSWORD','TAMAQ_DATABASE_URL','TAMAQ_JWT_SECRET','TAMAQ_DOMAIN','TAMAQ_ACME_EMAIL',
              'TAMAQ_SMTP_HOST','TAMAQ_SMTP_USERNAME','TAMAQ_SMTP_PASSWORD','TAMAQ_EMAIL_FROM','OPENAI_API_KEY','OPENAI_MODEL','PILOT_INVITE_TOKENS')
    missing=[key for key in required if not values.get(key) or values[key]=='REPLACE' or 'REPLACE@' in values[key]]
    errors=[]
    if values.get('TAMAQ_ENV')!='production' or values.get('TAMAQ_PROVIDER')!='openai': errors.append('production_openai_required')
    if len(values.get('TAMAQ_JWT_SECRET','').encode())<32: errors.append('jwt_secret_too_short')
    if values.get('TAMAQ_SMTP_STARTTLS')!='true': errors.append('smtp_starttls_required')
    if values.get('TAMAQ_SMTP_HOST','').lower() in ('mailpit','localhost','127.0.0.1'): errors.append('external_smtp_required')
    try:
        database=urlsplit(values.get('TAMAQ_DATABASE_URL',''))
        if database.scheme not in ('postgresql','postgres') or database.hostname!='postgres' or database.username!='tamaq' or database.port!=5432 or database.path!='/tamaq' or unquote(database.password or '')!=values.get('POSTGRES_PASSWORD'):
            errors.append('postgres_url_and_password_must_match_compose')
    except ValueError: errors.append('invalid_postgres_url')
    domain=values.get('TAMAQ_DOMAIN','')
    origin=urlsplit('https://'+domain)
    if not domain or origin.hostname!=domain or '.' not in domain or domain.endswith(('.local','.localhost','.test')) or domain.replace('.','').isdigit(): errors.append('public_domain_required')
    try:
        invites=json.loads(values.get('PILOT_INVITE_TOKENS','[]'))
        if not isinstance(invites,list) or not 1<=len(invites)<=20 or len(set(invites))!=len(invites) or any(not isinstance(x,str) or len(x)<32 for x in invites): raise ValueError()
    except (ValueError,TypeError): errors.append('valid_existing_invitations_required')
    # A transfer must preserve the ledger itself; these are upper bounds only.
    for key,limit in [('TAMAQ_VERIFICATION_BUDGET_USD','1'),('TAMAQ_PILOT_BUDGET_USD','5'),('TAMAQ_GLOBAL_BUDGET_USD','50')]:
        if values.get(key)!=limit: errors.append(key+'_must_preserve_current_cap')
    return {'ready':not(missing or errors),'missing_fields':missing,'configuration_errors':errors}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--env-file',type=Path,required=True)
    args=parser.parse_args()
    try: report=inspect(args.env_file)
    except Exception: report={'ready':False,'configuration_errors':['private_configuration_unreadable']}
    print(json.dumps(report)); raise SystemExit(0 if report['ready'] else 1)
