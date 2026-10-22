"""Explicit offline import. Never instantiates/prunes the source ledger.

Use a consistent SQLite backup taken after stopping writes for the final cutover.
An empty PostgreSQL usage store is required; repeated identical import is a no-op.
"""
import argparse
import hashlib
import json
import sqlite3
import time
from pathlib import Path
from . import Database, USAGE_LOCK, MIGRATION_LOCK

TABLES={
 'guests':['token_hash','created','principal','expires_at'],
 'requests':['guest','key','digest','created','status','reserved','charged','body','usage'],
 'registration':['created','principal'],
 'budget':['id','deleted_spend','halted','global_cap','pilot_cap','verification_cap','verification_baseline']}

def fingerprint(rows):
    return hashlib.sha256(json.dumps(sorted(rows,key=lambda x:json.dumps(x,ensure_ascii=True)),ensure_ascii=True,separators=(',',':')).encode()).hexdigest()

def source_rows(path):
    with sqlite3.connect(Path(path).resolve().as_uri()+'?mode=ro',uri=True) as db:
        return {t:[list(r) for r in db.execute('SELECT '+','.join(columns)+' FROM '+t)] for t,columns in TABLES.items()}

def verify(db, source):
    proof={}
    for table,columns in TABLES.items():
        rows=[list(r.values()) for r in db.execute('SELECT '+','.join(columns)+' FROM '+table).fetchall()]
        equal=fingerprint(rows)==fingerprint(source[table])
        if not equal: raise RuntimeError('Migration differs in '+table)
        proof[table]={'rows':len(rows),'exact_match':equal}
    proof['charged_micro_usd']=sum(r[6] for r in source['requests'])+source['budget'][0][1]
    proof['reserved_micro_usd']=sum(r[5] for r in source['requests'])
    proof['verification_cap_micro_usd']=source['budget'][0][5]
    proof['pilot_cap_micro_usd']=source['budget'][0][4]
    return proof

def migrate(path,database,verify_only=False):
    source=source_rows(path)
    if len(source['budget'])!=1: raise RuntimeError('Source budget is missing')
    source_digest=fingerprint([[t,fingerprint(rows)] for t,rows in source.items()])
    with database.connection() as db:
        db.execute('SELECT pg_advisory_xact_lock(%s)',(MIGRATION_LOCK,))
        db.execute('SELECT pg_advisory_xact_lock(%s)',(USAGE_LOCK,))
        prior=db.execute('SELECT source_digest FROM import_manifest WHERE id=1').fetchone()
        if verify_only:
            if not prior: raise RuntimeError('Target has no import manifest')
            return verify(db,source)
        if prior:
            if not prior or prior['source_digest']!=source_digest: raise RuntimeError('Import source differs; refusing overwrite')
            return verify(db,source)
        if any(db.execute('SELECT COUNT(*) AS n FROM '+t).fetchone()['n'] for t in TABLES) or db.execute('SELECT COUNT(*) AS n FROM users').fetchone()['n']:
            raise RuntimeError('Target is populated; refusing reset or merge')
        principals={r[2] for r in source['guests']}|{r[0] for r in source['requests']}|{r[1] for r in source['registration'] if r[1]}
        for principal in principals: db.execute('INSERT INTO invitations(principal) VALUES(%s) ON CONFLICT DO NOTHING',(principal,))
        for table,columns in TABLES.items():
            for row in source[table]:
                db.execute('INSERT INTO '+table+'('+','.join(columns)+') VALUES('+','.join(['%s']*len(columns))+')',row)
        db.execute('INSERT INTO import_manifest VALUES(1,%s,%s)',(source_digest,int(time.time())))
        return verify(db,source)

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('backup'); parser.add_argument('--verify-only',action='store_true')
    args=parser.parse_args(); database=Database(); database.require_current()
    print(json.dumps(migrate(args.backup,database,args.verify_only),indent=2))
