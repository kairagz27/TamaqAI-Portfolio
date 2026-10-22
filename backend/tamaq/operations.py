"""Read-only pilot monitor. No provider creation, tokens, user rows or budget updates."""
import argparse
import json
import os
import shutil
import sys
import urllib.request
from .db import Database


def status():
    with Database().connection() as db:
        db.execute('SET TRANSACTION READ ONLY')
        state = db.execute('SELECT * FROM budget WHERE id=1').fetchone()
        totals = db.execute('SELECT COALESCE(SUM(charged),0) AS charged, COALESCE(SUM(reserved),0) AS reserved, COUNT(*) AS requests FROM requests').fetchone()
    charged = int(totals['charged'] + state['deleted_spend'])
    reserved = int(totals['reserved'])
    caps = [int(state['pilot_cap']), int(state['global_cap'])]
    if state['verification_cap'] is not None:
        caps.append(int(state['verification_cap']))
    free = shutil.disk_usage(os.environ.get('TAMAQ_FILES_ROOT','/')).free
    alerts = []
    if charged + reserved >= min(caps) * .8: alerts.append('budget_80_percent')
    if reserved: alerts.append('reservations_require_review')
    if state['halted']: alerts.append('analysis_halted')
    if free < 1024**3: alerts.append('disk_below_1_gib')
    return {'charged_micro_usd': charged, 'reserved_micro_usd': reserved,
            'remaining_after_reservations_micro_usd': max(0,min(caps)-charged-reserved),
            'requests': int(totals['requests']), 'disk_free_bytes': free, 'alerts': alerts}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--logs',action='store_true',help='Aggregate response-only JSON from stdin')
    args=parser.parse_args()
    try:
        if args.logs:
            requests=errors=incomplete=0
            for line in sys.stdin:
                try: record=json.loads(line)
                except ValueError: continue
                if not isinstance(record,dict) or set(record)!={'request_id','method','route','status','complete','duration_ms'}: continue
                if type(record['status']) is not int: continue
                requests+=1; errors+=record['status']>=500; incomplete+=record['complete'] is False
            print(json.dumps({'requests':requests,'http_5xx':errors,'incomplete':incomplete}))
            return 1 if errors or incomplete else 0
        report=status()
        try:
            with urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('PORT','8000')+'/ready',timeout=5) as response:
                report['ready']=response.status==200
        except Exception: report['ready']=False; report['alerts'].append('backend_unavailable')
        print(json.dumps(report))
        return 1 if report['alerts'] else 0
    except Exception:
        print(json.dumps({'ready':False,'alerts':['monitor_failed']}))
        return 2

if __name__=='__main__': raise SystemExit(main())
