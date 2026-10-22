import unittest, json
from types import SimpleNamespace
from datetime import date,timedelta
from uuid import uuid4
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
from pydantic import ValidationError
import test_accounts as accounts
from tamaq.nutrition.models import Questionnaire, GoalWrite
from tamaq.nutrition.calculator import calculate
from tamaq.plans.models import PlanContent, Nutrients, Ingredient, PlannedMeal
from tamaq.contracts import AnalysisError
from tamaq.provider import OpenAIProvider, MODEL
from tamaq.dev_fake import FakeProvider

class PlanProviderTest(unittest.TestCase):
    def test_strict_schema_bounded_output_usage_and_no_network(self):
        parameters={'meals':3,'times':['08:00','13:00','19:00'],'targets':{'calories':2400,'protein':120,'fat':80,'carbs':300},'preferences':'test','exclusions':[],'allergies':[],'budget':'','cooking_minutes':30,'language':'ru'}
        result=FakeProvider().plan(parameters).result
        response=SimpleNamespace(usage=SimpleNamespace(input_tokens=100,output_tokens=200,input_tokens_details=SimpleNamespace(cached_tokens=0)),service_tier='default',model=MODEL,status='completed',output=[],output_text=result.model_dump_json(),id='test-response')
        provider=OpenAIProvider('fake-test-api-key',MODEL)
        with patch.object(provider.client.responses,'create',return_value=response) as call:
            output=provider.plan(parameters)
        self.assertEqual(110,output.cost_micro_usd);self.assertEqual(200,output.usage['output_tokens'])
        args=call.call_args.kwargs
        self.assertEqual(4000,args['max_output_tokens']);self.assertFalse(args['store']);self.assertTrue(args['text']['format']['strict'])
        schema=args['text']['format']['schema']
        self.assertFalse(schema['additionalProperties']);self.assertEqual(set(schema['properties']),set(schema['required']))
    def test_preflight_size_reject_is_zero_charge(self):
        provider=OpenAIProvider('fake-test-api-key',MODEL)
        with self.assertRaises(AnalysisError) as e: provider.plan({'preferences':'x'*9000})
        self.assertEqual(0,e.exception.cost_micro_usd)

class CalculatorTest(unittest.TestCase):
    def test_reference_and_diary(self):
        q=Questionnaire(goal='maintain',age=30,height_cm=175,weight_kg=70,formula_sex='male',activity='light')
        self.assertEqual({'calories':2638,'protein':132,'fat':88,'carbs':330},calculate(q)['targets'])
        self.assertEqual(1648.75,calculate(q)['resting_kcal'])
        self.assertIsNone(calculate(Questionnaire())['targets']['calories'])
    def test_applicability_and_finite_validation(self):
        for fields in ({'age':17},{'age':79},{'special_condition':True},{'weight_kg':40},{'formula_sex':'unspecified'}):
            with self.assertRaises(AnalysisError): calculate(Questionnaire(goal='lose',age=30,height_cm=175,weight_kg=70,formula_sex='male').model_copy(update=fields))
        with self.assertRaises(ValidationError): Questionnaire(weight_kg=float('nan'))
        with self.assertRaises(ValidationError): GoalWrite(effective_date=date.today()-timedelta(days=1),targets={})
    def test_unknown_and_arithmetic(self):
        meal=PlannedMeal(id='m',time='12:00',dish='Test',ingredients=[Ingredient(id='i',name='Test',quantity=100,unit='g',basis_quantity=100,nutrition=None)],total=None)
        PlanContent(meals=[meal],total=None).validate_semantics()
        with self.assertRaises(ValueError): PlanContent(meals=[meal],total=None).validate_semantics(ai=True)
        meal=meal.model_copy(update={'total':Nutrients(calories=1,protein=0,fat=0,carbs=0)})
        with self.assertRaises(ValueError): PlanContent(meals=[meal],total=None).validate_semantics()

class NutritionPlanAccountsTest(accounts.AccountsTest):
    def setUp(self):
        super().setUp();self.plan_calls=0;self.parameters=None
        original=self.provider.plan
        def counted(params): self.plan_calls+=1;self.parameters=params;return original(params)
        self.provider.plan=counted
    def setup_targets(self):
        token=self.account();h=self.headers(token)
        q=self.client.get('/v1/nutrition/questionnaire',headers=h).json()
        self.assertFalse(q['document']['completed'])
        q['document'].update(completed=True,step=3,goal='maintain',age=30,height_cm=175,weight_kg=70,formula_sex='male')
        self.assertEqual(200,self.client.put('/v1/nutrition/questionnaire',json=q,headers=h).status_code)
        q=self.client.get('/v1/nutrition/questionnaire',headers=h).json()
        targets=calculate(Questionnaire.model_validate(q['document']))['targets']
        response=self.client.put('/v1/nutrition/goals',headers=h,json={'effective_date':str(date.today()),'origin':'calculated','targets':targets,'fitness_version':q['version']})
        self.assertEqual(200,response.status_code,response.text)
        self.client.post('/v1/auth/link-guest',headers=h,json={'guest_token':self.guest(),'operation':str(uuid4())})
        return token,q
    def generate(self,token,q,key):
        return self.client.post('/v1/plans/generate',headers={**self.headers(token),'Idempotency-Key':key},json={'date':str(date.today()),'goal_date':str(date.today()),'fitness_version':q['version'],'language':'ru'})
    def test_questionnaire_resume_conflict_and_two_owners(self):
        a=self.account();h=self.headers(a);q=self.client.get('/v1/nutrition/questionnaire',headers=h).json()
        q['document'].update(step=1,goal='diary')
        self.assertEqual(200,self.client.put('/v1/nutrition/questionnaire',headers=h,json=q).status_code)
        self.assertEqual(409,self.client.put('/v1/nutrition/questionnaire',headers=h,json=q).status_code)
        self.assertEqual(1,self.client.get('/v1/nutrition/questionnaire',headers=h).json()['document']['step'])
        b=self.account('second@example.com')
        self.assertEqual(0,self.client.get('/v1/nutrition/questionnaire',headers=self.headers(b)).json()['document']['step'])
    def test_goal_history_and_calculated_proof(self):
        token,q=self.setup_targets();h=self.headers(token)
        original=self.client.get('/v1/nutrition/goals',headers=h).json()['history'][0]
        future=str(date.today()+timedelta(days=1))
        self.assertEqual(200,self.client.put('/v1/nutrition/goals',headers=h,json={'effective_date':future,'targets':{'calories':2100,'protein':80,'fat':60,'carbs':250}}).status_code)
        self.assertEqual(original,self.client.get('/v1/nutrition/goals',headers=h).json()['history'][0])
        self.assertEqual(422,self.client.put('/v1/nutrition/goals',headers=h,json={'effective_date':future,'origin':'calculated','fitness_version':q['version'],'targets':{'calories':1}}).status_code)
    def test_plan_same_ledger_parallel_repeat_and_no_pii(self):
        token,q=self.setup_targets();key=str(uuid4())
        result=self.generate(token,q,key);self.assertEqual(200,result.status_code,result.text)
        with ThreadPoolExecutor(2) as pool: retries=list(pool.map(lambda _:self.generate(token,q,key),range(2)))
        self.assertTrue(all(r.status_code==200 and r.json()==result.json() for r in retries));self.assertEqual(1,self.plan_calls)
        self.assertTrue(set(self.parameters)=={'meals','times','preferences','exclusions','allergies','budget','cooking_minutes','targets','language'})
        with self.database.connection() as db:
            row=db.execute('SELECT charged,reserved FROM requests WHERE key=%s',(key,)).fetchone();self.assertEqual({'charged':0,'reserved':0},dict(row))
            self.assertEqual(1,db.execute('SELECT COUNT(*) AS n FROM meal_plans').fetchone()['n'])
    def test_manual_plan_owner_conflict_and_mutation_replay(self):
        token,q=self.setup_targets();generated=self.generate(token,q,str(uuid4())).json();pid=generated['id']
        body={'version':1,'operation':str(uuid4()),'document':generated['document']}
        body['document']['content']['meals'][0]['dish']='Manual replacement'
        a=self.client.put('/v1/plans/'+pid,headers=self.headers(token),json=body)
        self.assertEqual(200,a.status_code,a.text)
        self.assertEqual(a.json(),self.client.put('/v1/plans/'+pid,headers=self.headers(token),json=body).json())
        body['operation']=str(uuid4());self.assertEqual(409,self.client.put('/v1/plans/'+pid,headers=self.headers(token),json=body).status_code)
        b=self.account('other@example.com');self.assertEqual(404,self.client.get('/v1/plans/'+pid,headers=self.headers(b)).status_code)
        self.assertEqual(404,self.client.put('/v1/plans/'+pid,headers=self.headers(b),json=body).status_code)
    def test_restriction_reject_charged_once_and_budget_no_new_grant(self):
        token,q=self.setup_targets();q['document']['allergies']=['fixture']
        r=self.client.put('/v1/nutrition/questionnaire',headers=self.headers(token),json=q);q=r.json()
        key=str(uuid4());self.assertEqual(502,self.generate(token,q,key).status_code)
        self.assertEqual(502,self.generate(token,q,key).status_code);self.assertEqual(1,self.plan_calls)
        with self.database.connection() as db: db.execute('UPDATE budget SET verification_cap=1')
        self.assertEqual(429,self.generate(token,q,str(uuid4())).status_code)
        self.assertEqual(1,self.plan_calls)
    def test_plan_unknown_outcome_keeps_reservation(self):
        token,q=self.setup_targets();key=str(uuid4())
        with patch.object(self.provider,'plan',side_effect=AnalysisError('provider_outcome_unknown',504)):
            self.assertEqual(504,self.generate(token,q,key).status_code)
            self.assertEqual(504,self.generate(token,q,key).status_code)
        with self.database.connection() as db: self.assertEqual(300000,db.execute('SELECT reserved FROM requests WHERE key=%s',(key,)).fetchone()['reserved'])
