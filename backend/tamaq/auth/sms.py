"""Delivery ports. Runtime never selects a paid SMS service or logs codes."""
import json
import os
from pathlib import Path
from typing import Protocol
from ..contracts import AnalysisError

class SMSDelivery(Protocol):
    available: bool
    development: bool
    def send(self, phone: str, code: str, challenge: str): ...

class DisabledSMS:
    available=False
    development=False
    def send(self, phone, code, challenge): raise AnalysisError('sms_unavailable',503)

class DevelopmentSMS:
    """Private local inbox, deliberately unavailable in production. No network delivery."""
    available=True
    development=True
    def __init__(self, path, environment):
        if environment!='development': raise RuntimeError('Development SMS requires development')
        self.root=Path(path)
        self.root.mkdir(parents=True,exist_ok=True,mode=0o700)
    def send(self, phone, code, challenge):
        target=self.root/(challenge+'.json')
        descriptor=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(descriptor,'w',encoding='utf-8') as output:
            json.dump({'phone':phone,'code':code,'challenge':challenge,'delivery':'development-only'},output)
        # Bound retention. Expired inbox files do not grant authentication.
        import time
        for prior in self.root.glob('*.json'):
            if prior.stat().st_mtime < time.time()-600: prior.unlink(missing_ok=True)

def configured_sms():
    provider=os.environ.get('TAMAQ_SMS_PROVIDER','disabled')
    if provider=='disabled': return DisabledSMS()
    if provider=='development':
        return DevelopmentSMS(os.environ.get('TAMAQ_SMS_INBOX','var/private/sms'),os.environ.get('TAMAQ_ENV','development'))
    raise RuntimeError('SMS adapter is not configured; no implicit paid delivery')

class MemorySMS:
    """Explicitly injected test double; never selected by runtime configuration."""
    available=True
    development=True
    def __init__(self): self.messages=[]
    def send(self, phone, code, challenge): self.messages.append((phone,code,challenge))
