import hashlib, json, os, time
from uuid import UUID
from fastapi import APIRouter, Request, Header
from fastapi.responses import JSONResponse
from psycopg.types.json import Jsonb
from ..contracts import AnalysisError
from ..nutrition.router import active
from ..nutrition.models import Questionnaire
from ..provider import OpenAIProvider
from .models import PlanWrite, Generate

def router(auth,identity,ledger,provider,environment):
    api=APIRouter(prefix='/v1/plans')
    def public(row): return {'id':str(row['id']),'version':row['version'],'document':row['document'],'is_demo':row['document'].get('is_demo',False)}
    @api.get('')
    def list_plans(request:Request):
        user=identity(request)
        with auth.db.connection() as db:
            active(db,user)
            rows=db.execute('SELECT id,version,document FROM meal_plans WHERE user_id=%s ORDER BY updated_at DESC LIMIT 30',(user['id'],)).fetchall()
        return {'plans':[public(r) for r in rows]}
    @api.get('/{pid}')
    def get(pid:UUID,request:Request):
        user=identity(request)
        with auth.db.connection() as db:
            active(db,user)
            row=db.execute('SELECT id,version,document FROM meal_plans WHERE id=%s AND user_id=%s',(pid,user['id'])).fetchone()
        if not row: raise AnalysisError('plan_not_found',404)
        return public(row)
    @api.put('/{pid}')
    def put(pid:UUID,body:PlanWrite,request:Request):
        user=identity(request)
        document=body.document.model_dump(mode='json')
        digest=hashlib.sha256(json.dumps({'id':str(pid),'version':body.version,'document':document},sort_keys=True,separators=(',',':')).encode()).hexdigest()
        with auth.db.connection() as db:
            active(db,user)
            prior=db.execute('SELECT digest,response FROM plan_mutations WHERE user_id=%s AND operation=%s',(user['id'],body.operation)).fetchone()
            if prior:
                if prior['digest']!=digest: raise AnalysisError('idempotency_conflict',409)
                return prior['response']
            row=db.execute('SELECT * FROM meal_plans WHERE id=%s FOR UPDATE',(pid,)).fetchone()
            if row and row['user_id']!=user['id']: raise AnalysisError('plan_not_found',404)
            if not row and body.version>0: raise AnalysisError('plan_not_found',404)
            if not row and body.document.source=='ai': raise AnalysisError('invalid_plan_source',422)
            if (row['version'] if row else 0)!=body.version: raise AnalysisError('edit_conflict',409)
            if row and row['document'].get('is_demo'): document['is_demo']=True
            version=body.version+1
            db.execute('INSERT INTO meal_plans VALUES(%s,%s,%s,%s,%s) ON CONFLICT(id) DO UPDATE SET version=excluded.version,document=excluded.document,updated_at=excluded.updated_at',(pid,user['id'],version,Jsonb(document),time.time_ns()//1000000))
            response={'id':str(pid),'version':version,'document':document,'is_demo':document.get('is_demo',False)}
            db.execute('INSERT INTO plan_mutations VALUES(%s,%s,%s,%s)',(user['id'],body.operation,digest,Jsonb(response)))
        return response
    @api.post('/generate')
    def generate(body:Generate,request:Request,idempotency_key:str | None=Header(default=None)):
        user=identity(request);actor=auth.analysis_actor(user)
        try: key=str(UUID(idempotency_key or ''))
        except ValueError: raise AnalysisError('invalid_idempotency_key',400) from None
        # Snapshot is stored in the cached ledger body. A retry after settings
        # changes must not charge again or silently produce a different plan.
        digest=hashlib.sha256(('plan-v1:'+body.model_dump_json()).encode()).hexdigest()
        prior=ledger.reserve(actor,key,digest)
        if prior is None:
            started=time.monotonic();output=None
            try:
                with auth.db.connection() as db:
                    active(db,user)
                    fitness=db.execute('SELECT version,document FROM user_fitness WHERE user_id=%s',(user['id'],)).fetchone()
                    goals=db.execute('SELECT effective_date,document FROM user_goals WHERE user_id=%s AND effective_date<=%s ORDER BY effective_date DESC LIMIT 1',(user['id'],body.date)).fetchone()
                if not fitness or fitness['version']!=body.fitness_version or not goals or goals['effective_date']!=body.goal_date:
                    error=AnalysisError('edit_conflict',409);error.cost_micro_usd=0;raise error
                q=Questionnaire.model_validate(fitness['document'])
                if not q.completed or not goals['document']['targets']['calories']:
                    error=AnalysisError('plan_targets_required',422);error.cost_micro_usd=0;raise error
                # Intentionally exclude account identity and unnecessary anthropometrics.
                params={k:getattr(q,k) for k in ('meals','times','preferences','exclusions','allergies','budget','cooking_minutes')}
                params.update(targets=goals['document']['targets'],language=body.language)
                chosen=provider
                if chosen is None:
                    try: chosen=OpenAIProvider(os.environ.get('OPENAI_API_KEY',''),os.environ.get('OPENAI_MODEL',''))
                    except AnalysisError as e: e.cost_micro_usd=0;raise
                output=chosen.plan(params);result=output.result.validate_semantics(ai=True)
                if len(result.meals)!=q.meals or sorted(m.time for m in result.meals)!=sorted(q.times): raise ValueError('schedule_mismatch')
                names=' '.join(i.name.casefold() for m in result.meals for i in m.ingredients)
                if any(x.casefold() in names for x in q.exclusions+q.allergies): raise ValueError('restricted_ingredient')
                payload={'id':key,'version':0,'document':{'date':body.date.isoformat(),'source':'ai','is_demo':chosen.is_demo,'content':result.model_dump()},'is_demo':chosen.is_demo,'model':chosen.model,'latency_ms':round((time.monotonic()-started)*1000),'cost_micro_usd':output.cost_micro_usd,'usage':output.usage}
                prior=json.dumps({'status':200,'body':payload},allow_nan=False)
                ledger.finish(actor,key,prior,output.cost_micro_usd,output.usage)
            except Exception as e:
                failure=e if isinstance(e,AnalysisError) else AnalysisError('invalid_plan_result' if output else 'analysis_outcome_unknown',502)
                cost=output.cost_micro_usd if output else getattr(e,'cost_micro_usd',None)
                usage=output.usage if output else getattr(e,'usage',None)
                prior=json.dumps({'status':failure.status_code,'body':{'error':{'code':failure.code}}})
                ledger.finish(actor,key,prior,cost,usage)
        cached=json.loads(prior)
        if environment=='production' and cached['body'].get('is_demo'): raise AnalysisError('demo_result_forbidden',503)
        if cached['status']==200:
            # Idempotently persist even if the initial DB commit failed after billing.
            with auth.db.connection() as db:
                active(db,user)
                existing=db.execute('SELECT user_id FROM meal_plans WHERE id=%s',(key,)).fetchone()
                if existing and existing['user_id']!=user['id']: raise AnalysisError('plan_not_found',404)
                db.execute('INSERT INTO meal_plans VALUES(%s,%s,1,%s,%s) ON CONFLICT(id) DO NOTHING',(key,user['id'],Jsonb(cached['body']['document']),time.time_ns()//1000000))
            cached['body']['version']=1
        return JSONResponse(cached['body'],status_code=cached['status'])
    return api
