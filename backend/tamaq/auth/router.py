from uuid import UUID
from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator
from ..contracts import AnalysisError

class Input(BaseModel): model_config=ConfigDict(extra='forbid')
class Credentials(Input):
    email: EmailStr
    password: str=Field(min_length=1,max_length=128)
class Register(Credentials):
    password: str=Field(min_length=15,max_length=128)
    name: str=Field(min_length=1,max_length=80)
    @field_validator('name')
    @classmethod
    def nonblank(cls,value):
        if not value.strip(): raise ValueError('name_required')
        return value.strip()
class Email(Input): email: EmailStr
class Refresh(Input): refresh_token: str=Field(min_length=32,max_length=256)
class Code(Input): token: str=Field(min_length=32,max_length=128)
class Reset(Code): password: str=Field(min_length=15,max_length=128)
class Link(Input):
    guest_token: str=Field(min_length=32,max_length=128)
    operation: UUID
class PhoneRequest(Input):
    phone: str=Field(min_length=3,max_length=32)
    region: str=Field(default='KZ',min_length=2,max_length=2)
class PhoneVerify(Input):
    challenge_id: UUID
    code: str=Field(pattern=r'^\d{6}$',min_length=6,max_length=6)
    name: str=Field(default='',max_length=80)

def router(service):
    api=APIRouter(prefix='/v1/auth')
    def ip(request): return request.client.host if request.client else 'unknown'
    def identity(request):
        auth=request.headers.get('authorization','')
        if not auth.startswith('Bearer '): raise AnalysisError('unauthorized',401)
        return service.identity(auth[7:])
    @api.get('/methods')
    def methods(): return {'email':True,'phone':service.phone.delivery.available,'sms_development':service.phone.delivery.development}
    @api.post('/phone/request')
    def phone_request(body:PhoneRequest,request:Request): return service.phone.request(body.phone,body.region,ip(request))
    @api.post('/phone/verify')
    def phone_verify(body:PhoneVerify,request:Request): return service.phone.verify(body.challenge_id,body.code,body.name,ip(request))
    @api.post('/register')
    def register(body:Register,request:Request): return service.register(str(body.email),body.password,body.name.strip(),ip(request))
    @api.post('/login')
    def login(body:Credentials,request:Request): return service.login(str(body.email),body.password,ip(request))
    @api.post('/refresh')
    def refresh(body:Refresh,request:Request): return service.refresh(body.refresh_token,ip(request))
    @api.post('/logout')
    def logout(request:Request): return service.logout(identity(request))
    @api.post('/forgot-password')
    def forgot(body:Email,request:Request): return service.forgot(str(body.email),ip(request))
    @api.post('/reset-password')
    def reset(body:Reset,request:Request): return service.consume_action(body.token,'reset',ip(request),body.password)
    @api.post('/verify-email')
    def verify(body:Code,request:Request): return service.consume_action(body.token,'verify',ip(request))
    @api.post('/resend-verification')
    def resend(request:Request): return service.resend(identity(request),ip(request))
    @api.post('/link-guest')
    def link(body:Link,request:Request): return service.link_guest(identity(request),body.guest_token,body.operation)
    return api,identity
