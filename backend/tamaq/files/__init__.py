"""Avatar storage port. Deployments may inject an object-store implementation."""
import io
import os
import re
from pathlib import Path
from typing import Protocol
from uuid import uuid4
from PIL import Image, ImageOps, UnidentifiedImageError
from ..contracts import AnalysisError

MAX_AVATAR_BYTES=2*1024*1024

def avatar_image(raw):
    if not raw or len(raw)>MAX_AVATAR_BYTES: raise AnalysisError('avatar_too_large',413)
    try:
        with Image.open(io.BytesIO(raw)) as image:
            if image.format not in ('JPEG','PNG','WEBP') or getattr(image,'n_frames',1)!=1: raise ValueError()
            if image.width*image.height>16_000_000 or min(image.size)<1: raise ValueError()
            image.load()
            clean=ImageOps.fit(ImageOps.exif_transpose(image).convert('RGB'),(512,512),method=Image.Resampling.LANCZOS)
            result=io.BytesIO();clean.save(result,'JPEG',quality=85)
            return result.getvalue()
    except (ValueError,OSError,UnidentifiedImageError,Image.DecompressionBombError):
        raise AnalysisError('invalid_avatar',422) from None

class AvatarStorage(Protocol):
    def write(self, data: bytes) -> str: ...
    def read(self, key: str) -> bytes: ...
    def delete(self, key: str | None): ...

class LocalAvatarStorage:
    def __init__(self, root):
        self.root=Path(root)
        if self.root.is_symlink(): raise RuntimeError('Avatar root must not be a symlink')
        self.root.mkdir(parents=True,exist_ok=True,mode=0o700)
    def path(self,key):
        if not re.fullmatch(r'[0-9a-f]{32}\.jpg',key): raise AnalysisError('invalid_avatar',422)
        target=self.root/key
        if target.is_symlink(): raise AnalysisError('invalid_avatar',422)
        return target
    def write(self,data):
        key=uuid4().hex+'.jpg';target=self.path(key)
        descriptor=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        try:
            with os.fdopen(descriptor,'wb') as output:output.write(data)
        except BaseException:
            target.unlink(missing_ok=True)
            raise
        return key
    def read(self,key):
        try:return self.path(key).read_bytes()
        except FileNotFoundError:raise AnalysisError('avatar_not_found',404) from None
    def delete(self,key):
        if key:self.path(key).unlink(missing_ok=True)
