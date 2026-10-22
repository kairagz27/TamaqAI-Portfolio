"""Real PostgreSQL transactions in a disposable database, never the pilot ledger."""
import io, os, unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
from uuid import uuid4
import psycopg
from psycopg import sql
from psycopg.conninfo import make_conninfo
from fastapi.testclient import TestClient
from PIL import Image
from tamaq.db import Database
from tamaq.auth.service import Auth, digest
from tamaq.auth.email import MemoryDelivery
from tamaq.usage.postgres import PostgresLedger
from tamaq.ledger import Ledger
from tamaq.dev_fake import FakeProvider
from tamaq.app import create_app

PASSWORD='test-only-long-password'
INVITE='account-contract-test-invite'

@unittest.skipUnless(os.environ.get('TAMAQ_DATABASE_URL'),'Explicit local PostgreSQL configuration required')
class AccountsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=os.environ['TAMAQ_DATABASE_URL']; cls.name='tamaq_test_'+uuid4().hex
        with psycopg.connect(cls.source,autocommit=True) as db: db.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(cls.name)))
        cls.database=Database(cls.source.rsplit('/',1)[0]+'/'+cls.name); cls.database.migrate()
    @classmethod
    def tearDownClass(cls):
        with psycopg.connect(cls.source,autocommit=True) as db: db.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(cls.name)))
    def setUp(self):
        with self.database.connection() as db:
            db.execute('TRUNCATE guests,requests,registration,budget,invitations,users,auth_sessions,refresh_tokens,email_actions,auth_attempts,import_manifest RESTART IDENTITY CASCADE')
        self.env=patch.dict(os.environ,{'TAMAQ_ENV':'development','TAMAQ_PROVIDER':'fake','TAMAQ_VERIFICATION_BUDGET_USD':'1'})
        self.env.start(); self.addCleanup(self.env.stop)
        self.mail=MemoryDelivery(); self.auth=Auth(self.database,self.mail,'local-test-signing-key-with-at-least-32-bytes')
        self.ledger=PostgresLedger(self.database); self.provider=FakeProvider(); self.calls=0
        original=self.provider.analyze
        def counted(photo): self.calls+=1; return original(photo)
        self.provider.analyze=counted
        self.client=TestClient(create_app(self.ledger,self.provider,INVITE,auth=self.auth))
    def headers(self,tokens): return {'Authorization':'Bearer '+tokens['access_token']}
    def account(self,email='first@example.com',verified=True):
        self.assertEqual(200,self.client.post('/v1/auth/register',json={'email':email,'password':PASSWORD,'name':'Test'}).status_code)
        if verified: self.assertEqual(200,self.client.post('/v1/auth/verify-email',json={'token':self.mail.messages[-1][2]}).status_code)
        response=self.client.post('/v1/auth/login',json={'email':email,'password':PASSWORD}); self.assertEqual(200,response.status_code)
        return response.json()
    def guest(self): return self.client.post('/v1/guests',headers={'X-Pilot-Invite':INVITE}).json()['token']
    def analyze(self,token,key=None):
        photo=io.BytesIO(); Image.new('RGB',(32,32),'green').save(photo,'JPEG')
        return self.client.post('/v1/analyses',content=photo.getvalue(),headers={'Authorization':'Bearer '+token,'Content-Type':'image/jpeg','Idempotency-Key':key or str(uuid4())})
    def test_registration_hash_generic_validation_and_no_budget(self):
        account=self.account(); self.assertEqual(403,self.analyze(account['access_token']).status_code)
        with self.database.connection() as db:
            row=db.execute('SELECT password_hash FROM users').fetchone(); self.assertTrue(row['password_hash'].startswith('$argon2id$'))
            self.assertNotIn(PASSWORD,row['password_hash']); self.assertEqual(0,db.execute('SELECT COUNT(*) AS n FROM requests').fetchone()['n'])
        response=self.client.post('/v1/auth/register',json={'email':'bad','password':'private-short-value'})
        self.assertEqual(422,response.status_code); self.assertNotIn('private-short-value',response.text)
    def test_registration_422_identifies_fields_without_disclosing_values(self):
        for email in ('fixture@example.com\u200b', 'first..last@example.com', 'fixture@example.test'):
            response=self.client.post('/v1/auth/register',json={'email':email,'name':'Test','password':PASSWORD})
            self.assertEqual(422,response.status_code)
            self.assertEqual({'code':'invalid_input','fields':{'email':'invalid'}},response.json()['error'])
            self.assertNotIn(PASSWORD,response.text);self.assertNotIn(email,response.text)
        response=self.client.post('/v1/auth/register',json={'email':'fixture@example.com','password':'short'})
        self.assertEqual({'name':'required','password':'too_short'},response.json()['error']['fields'])
        with self.database.connection() as db:
            self.assertEqual(0,db.execute('SELECT COUNT(*) AS n FROM users').fetchone()['n'])
    def test_registration_unicode_json_contract_and_existing_email(self):
        body={'email':'fixture+tag@example.com','name':'Әлия Ғани Қанат','password':'密码😀'*5}
        response=self.client.post('/v1/auth/register',json=body);self.assertEqual(200,response.status_code)
        login={key:body[key] for key in ('email','password')}
        self.assertEqual(200,self.client.post('/v1/auth/login',json=login).status_code)
        self.assertEqual(200,self.client.post('/v1/auth/register',json={**body,'password':PASSWORD}).status_code)
        self.assertEqual(401,self.client.post('/v1/auth/login',json={**login,'password':PASSWORD}).status_code)
        with self.database.connection() as db:
            self.assertEqual(1,db.execute('SELECT COUNT(*) AS n FROM users').fetchone()['n'])
        self.assertEqual(0,self.calls)
    def test_email_delivery_failure_commits_account_and_credentials_still_work(self):
        body={'email':'delivery-fixture@example.com','name':'Test','password':PASSWORD}
        with patch.object(self.mail,'send',side_effect=RuntimeError('test delivery unavailable')):
            response=self.client.post('/v1/auth/register',json=body)
        self.assertEqual(503,response.status_code)
        self.assertEqual({'error':{'code':'email_delivery_unavailable'}},response.json())
        with self.database.connection() as db:
            self.assertEqual(1,db.execute('SELECT COUNT(*) AS n FROM users').fetchone()['n'])
        self.assertEqual(200,self.client.post('/v1/auth/login',json={key:body[key] for key in ('email','password')}).status_code)
        self.assertEqual(0,self.calls)
    def test_rotation_replay_revokes_family(self):
        old=self.account(); new=self.client.post('/v1/auth/refresh',json={'refresh_token':old['refresh_token']}).json()
        self.assertNotEqual(old['refresh_token'],new['refresh_token'])
        self.assertEqual(401,self.client.post('/v1/auth/refresh',json={'refresh_token':old['refresh_token']}).status_code)
        self.assertEqual(401,self.client.get('/v1/users/me',headers=self.headers(new)).status_code)
        with self.database.connection() as db:
            self.assertIsNone(db.execute('SELECT token_hash FROM refresh_tokens WHERE token_hash=%s',(old['refresh_token'],)).fetchone())
            self.assertIsNotNone(db.execute('SELECT token_hash FROM refresh_tokens WHERE token_hash=%s',(digest(old['refresh_token']),)).fetchone())
    def test_parallel_refresh_reuse_is_detected(self):
        old=self.account()
        with ThreadPoolExecutor(2) as pool: codes=list(pool.map(lambda _:self.client.post('/v1/auth/refresh',json={'refresh_token':old['refresh_token']}).status_code,range(2)))
        self.assertEqual([200,401],sorted(codes))
    def test_logout_revokes_access_and_refresh(self):
        account=self.account(); self.assertEqual(200,self.client.post('/v1/auth/logout',headers=self.headers(account)).status_code)
        self.assertEqual(401,self.client.get('/v1/users/me',headers=self.headers(account)).status_code)
        self.assertEqual(401,self.client.post('/v1/auth/refresh',json={'refresh_token':account['refresh_token']}).status_code)
    def test_reset_single_use_expires_and_revokes_sessions(self):
        account=self.account(); self.client.post('/v1/auth/forgot-password',json={'email':'first@example.com'})
        token=self.mail.messages[-1][2]
        self.assertEqual(200,self.client.post('/v1/auth/reset-password',json={'token':token,'password':PASSWORD+'-new'}).status_code)
        self.assertEqual(400,self.client.post('/v1/auth/reset-password',json={'token':token,'password':PASSWORD+'-new'}).status_code)
        self.assertEqual(401,self.client.get('/v1/users/me',headers=self.headers(account)).status_code)
        self.assertEqual(401,self.client.post('/v1/auth/login',json={'email':'first@example.com','password':PASSWORD}).status_code)
        self.assertEqual(200,self.client.post('/v1/auth/login',json={'email':'first@example.com','password':PASSWORD+'-new'}).status_code)
    def test_verify_expiration_and_single_use(self):
        account=self.account(verified=False); token=self.mail.messages[-1][2]
        with self.database.connection() as db: db.execute('UPDATE email_actions SET expires_at=0')
        self.assertEqual(400,self.client.post('/v1/auth/verify-email',json={'token':token}).status_code)
        self.client.post('/v1/auth/resend-verification',headers=self.headers(account)); token=self.mail.messages[-1][2]
        self.assertEqual(200,self.client.post('/v1/auth/verify-email',json={'token':token}).status_code)
        self.assertEqual(400,self.client.post('/v1/auth/verify-email',json={'token':token}).status_code)
    def test_link_proves_both_sessions_preserves_cache_quota_and_budget(self):
        token=self.guest(); key=str(uuid4()); result=self.analyze(token,key); self.assertEqual(200,result.status_code)
        before=self.ledger.report(); account=self.account(); operation=str(uuid4())
        self.assertEqual(401,self.client.post('/v1/auth/link-guest',headers=self.headers(account),json={'guest_token':'x'*40,'operation':operation}).status_code)
        for _ in range(2): self.assertEqual(200,self.client.post('/v1/auth/link-guest',headers=self.headers(account),json={'guest_token':token,'operation':operation}).status_code)
        self.assertEqual(result.json(),self.analyze(account['access_token'],key).json()); self.assertEqual(1,self.calls)
        self.assertEqual(before,self.ledger.report()); self.assertEqual(401,self.analyze(token,key).status_code)
        self.assertEqual(409,self.client.post('/v1/guests',headers={'X-Pilot-Invite':INVITE}).status_code)
    def test_account_isolation_and_body_uid_is_not_identity(self):
        first=self.account(); second=self.account('second@example.com')
        token=self.guest(); operation=str(uuid4()); self.client.post('/v1/auth/link-guest',headers=self.headers(first),json={'guest_token':token,'operation':operation})
        key=str(uuid4()); self.analyze(first['access_token'],key)
        self.assertEqual(403,self.client.get('/v1/analyses/'+key+'/receipt',headers=self.headers(second)).status_code)
        self.assertEqual(422,self.client.patch('/v1/users/me',headers=self.headers(second),json={'name':'Intruder','uid':first['user_id']}).status_code)
        self.assertEqual('Test',self.client.get('/v1/users/me',headers=self.headers(first)).json()['name'])
    def test_limits_and_generic_forgot(self):
        for _ in range(5): self.assertEqual(401,self.client.post('/v1/auth/login',json={'email':'none@example.com','password':PASSWORD}).status_code)
        self.assertEqual(429,self.client.post('/v1/auth/login',json={'email':'none@example.com','password':PASSWORD}).status_code)
        self.assertEqual({'accepted':True},self.client.post('/v1/auth/forgot-password',json={'email':'none@example.com'}).json())
    def test_deletion_reauth_finance_and_claim_survive(self):
        account=self.account(); token=self.guest(); self.client.post('/v1/auth/link-guest',headers=self.headers(account),json={'guest_token':token,'operation':str(uuid4())})
        self.analyze(account['access_token']); before=self.ledger.report()
        self.assertEqual(401,self.client.request('DELETE','/v1/users/me',headers=self.headers(account),json={'password':'wrong'}).status_code)
        self.assertEqual(200,self.client.request('DELETE','/v1/users/me',headers=self.headers(account),json={'password':PASSWORD}).status_code)
        self.assertEqual(before,self.ledger.report()); self.assertEqual(401,self.client.get('/v1/users/me',headers=self.headers(account)).status_code)
        self.assertEqual(409,self.client.post('/v1/guests',headers={'X-Pilot-Invite':INVITE}).status_code)
    def test_concurrent_reservations_share_one_cap_across_instances(self):
        owner=self.ledger.authenticate(self.guest()); ledger2=PostgresLedger(self.database)
        def reserve(i):
            try: (self.ledger if i%2 else ledger2).reserve(owner+str(i),str(uuid4()),'digest'); return True
            except Exception as e:
                from tamaq.contracts import AnalysisError
                if not isinstance(e,AnalysisError): raise
                return False
        # Trusted operator principals don't need guest rows but requests have invitation FKs.
        with self.database.connection() as db:
            for i in range(8): db.execute('INSERT INTO invitations(principal) VALUES(%s)',(owner+str(i),))
        with ThreadPoolExecutor(8) as pool: accepted=list(pool.map(reserve,range(8)))
        self.assertEqual(3,sum(accepted)); self.assertEqual(900000,self.ledger.report()['reserved_estimate_micro_usd'])
