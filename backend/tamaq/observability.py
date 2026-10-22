"""Response metadata only. Never inspect bodies, credentials, IPs or URL parameters."""
import asyncio
import json
import logging
import time
from uuid import uuid4
from starlette.responses import JSONResponse

log = logging.getLogger('tamaq.requests')
if not log.handlers:
    log.addHandler(logging.StreamHandler())
log.setLevel(logging.INFO)
log.propagate = False


class RequestMetadata:
    def __init__(self, app, body_timeout=15):
        self.app, self.body_timeout = app, body_timeout

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope, receive, send)
        started = time.monotonic()
        request_id = uuid4().hex
        status, sent, complete = 500, False, True
        body_complete = False

        async def limited_receive():
            nonlocal body_complete
            if body_complete:
                return await receive()  # Waiting for disconnect is not an upload.
            remaining = self.body_timeout - (time.monotonic() - started)
            if remaining <= 0:
                raise TimeoutError()
            message = await asyncio.wait_for(receive(), remaining)
            if message['type'] == 'http.request' and not message.get('more_body',False):
                body_complete = True
            return message

        async def metadata_send(message):
            nonlocal status, sent
            if message['type'] == 'http.response.start':
                status, sent = message['status'], True
                message = dict(message)
                message['headers'] = [(k,v) for k,v in message.get('headers', []) if k.lower() != b'x-request-id'] + [(b'x-request-id', request_id.encode())]
            await send(message)

        try:
            await self.app(scope, limited_receive, metadata_send)
        except Exception as error:
            # No exception text/traceback: DB/SMTP errors may contain private data.
            complete = False
            if not sent:
                timeout = isinstance(error, TimeoutError)
                await JSONResponse({'error': {'code': 'request_timeout' if timeout else 'server_unavailable'}},
                                   status_code=408 if timeout else 503)(scope, receive, metadata_send)
        finally:
            route = getattr(scope.get('route'), 'path', 'unmatched')
            log.info(json.dumps({'request_id': request_id, 'method': scope['method'] if scope['method'] in ('GET','POST','PATCH','PUT','DELETE','HEAD','OPTIONS') else 'OTHER',
                                 'route': route, 'status': status, 'complete': complete,
                                 'duration_ms': round((time.monotonic()-started)*1000)}))
