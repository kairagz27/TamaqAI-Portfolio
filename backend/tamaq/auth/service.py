import hashlib
import os
import secrets
import threading
import time
from uuid import uuid4, UUID
import jwt
from pwdlib import PasswordHash
from pwdlib.hashers.argon2 import Argon2Hasher
from ..contracts import AnalysisError
from ..db import USAGE_LOCK
from ..usage import Actor

AUTH_LOCK = 8426500003
ACCESS_SECONDS = 600
SESSION_SECONDS = 30 * 86400
HASH = PasswordHash((Argon2Hasher(time_cost=2, memory_cost=19456, parallelism=1),))
HASH_SLOTS = threading.BoundedSemaphore(2)
with HASH_SLOTS: DUMMY_HASH = HASH.hash(secrets.token_urlsafe(32))

def digest(value): return hashlib.sha256(value.encode()).hexdigest()
def now(): return int(time.time())

class Auth:
    def __init__(self, database, delivery, key=None, sms=None, avatar_storage=None):
        self.db, self.delivery = database, delivery
        self.key = key or os.environ.get('TAMAQ_JWT_SECRET', '')
        if len(self.key.encode()) < 32: raise RuntimeError('TAMAQ_JWT_SECRET must have at least 32 bytes')
        from .sms import configured_sms
        from .phone import PhoneAuth
        from ..files import LocalAvatarStorage
        self.phone=PhoneAuth(self,sms if sms is not None else configured_sms())
        root=os.environ.get('TAMAQ_FILES_ROOT','')
        if avatar_storage is None and os.environ.get('TAMAQ_ENV')=='production' and not root:
            raise RuntimeError('Production requires persistent TAMAQ_FILES_ROOT or an injected object store')
        self.avatars=avatar_storage if avatar_storage is not None else LocalAvatarStorage(root or 'var/private/avatars')

    @staticmethod
    def contact_verified(user): return bool(user['verified_at'] or user.get('phone_verified_at'))

    def limit(self, kind, ip, email=''):
        moment = now()
        maximum=120 if kind=='refresh' else 5
        rules = [(kind + ':ip:' + digest(ip), maximum, 60 if kind == 'login' else 3600),
                 (kind + ':email:' + digest(email.casefold()), maximum, 60 if kind == 'login' else 3600),
                 (kind + ':global', 100, 3600)]
        with self.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            for bucket, maximum, window in rules:
                if db.execute('SELECT COUNT(*) AS n FROM auth_attempts WHERE bucket=%s AND created_at>%s', (bucket,moment-window)).fetchone()['n'] >= maximum:
                    raise AnalysisError('auth_rate_limit', 429)
            for bucket, _, _ in rules: db.execute('INSERT INTO auth_attempts(bucket,created_at) VALUES(%s,%s)', (bucket,moment))
            db.execute('DELETE FROM auth_attempts WHERE created_at<%s', (moment-86400,))

    def action(self, db, user, purpose):
        token = secrets.token_urlsafe(32)
        db.execute('UPDATE email_actions SET used_at=%s WHERE user_id=%s AND purpose=%s AND used_at IS NULL', (now(),user['id'],purpose))
        db.execute('INSERT INTO email_actions VALUES(%s,%s,%s,%s,%s,NULL)', (digest(token),user['id'],purpose,now(),now()+(86400 if purpose=='verify' else 1800)))
        return token

    def deliver(self, email, purpose, token):
        try: self.delivery.send(email,purpose,token)
        except Exception: raise AnalysisError('email_delivery_unavailable',503) from None

    def register(self, email, password, name, ip):
        self.limit('register',ip,email)
        with HASH_SLOTS: hashed = HASH.hash(password)
        token = None
        with self.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            user=db.execute('INSERT INTO users(id,email,email_key,password_hash,name,created_at) VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT(email_key) DO NOTHING RETURNING *', (uuid4(),email,email.casefold(),hashed,name,now())).fetchone()
            if user:
                from psycopg.types.json import Jsonb
                from ..nutrition.models import Questionnaire
                db.execute('INSERT INTO user_fitness VALUES(%s,1,%s,%s)',(user['id'],Jsonb(Questionnaire().model_dump()),now()))
                token=self.action(db,user,'verify')
        if token: self.deliver(email,'verify',token)
        return {'accepted':True}

    def verify_password(self, password, hashed):
        with HASH_SLOTS:
            return HASH.verify(password, hashed or DUMMY_HASH)

    def login(self, email, password, ip):
        self.limit('login',ip,email)
        with self.db.connection() as db: user=db.execute('SELECT * FROM users WHERE email_key=%s AND deleted_at IS NULL', (email.casefold(),)).fetchone()
        if not self.verify_password(password, user['password_hash'] if user else None) or not user:
            raise AnalysisError('invalid_credentials',401)
        # Recheck after hashing so a simultaneous password reset cannot resurrect access.
        with self.db.connection() as db:
            active=db.execute('SELECT * FROM users WHERE id=%s AND password_hash=%s AND deleted_at IS NULL FOR UPDATE', (user['id'],user['password_hash'])).fetchone()
            if not active: raise AnalysisError('invalid_credentials',401)
            sid=uuid4(); moment=now()
            db.execute('INSERT INTO auth_sessions VALUES(%s,%s,%s,%s,%s,NULL)', (sid,user['id'],moment,moment,moment+SESSION_SECONDS))
            return self.tokens(db,user['id'],sid,moment)

    def tokens(self, db, uid, sid, auth_time):
        moment=now(); refresh=secrets.token_urlsafe(48)
        db.execute('INSERT INTO refresh_tokens VALUES(%s,%s,%s,NULL,NULL)', (digest(refresh),sid,moment))
        access=jwt.encode({'sub':str(uid),'sid':str(sid),'iat':moment,'nbf':moment,'exp':moment+ACCESS_SECONDS,'auth_time':auth_time,'iss':'tamaqai','aud':'tamaqai-android','type':'access'}, self.key, algorithm='HS256')
        return {'access_token':access,'refresh_token':refresh,'expires_in':ACCESS_SECONDS,'user_id':str(uid)}

    def refresh(self, token, ip):
        self.limit('refresh',ip,digest(token))
        failure=False; result=None
        with self.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            row=db.execute('SELECT r.*,s.user_id,s.auth_time,s.expires_at,s.revoked_at AS session_revoked,u.deleted_at FROM refresh_tokens r JOIN auth_sessions s ON s.id=r.session_id JOIN users u ON u.id=s.user_id WHERE r.token_hash=%s FOR UPDATE OF r,s', (digest(token),)).fetchone()
            if not row or row['session_revoked'] or row['deleted_at'] or row['expires_at']<=now(): failure=True
            elif row['used_at'] or row['revoked_at']:
                db.execute('UPDATE auth_sessions SET revoked_at=%s WHERE id=%s', (now(),row['session_id']))
                db.execute('UPDATE refresh_tokens SET revoked_at=%s WHERE session_id=%s', (now(),row['session_id']))
                failure=True
            else:
                db.execute('UPDATE refresh_tokens SET used_at=%s WHERE token_hash=%s', (now(),digest(token)))
                result=self.tokens(db,row['user_id'],row['session_id'],row['auth_time'])
        # Commit replay revocation before raising the response error.
        if failure: raise AnalysisError('session_expired',401)
        return result

    def identity(self, token):
        try:
            claims=jwt.decode(token,self.key,algorithms=['HS256'],issuer='tamaqai',audience='tamaqai-android',options={'require':['sub','sid','exp','iat','nbf','auth_time','type']})
            if claims['type']!='access': raise ValueError()
            uid,sid=UUID(claims['sub']),UUID(claims['sid'])
        except (jwt.PyJWTError, ValueError, TypeError): raise AnalysisError('token_expired',401) from None
        with self.db.connection() as db:
            row=db.execute('SELECT u.*,s.id AS session_id,s.auth_time FROM users u JOIN auth_sessions s ON u.id=s.user_id WHERE u.id=%s AND s.id=%s AND u.deleted_at IS NULL AND s.revoked_at IS NULL AND s.expires_at>%s', (uid,sid,now())).fetchone()
        if not row: raise AnalysisError('unauthorized',401)
        return row

    def logout(self, user):
        with self.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            db.execute('UPDATE auth_sessions SET revoked_at=%s WHERE id=%s AND user_id=%s', (now(),user['session_id'],user['id']))
            db.execute('UPDATE refresh_tokens SET revoked_at=%s WHERE session_id=%s', (now(),user['session_id']))
        return {'revoked':True}

    def forgot(self,email,ip):
        self.limit('reset',ip,email); token=None
        with self.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            user=db.execute('SELECT * FROM users WHERE email_key=%s AND deleted_at IS NULL', (email.casefold(),)).fetchone()
            if user: token=self.action(db,user,'reset')
        if token: self.deliver(user['email'],'reset',token)
        return {'accepted':True}

    def resend(self,user,ip):
        self.limit('verify',ip,user['email']); token=None
        with self.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            current=db.execute('SELECT * FROM users WHERE id=%s AND deleted_at IS NULL FOR UPDATE', (user['id'],)).fetchone()
            if current and not current['verified_at']: token=self.action(db,current,'verify')
        if token: self.deliver(user['email'],'verify',token)
        return {'accepted':True}

    def consume_action(self,token,purpose,ip,password=None):
        self.limit('email_action',ip,digest(token))
        hashed=None
        if password:
            with HASH_SLOTS: hashed=HASH.hash(password)
        with self.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            row=db.execute('SELECT a.* FROM email_actions a JOIN users u ON u.id=a.user_id WHERE a.token_hash=%s AND a.purpose=%s AND a.used_at IS NULL AND a.expires_at>%s AND u.deleted_at IS NULL FOR UPDATE OF a,u', (digest(token),purpose,now())).fetchone()
            if not row: raise AnalysisError('invalid_email_code',400)
            db.execute('UPDATE email_actions SET used_at=%s WHERE user_id=%s AND purpose=%s', (now(),row['user_id'],purpose))
            if purpose=='verify': db.execute('UPDATE users SET verified_at=%s WHERE id=%s', (now(),row['user_id']))
            else:
                db.execute('UPDATE users SET password_hash=%s WHERE id=%s', (hashed,row['user_id']))
                db.execute('UPDATE auth_sessions SET revoked_at=%s WHERE user_id=%s', (now(),row['user_id']))
                db.execute('UPDATE refresh_tokens SET revoked_at=%s WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=%s)', (now(),row['user_id']))
        return {'completed':True}

    def link_guest(self,user,token,operation):
        if not self.contact_verified(user): raise AnalysisError('email_not_verified',403)
        if user['auth_time'] < now()-600: raise AnalysisError('reauthentication_required',403)
        with self.db.connection() as db:
            # Same lock order as account deletion; usage lock prevents reserve/link races.
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            db.execute('SELECT pg_advisory_xact_lock(%s)', (USAGE_LOCK,))
            current=db.execute('SELECT id FROM auth_sessions WHERE id=%s AND user_id=%s AND revoked_at IS NULL AND expires_at>%s', (user['session_id'],user['id'],now())).fetchone()
            if not current: raise AnalysisError('unauthorized',401)
            existing=db.execute('SELECT linked_operation FROM invitations WHERE user_id=%s', (user['id'],)).fetchone()
            if existing:
                if str(existing['linked_operation'])==str(operation): return {'linked':True,'operation':str(operation)}
                raise AnalysisError('link_conflict',409)
            guest=db.execute('SELECT principal FROM guests WHERE token_hash=%s AND expires_at>%s', (digest(token),now())).fetchone()
            invitation=db.execute('SELECT * FROM invitations WHERE principal=%s FOR UPDATE', (guest['principal'],)).fetchone() if guest else None
            if not invitation or not invitation['enabled']: raise AnalysisError('invalid_guest_session',401)
            if invitation['user_id']: raise AnalysisError('link_conflict',409)
            db.execute('UPDATE invitations SET user_id=%s,linked_operation=%s WHERE principal=%s', (user['id'],operation,guest['principal']))
            db.execute('DELETE FROM guests WHERE principal=%s', (guest['principal'],))
        return {'linked':True,'operation':str(operation)}

    def analysis_actor(self,user):
        if not self.contact_verified(user): raise AnalysisError('email_not_verified',403)
        with self.db.connection() as db: invitation=db.execute('SELECT principal FROM invitations WHERE user_id=%s AND enabled=TRUE', (user['id'],)).fetchone()
        if not invitation: raise AnalysisError('invite_required',403)
        return Actor(invitation['principal'],str(user['id']),str(user['session_id']))
