from ..contracts import AnalysisError
from .models import Questionnaire, Targets

ACTIVITY={'sedentary':1.4,'light':1.6,'moderate':1.8,'active':2.0}
VERSION='mifflin-1990-pal-v1'

def calculate(q: Questionnaire) -> dict:
    if q.goal=='diary': return {'targets':Targets().model_dump(),'formula':VERSION,'resting_kcal':None,'activity_factor':None}
    if q.age is None or q.height_cm is None or q.weight_kg is None or q.formula_sex=='unspecified':
        raise AnalysisError('calculation_fields_required',422)
    # The original sample was healthy adults 19–78. Product guards are not
    # medical thresholds or a promise that a calculated target is safe.
    bmi=q.weight_kg/(q.height_cm/100)**2
    if not 19<=q.age<=78 or q.special_condition or not 18.5<=bmi<=40:
        raise AnalysisError('automatic_calculation_unsuitable',422)
    resting=10*q.weight_kg+6.25*q.height_cm-5*q.age+(5 if q.formula_sex=='male' else -161)
    maintenance=resting*ACTIVITY[q.activity]
    adjustment=min(300,maintenance*.1)
    calories=int(maintenance+(-adjustment if q.goal=='lose' else adjustment if q.goal=='gain' else 0)+.5)
    if not 1500<=calories<=4500: raise AnalysisError('automatic_calculation_unsuitable',422)
    targets=Targets(calories=calories,protein=int(calories*.20/4+.5),fat=int(calories*.30/9+.5),carbs=int(calories*.50/4+.5))
    return {'targets':targets.model_dump(),'formula':VERSION,'resting_kcal':round(resting,2),'activity_factor':ACTIVITY[q.activity]}
