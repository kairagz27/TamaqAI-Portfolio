from fastapi import APIRouter, Request
from psycopg.types.json import Jsonb
from ..auth.service import AUTH_LOCK, now
from ..contracts import AnalysisError
from .models import FitnessWrite, Questionnaire, GoalWrite
from .calculator import calculate

def active(db,user):
    db.execute('SELECT pg_advisory_xact_lock(%s)',(AUTH_LOCK,))
    row=db.execute('SELECT u.id FROM users u JOIN auth_sessions s ON s.user_id=u.id WHERE u.id=%s AND s.id=%s AND u.deleted_at IS NULL AND s.revoked_at IS NULL AND s.expires_at>%s',(user['id'],user['session_id'],now())).fetchone()
    if not row: raise AnalysisError('unauthorized',401)

def router(service,identity):
    api=APIRouter(prefix='/v1/nutrition')
    @api.get('/questionnaire')
    def get(request:Request):
        user=identity(request)
        with service.db.connection() as db:
            active(db,user)
            row=db.execute('SELECT version,document FROM user_fitness WHERE user_id=%s',(user['id'],)).fetchone()
        # Legacy accounts stay usable; opening the questionnaire starts an edit.
        return dict(row) if row else {'version':0,'document':Questionnaire(completed=True).model_dump()}
    @api.put('/questionnaire')
    def put(body:FitnessWrite,request:Request):
        user=identity(request)
        with service.db.connection() as db:
            active(db,user)
            row=db.execute('SELECT version FROM user_fitness WHERE user_id=%s FOR UPDATE',(user['id'],)).fetchone()
            version=row['version'] if row else 0
            if version!=body.version: raise AnalysisError('edit_conflict',409)
            version+=1
            db.execute('INSERT INTO user_fitness VALUES(%s,%s,%s,%s) ON CONFLICT(user_id) DO UPDATE SET version=excluded.version,document=excluded.document,updated_at=excluded.updated_at',(user['id'],version,Jsonb(body.document.model_dump()),now()))
        return {'version':version,'document':body.document.model_dump()}
    @api.post('/preview')
    def preview(body:Questionnaire,request:Request):
        identity(request)
        return calculate(body)
    @api.get('/goals')
    def goals(request:Request):
        user=identity(request)
        with service.db.connection() as db:
            active(db,user)
            rows=db.execute('SELECT effective_date,document FROM user_goals WHERE user_id=%s ORDER BY effective_date',(user['id'],)).fetchall()
        return {'history':[{'effective_date':r['effective_date'].isoformat(),**r['document']} for r in rows]}
    @api.put('/goals')
    def save_goal(body:GoalWrite,request:Request):
        user=identity(request)
        with service.db.connection() as db:
            active(db,user)
            data={'origin':body.origin,'targets':body.targets.model_dump()}
            if body.origin=='calculated':
                row=db.execute('SELECT version,document FROM user_fitness WHERE user_id=%s',(user['id'],)).fetchone()
                if not row or row['version']!=body.fitness_version: raise AnalysisError('edit_conflict',409)
                result=calculate(Questionnaire.model_validate(row['document']))
                if result['targets']!=body.targets.model_dump(): raise AnalysisError('calculated_targets_mismatch',422)
                data['formula']=result['formula'];data['fitness_version']=row['version']
            db.execute('INSERT INTO user_goals VALUES(%s,%s,%s,%s) ON CONFLICT(user_id,effective_date) DO UPDATE SET document=excluded.document,updated_at=excluded.updated_at',(user['id'],body.effective_date,Jsonb(data),now()))
        return {'effective_date':body.effective_date.isoformat(),**data}
    return api
