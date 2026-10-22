"""Read-only database transfer/restore evidence; no credentials or row values printed."""
import argparse, hashlib, json, sys
from pathlib import Path
from psycopg import sql
from . import Database, USAGE_LOCK

def fingerprint(db):
    result={}
    names=[r['table_name'] for r in db.execute("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name")]
    for name in names:
        rows=db.execute(sql.SQL('SELECT * FROM public.{}').format(sql.Identifier(name))).fetchall()
        canonical=sorted(json.dumps(row,default=str,sort_keys=True,separators=(',',':')) for row in rows)
        result[name]={'rows':len(rows),'sha256':hashlib.sha256('\n'.join(canonical).encode()).hexdigest()}
    for query,key in [
        ("SELECT table_name,column_name,data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema='public' ORDER BY table_name,ordinal_position",'columns'),
        ("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename,indexname",'indexes'),
        ("SELECT c.relname,p.conname,pg_get_constraintdef(p.oid) AS definition FROM pg_constraint p JOIN pg_class c ON c.oid=p.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' ORDER BY c.relname,p.conname",'constraints')]:
        result[key]={'sha256':hashlib.sha256(json.dumps(db.execute(query).fetchall(),default=str,sort_keys=True).encode()).hexdigest()}
    for row in db.execute("SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename"):
        state=db.execute(sql.SQL('SELECT last_value,is_called FROM public.{}').format(sql.Identifier(row['sequencename']))).fetchone()
        result['sequence:'+row['sequencename']]={'sha256':hashlib.sha256(json.dumps(state,sort_keys=True).encode()).hexdigest()}
    return result

def snapshot(database=None):
    with (database or Database()).connection() as db:
        db.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY')
        db.execute('SET LOCAL statement_timeout=60000')
        db.execute('SELECT pg_advisory_xact_lock(8426500003)')
        db.execute('SELECT pg_advisory_xact_lock(%s)',(USAGE_LOCK,))
        return {'tables':fingerprint(db)}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--compare',type=Path,help='Fingerprint JSON from a frozen source database')
    args=parser.parse_args()
    actual=snapshot()
    if args.compare:
        if actual!=json.loads(args.compare.read_text(encoding='utf-8')):
            raise RuntimeError('Database contents/schema/sequences differ from the source')
        print(json.dumps({'all_tables_schema_constraints_indexes_sequences_equal':True}))
    else:
        print(json.dumps(actual,sort_keys=True))

if __name__=='__main__':
    try: main()
    except Exception as error:
        print('Database evidence check failed ('+type(error).__name__+')',file=sys.stderr)
        sys.exit(1)
