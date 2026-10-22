import asyncio, json, os, unittest
from types import SimpleNamespace
from unittest.mock import patch
from fastapi.testclient import TestClient
import test_backend as fixture
from tamaq.observability import RequestMetadata
from tamaq.operations import status


class PilotOperationsTest(unittest.TestCase):
    setUp=fixture.BackendTest.setUp
    tearDown=fixture.BackendTest.tearDown

    def test_request_metadata_excludes_all_private_input(self):
        with self.assertLogs('tamaq.requests',level='INFO') as records:
            response=self.client.post('/v1/auth/not-a-route?email=private-value',content='password-private-value',
                                      headers={'Authorization':'Bearer private-token','X-Request-ID':'private-id'})
        record=json.loads(records.records[-1].message)
        self.assertEqual({'request_id','method','route','status','complete','duration_ms'},set(record))
        self.assertNotIn('private',json.dumps(record)); self.assertEqual('unmatched',record['route'])
        self.assertEqual(record['request_id'],response.headers['X-Request-ID'])

    def test_ready_is_minimal_and_does_not_call_provider(self):
        self.assertEqual({'status':'ready'},self.client.get('/ready').json())
        self.assertEqual(0,self.calls)
        with patch.object(self.ledger,'connection',side_effect=RuntimeError('secret-database-value')):
            response=self.client.get('/ready')
        self.assertEqual(503,response.status_code)
        self.assertEqual({'status':'unavailable'},response.json())

    def test_missing_avatar_volume_fails_readiness(self):
        with patch.dict(os.environ,{'TAMAQ_FILES_ROOT':os.path.join(self.directory.name,'missing')}):
            self.assertEqual(503,self.client.get('/ready').status_code)

    def test_upload_read_deadline_and_safe_exception(self):
        async def exercise(error=None):
            messages=[]
            async def app(scope,receive,send):
                if error: raise error
                await receive()
            async def slow(): await asyncio.sleep(.1); return {'type':'http.request','body':b'private'}
            async def send(message): messages.append(message)
            with self.assertLogs('tamaq.requests') as records:
                await RequestMetadata(app,body_timeout=.01)({'type':'http','method':'POST'},slow,send)
            self.assertNotIn('private',str([r.message for r in records.records]))
            return messages
        self.assertEqual(408,asyncio.run(exercise())[0]['status'])
        result=asyncio.run(exercise(RuntimeError('private-database-password')))
        self.assertEqual(503,result[0]['status']); self.assertNotIn(b'private',result[1]['body'])

    def test_monitor_read_only_no_budget_constructor(self):
        state={'deleted_spend':4,'pilot_cap':5000000,'global_cap':50000000,'verification_cap':1000000,'halted':False}
        totals={'charged':200,'reserved':0,'requests':3}
        with patch('tamaq.operations.Database') as database, patch('tamaq.operations.shutil.disk_usage',return_value=SimpleNamespace(free=2*1024**3)):
            db=database.return_value.connection.return_value.__enter__.return_value
            db.execute.return_value.fetchone.side_effect=[state,totals]
            report=status()
        self.assertEqual(204,report['charged_micro_usd']); self.assertEqual(999796,report['remaining_after_reservations_micro_usd'])
        self.assertEqual([],report['alerts'])
        queries=[call.args[0] for call in db.execute.call_args_list]
        self.assertEqual('SET TRANSACTION READ ONLY',queries[0])
        self.assertTrue(all(query.startswith(('SELECT','SET TRANSACTION READ ONLY')) for query in queries))

    def test_finished_upload_does_not_time_out_provider_or_disconnect_wait(self):
        async def exercise():
            messages=[]; received=0
            async def receive():
                nonlocal received
                received+=1
                return {'type':'http.request','body':b'', 'more_body':False} if received==1 else {'type':'http.disconnect'}
            async def send(message): messages.append(message)
            async def app(scope,receive,send):
                await receive()
                await asyncio.sleep(.02)  # Longer than the upload deadline.
                self.assertEqual('http.disconnect',(await receive())['type'])
                await send({'type':'http.response.start','status':200,'headers':[]})
                await send({'type':'http.response.body','body':b'{}'})
            await RequestMetadata(app,body_timeout=.01)({'type':'http','method':'POST'},receive,send)
            return messages
        self.assertEqual(200,asyncio.run(exercise())[0]['status'])
