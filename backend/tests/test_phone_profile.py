"""No external SMS/AI. Every test uses AccountsTest's disposable PostgreSQL database."""
import io
import os
import tempfile
from unittest.mock import patch
from uuid import UUID
from PIL import Image
import test_accounts
from test_accounts import PASSWORD
from tamaq.auth.phone import normalize_phone
from tamaq.auth.sms import MemorySMS, DisabledSMS, DevelopmentSMS
from tamaq.files import LocalAvatarStorage
from tamaq.contracts import AnalysisError

class PhoneProfileTest(test_accounts.AccountsTest):
    def setUp(self):
        super().setUp()
        with self.database.connection() as db: db.execute('TRUNCATE sms_challenges')
        self.sms=MemorySMS();self.auth.phone.delivery=self.sms
        self.files=tempfile.TemporaryDirectory();self.addCleanup(self.files.cleanup)
        self.auth.avatars=LocalAvatarStorage(self.files.name)
    def request_phone(self,phone='+1 (202) 555-0123',region='US'):
        response=self.client.post('/v1/auth/phone/request',json={'phone':phone,'region':region})
        self.assertEqual(200,response.status_code)
        self.assertNotIn('code',response.json())
        return response.json()['challenge_id']
    def phone_account(self):
        challenge=self.request_phone();code=self.sms.messages[-1][1]
        response=self.client.post('/v1/auth/phone/verify',json={'challenge_id':challenge,'code':code,'name':'Synthetic profile'})
        self.assertEqual(200,response.status_code);return response.json()
    def test_e164_normalization_and_invalid_country(self):
        self.assertEqual('+12025550123',normalize_phone('(202) 555-0123','US'))
        self.assertEqual('+77010000000',normalize_phone('8 (701) 000-00-00','KZ'))
        for number in ('12','+79999999999999999999','+12025550123 ext 456'):
            with self.assertRaises(AnalysisError):normalize_phone(number,'US')
    def test_disabled_sms_and_production_inbox_guard(self):
        self.auth.phone.delivery=DisabledSMS()
        self.assertFalse(self.client.get('/v1/auth/methods').json()['phone'])
        self.assertEqual(503,self.client.post('/v1/auth/phone/request',json={'phone':'+12025550123','region':'US'}).status_code)
        self.assertEqual([],self.sms.messages)
        with self.assertRaises(RuntimeError):DevelopmentSMS(self.files.name,'production')
    def test_development_inbox_and_failed_delivery_reservation(self):
        delivery=DevelopmentSMS(self.files.name,'development')
        self.auth.phone.delivery=delivery
        challenge=self.request_phone()
        import json
        from pathlib import Path
        message=json.loads((Path(self.files.name)/(challenge+'.json')).read_text())
        self.assertEqual('development-only',message['delivery'])
        result=self.client.post('/v1/auth/phone/verify',json={'challenge_id':challenge,'code':message['code']})
        self.assertEqual(200,result.status_code)
        with patch.object(delivery,'send',side_effect=RuntimeError('delivery failed')):
            result=self.client.post('/v1/auth/phone/request',json={'phone':'+12025550124','region':'US'})
        self.assertEqual(503,result.status_code)
        with self.database.connection() as db:
            self.assertEqual(2,db.execute('SELECT COUNT(*) AS n FROM sms_challenges WHERE consumed_at IS NOT NULL').fetchone()['n'])
        self.assertEqual(429,self.client.post('/v1/auth/phone/request',json={'phone':'+12025550124','region':'US'}).status_code)
    def test_otp_single_use_no_budget_and_refresh(self):
        challenge=self.request_phone();code=self.sms.messages[-1][1]
        with self.database.connection() as db:
            row=db.execute('SELECT * FROM sms_challenges').fetchone();self.assertNotEqual(code,row['code_hash'])
        body={'challenge_id':challenge,'code':code,'name':'Test'}
        first=self.client.post('/v1/auth/phone/verify',json=body);self.assertEqual(200,first.status_code)
        self.assertEqual(400,self.client.post('/v1/auth/phone/verify',json=body).status_code)
        tokens=first.json();profile=self.client.get('/v1/users/me',headers=self.headers(tokens)).json()
        self.assertTrue(profile['phone_verified']);self.assertFalse(profile['email_verified']);self.assertFalse(profile['pilot_linked'])
        self.assertEqual(403,self.analyze(tokens['access_token']).status_code)
        self.assertEqual(200,self.client.post('/v1/auth/refresh',json={'refresh_token':tokens['refresh_token']}).status_code)
        with self.database.connection() as db:self.assertEqual(0,db.execute('SELECT COUNT(*) AS n FROM requests').fetchone()['n'])
    def test_otp_attempts_commit_and_expiry(self):
        challenge=self.request_phone();actual=self.sms.messages[-1][1];bad='000000' if actual!='000000' else '111111'
        for _ in range(5):self.assertEqual(400,self.client.post('/v1/auth/phone/verify',json={'challenge_id':challenge,'code':bad}).status_code)
        with self.database.connection() as db:
            row=db.execute('SELECT attempts,consumed_at FROM sms_challenges').fetchone();self.assertEqual(5,row['attempts']);self.assertIsNotNone(row['consumed_at'])
        with self.assertRaises(AnalysisError):self.auth.phone.verify(UUID(challenge),actual,'','other-test-client')
    def test_expired_code_cannot_create_account(self):
        challenge=self.request_phone()
        with self.database.connection() as db:db.execute('UPDATE sms_challenges SET expires_at=0')
        self.assertEqual(400,self.client.post('/v1/auth/phone/verify',json={'challenge_id':challenge,'code':self.sms.messages[-1][1]}).status_code)
        with self.database.connection() as db:self.assertEqual(0,db.execute('SELECT COUNT(*) AS n FROM users').fetchone()['n'])
    def test_send_cooldown_and_reservations(self):
        self.request_phone()
        self.assertEqual(429,self.client.post('/v1/auth/phone/request',json={'phone':'+12025550123','region':'US'}).status_code)
        self.assertEqual(1,len(self.sms.messages))
        for _ in range(2):
            with self.database.connection() as db:db.execute('UPDATE sms_challenges SET created_at=created_at-61')
            self.request_phone()
        with self.database.connection() as db:db.execute('UPDATE sms_challenges SET created_at=created_at-61')
        self.assertEqual(429,self.client.post('/v1/auth/phone/request',json={'phone':'+12025550123','region':'US'}).status_code)
        self.assertEqual(3,len(self.sms.messages))
    def test_phone_does_not_merge_email_by_name(self):
        email=self.account();phone=self.phone_account();self.assertNotEqual(email['user_id'],phone['user_id'])
        self.assertIsNone(self.client.get('/v1/users/me',headers=self.headers(phone)).json()['email'])
    def test_phone_explicit_guest_link_preserves_quota(self):
        guest=self.guest();account=self.phone_account()
        from uuid import uuid4
        response=self.client.post('/v1/auth/link-guest',headers=self.headers(account),json={'guest_token':guest,'operation':str(uuid4())})
        self.assertEqual(200,response.status_code)
        self.assertEqual(200,self.analyze(account['access_token']).status_code)
        self.assertEqual(401,self.analyze(guest).status_code)
    def image(self,color='green'):
        output=io.BytesIO();image=Image.new('RGB',(900,700),color);exif=Image.Exif();exif[271]='private-camera-metadata'
        image.save(output,'JPEG',exif=exif);return output.getvalue()
    def upload(self,tokens,data):return self.client.put('/v1/users/me/avatar',content=data,headers=self.headers(tokens)|{'Content-Type':'image/jpeg'})
    def test_avatar_reencode_replace_delete_and_login(self):
        tokens=self.account();first=self.upload(tokens,self.image());self.assertEqual(200,first.status_code)
        key=first.json()['avatar_version'];self.assertTrue(self.auth.avatars.path(key).exists())
        response=self.client.get('/v1/users/me/avatar',headers=self.headers(tokens))
        with Image.open(io.BytesIO(response.content)) as photo:self.assertEqual((512,512),photo.size);self.assertEqual({},dict(photo.getexif()))
        second=self.upload(tokens,self.image('red'));self.assertEqual(200,second.status_code)
        self.assertFalse(self.auth.avatars.path(key).exists());key=second.json()['avatar_version']
        self.client.post('/v1/auth/logout',headers=self.headers(tokens))
        tokens=self.client.post('/v1/auth/login',json={'email':'first@example.com','password':PASSWORD}).json()
        self.assertEqual(key,self.client.get('/v1/users/me',headers=self.headers(tokens)).json()['avatar_version'])
        self.assertEqual(200,self.client.get('/v1/users/me/avatar',headers=self.headers(tokens)).status_code)
        self.assertEqual(200,self.client.delete('/v1/users/me/avatar',headers=self.headers(tokens)).status_code)
        self.assertFalse(self.auth.avatars.path(key).exists())
        self.assertEqual(404,self.client.get('/v1/users/me/avatar',headers=self.headers(tokens)).status_code)
    def test_avatar_format_size_owner_and_no_secret_validation(self):
        first=self.account();second=self.account('second@example.com')
        self.upload(first,self.image());self.assertEqual(404,self.client.get('/v1/users/me/avatar?user_id='+first['user_id'],headers=self.headers(second)).status_code)
        self.assertEqual(401,self.client.get('/v1/users/me/avatar').status_code)
        self.assertEqual(422,self.upload(second,b'private-invalid-image').status_code)
        self.assertEqual(413,self.upload(second,b'x'*(2*1024*1024+1)).status_code)
        self.assertEqual(422,self.client.patch('/v1/users/me',headers=self.headers(second),json={'name':'Test','id':first['user_id']}).status_code)
    def test_delete_account_removes_avatar_but_finance_remains(self):
        tokens=self.account();key=self.upload(tokens,self.image()).json()['avatar_version']
        self.assertEqual(200,self.client.request('DELETE','/v1/users/me',headers=self.headers(tokens),json={'password':PASSWORD}).status_code)
        self.assertFalse(self.auth.avatars.path(key).exists())
