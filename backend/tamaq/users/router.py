from fastapi import APIRouter, Request
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field
from ..auth.service import AUTH_LOCK, now
from ..contracts import AnalysisError
from ..db import USAGE_LOCK

class Profile(BaseModel):
    model_config=ConfigDict(extra='forbid',allow_inf_nan=False)
    name: str=Field(max_length=80)
    height_cm: float | None=Field(default=None,ge=50,le=250)
    current_weight_kg: float | None=Field(default=None,ge=20,le=500)
    target_weight_kg: float | None=Field(default=None,ge=20,le=500)
class Delete(BaseModel):
    model_config=ConfigDict(extra='forbid')
    password: str=Field(default='',max_length=128)
    phone_confirmation: bool=False

def router(service,identity):
    api=APIRouter(prefix='/v1/users')
    def public(user):
        with service.db.connection() as db: invite=db.execute('SELECT linked_operation FROM invitations WHERE user_id=%s', (user['id'],)).fetchone()
        return {**{k:user[k] for k in ('email','name','height_cm','current_weight_kg','target_weight_kg')},
                'id':str(user['id']),'email_verified':user['verified_at'] is not None,
                'phone':user['phone'],'phone_verified':user['phone_verified_at'] is not None,
                'avatar_version':user['avatar_key'],
                'pilot_linked':invite is not None,'linked_operation':str(invite['linked_operation']) if invite else None}
    @api.get('/me')
    def me(request:Request): return public(identity(request))
    @api.patch('/me')
    def update(body:Profile,request:Request):
        user=identity(request)
        with service.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            row=db.execute('UPDATE users SET name=%s,height_cm=%s,current_weight_kg=%s,target_weight_kg=%s WHERE id=%s AND deleted_at IS NULL AND EXISTS(SELECT 1 FROM auth_sessions WHERE id=%s AND revoked_at IS NULL AND expires_at>%s) RETURNING *', (body.name.strip(),body.height_cm,body.current_weight_kg,body.target_weight_kg,user['id'],user['session_id'],now())).fetchone()
            if not row: raise AnalysisError('unauthorized',401)
        return public(row)
    @api.get('/me/avatar')
    def avatar(request:Request):
        user=identity(request)
        if not user['avatar_key']: raise AnalysisError('avatar_not_found',404)
        return Response(service.avatars.read(user['avatar_key']),media_type='image/jpeg',headers={'Cache-Control':'private, no-store'})
    @api.put('/me/avatar')
    async def upload_avatar(request:Request):
        from ..files import avatar_image, MAX_AVATAR_BYTES
        from starlette.concurrency import run_in_threadpool
        user=await run_in_threadpool(identity,request)
        raw=bytearray()
        async for chunk in request.stream():
            if len(raw)+len(chunk)>MAX_AVATAR_BYTES: raise AnalysisError('avatar_too_large',413)
            raw.extend(chunk)
        clean=await run_in_threadpool(avatar_image,bytes(raw))
        def store():
            key=None;old=None
            try:
                with service.db.connection() as db:
                    db.execute('SELECT pg_advisory_xact_lock(%s)',(AUTH_LOCK,))
                    active=db.execute('SELECT u.avatar_key FROM users u JOIN auth_sessions s ON s.user_id=u.id WHERE u.id=%s AND s.id=%s AND s.revoked_at IS NULL AND s.expires_at>%s AND u.deleted_at IS NULL FOR UPDATE OF u',(user['id'],user['session_id'],now())).fetchone()
                    if not active: raise AnalysisError('unauthorized',401)
                    old=active['avatar_key'];key=service.avatars.write(clean)
                    row=db.execute('UPDATE users SET avatar_key=%s WHERE id=%s RETURNING *',(key,user['id'])).fetchone()
            except Exception:
                if key: service.avatars.delete(key)
                raise
            service.avatars.delete(old)
            return public(row)
        return await run_in_threadpool(store)
    @api.delete('/me/avatar')
    def remove_avatar(request:Request):
        user=identity(request)
        with service.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)',(AUTH_LOCK,))
            active=db.execute('SELECT u.avatar_key FROM users u JOIN auth_sessions s ON s.user_id=u.id WHERE u.id=%s AND s.id=%s AND s.revoked_at IS NULL AND s.expires_at>%s AND u.deleted_at IS NULL FOR UPDATE OF u',(user['id'],user['session_id'],now())).fetchone()
            if not active: raise AnalysisError('unauthorized',401)
            row=db.execute('UPDATE users SET avatar_key=NULL WHERE id=%s RETURNING *',(user['id'],)).fetchone()
        service.avatars.delete(active['avatar_key'])
        return public(row)
    @api.delete('/me')
    def delete(body:Delete,request:Request):
        user=identity(request)
        service.limit('delete',request.client.host if request.client else 'unknown',user['email'] or user['phone'])
        if user['password_hash']:
            if not service.verify_password(body.password,user['password_hash']): raise AnalysisError('invalid_credentials',401)
        elif not body.phone_confirmation or not user['phone_verified_at'] or user['auth_time']<now()-600:
            raise AnalysisError('reauthentication_required',403)
        with service.db.connection() as db:
            db.execute('SELECT pg_advisory_xact_lock(%s)', (AUTH_LOCK,))
            db.execute('SELECT pg_advisory_xact_lock(%s)', (USAGE_LOCK,))
            active=db.execute('SELECT id,avatar_key FROM users WHERE id=%s AND password_hash IS NOT DISTINCT FROM %s AND deleted_at IS NULL FOR UPDATE', (user['id'],user['password_hash'])).fetchone()
            if not active: raise AnalysisError('reauthentication_required',403)
            session=db.execute('SELECT id FROM auth_sessions WHERE id=%s AND user_id=%s AND revoked_at IS NULL AND expires_at>%s', (user['session_id'],user['id'],now())).fetchone()
            if not session: raise AnalysisError('unauthorized',401)
            db.execute('UPDATE users SET email=NULL,email_key=NULL,password_hash=NULL,phone=NULL,phone_verified_at=NULL,avatar_key=NULL,name=\'\',height_cm=NULL,current_weight_kg=NULL,target_weight_kg=NULL,deleted_at=%s WHERE id=%s', (now(),user['id']))
            db.execute('UPDATE auth_sessions SET revoked_at=%s WHERE user_id=%s', (now(),user['id']))
            db.execute('UPDATE refresh_tokens SET revoked_at=%s WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=%s)', (now(),user['id']))
            db.execute('DELETE FROM email_actions WHERE user_id=%s', (user['id'],))
            for table in ('user_fitness','user_goals','meal_plans','plan_mutations'):
                db.execute(f'DELETE FROM {table} WHERE user_id=%s',(user['id'],))
            # Private catalogue versions contain user-entered facts. Unlike financial
            # tombstones, they are deleted with the account; shared facts survive.
            db.execute('DELETE FROM catalog_entries WHERE owner_id=%s',(user['id'],))
            db.execute('UPDATE requests SET body=NULL,usage=NULL,status=\'Deleted\' WHERE guest IN (SELECT principal FROM invitations WHERE user_id=%s)', (user['id'],))
            # Financial/idempotency tombstones and the invitation claim survive deletion.
        service.avatars.delete(active['avatar_key'])
        return {'deleted':True,'diary_backup':False}
    return api
