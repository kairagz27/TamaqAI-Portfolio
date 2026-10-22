"""Persistent OTP attempts, send reservations and single-use ownership proof."""
import hashlib
import hmac
import secrets
from uuid import uuid4
import phonenumbers
from .service import AUTH_LOCK, now, digest
from ..contracts import AnalysisError

def normalize_phone(value, region='KZ'):
    try:
        if region not in ('KZ','RU','US','GB','DE','TR','UZ','KG') or len(value)>32: raise ValueError()
        number=phonenumbers.parse(value,region)
        if number.extension or not phonenumbers.is_valid_number(number): raise ValueError()
        if phonenumbers.number_type(number) not in (phonenumbers.PhoneNumberType.MOBILE,phonenumbers.PhoneNumberType.FIXED_LINE_OR_MOBILE): raise ValueError()
        return phonenumbers.format_number(number,phonenumbers.PhoneNumberFormat.E164)
    except (ValueError,phonenumbers.NumberParseException): raise AnalysisError('invalid_phone',422) from None

class PhoneAuth:
    def __init__(self, auth, delivery): self.auth,self.delivery=auth,delivery
    def code_hash(self, challenge, phone, code):
        return hmac.new(self.auth.key.encode(),('sms:'+str(challenge)+':'+phone+':'+code).encode(),hashlib.sha256).hexdigest()
    def request(self, raw, region, ip):
        if not self.delivery.available: raise AnalysisError('sms_unavailable',503)
        phone=normalize_phone(raw,region); moment=now(); challenge=uuid4(); code=f'{secrets.randbelow(1_000_000):06d}'
        with self.auth.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)',(AUTH_LOCK,))
            last=db.execute('SELECT created_at FROM sms_challenges WHERE phone=%s ORDER BY created_at DESC LIMIT 1',(phone,)).fetchone()
            if last and last['created_at']>moment-60: raise AnalysisError('sms_cooldown',429)
            rules=[('sms:phone:'+digest(phone),3,900),('sms:phone-day:'+digest(phone),10,86400),('sms:ip:'+digest(ip),10,3600),('sms:global',100,3600)]
            for bucket,maximum,window in rules:
                if db.execute('SELECT COUNT(*) AS n FROM auth_attempts WHERE bucket=%s AND created_at>%s',(bucket,moment-window)).fetchone()['n']>=maximum:
                    raise AnalysisError('auth_rate_limit',429)
            for bucket,_,_ in rules: db.execute('INSERT INTO auth_attempts(bucket,created_at) VALUES(%s,%s)',(bucket,moment))
            db.execute('UPDATE sms_challenges SET consumed_at=%s WHERE phone=%s AND consumed_at IS NULL',(moment,phone))
            db.execute('INSERT INTO sms_challenges(id,phone,code_hash,created_at,expires_at) VALUES(%s,%s,%s,%s,%s)',(challenge,phone,self.code_hash(challenge,phone,code),moment,moment+300))
            db.execute('DELETE FROM sms_challenges WHERE created_at<%s',(moment-86400,))
        try: self.delivery.send(phone,code,str(challenge))
        except Exception:
            with self.auth.db.connection() as db: db.execute('UPDATE sms_challenges SET consumed_at=%s WHERE id=%s',(moment,challenge))
            raise AnalysisError('sms_delivery_unavailable',503) from None
        return {'challenge_id':str(challenge),'expires_in':300,'resend_after':60,'development_delivery':self.delivery.development}
    def verify(self, challenge, code, name, ip):
        if not self.delivery.available: raise AnalysisError('sms_unavailable',503)
        self.auth.limit('sms_verify',ip,str(challenge))
        failure=True; result=None; moment=now()
        with self.auth.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)',(AUTH_LOCK,))
            moment=now()
            row=db.execute('SELECT * FROM sms_challenges WHERE id=%s FOR UPDATE',(challenge,)).fetchone()
            if row and row['consumed_at'] is None and row['expires_at']>moment and row['attempts']<5:
                matches=hmac.compare_digest(row['code_hash'],self.code_hash(challenge,row['phone'],code))
                db.execute('UPDATE sms_challenges SET attempts=attempts+1,consumed_at=%s WHERE id=%s',(moment if matches or row['attempts']==4 else None,challenge))
                if matches:
                    user=db.execute('SELECT * FROM users WHERE phone=%s AND deleted_at IS NULL FOR UPDATE',(row['phone'],)).fetchone()
                    if not user:
                        user=db.execute('INSERT INTO users(id,phone,phone_verified_at,name,created_at) VALUES(%s,%s,%s,%s,%s) RETURNING *',(uuid4(),row['phone'],moment,name.strip(),moment)).fetchone()
                        from psycopg.types.json import Jsonb
                        from ..nutrition.models import Questionnaire
                        db.execute('INSERT INTO user_fitness VALUES(%s,1,%s,%s)',(user['id'],Jsonb(Questionnaire().model_dump()),moment))
                    sid=uuid4()
                    db.execute('INSERT INTO auth_sessions VALUES(%s,%s,%s,%s,%s,NULL)',(sid,user['id'],moment,moment,moment+30*86400))
                    result=self.auth.tokens(db,user['id'],sid,moment); failure=False
        # Failed attempts and consumed challenges must commit before raising.
        if failure: raise AnalysisError('invalid_sms_code',400)
        return result
