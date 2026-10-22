from datetime import date, datetime
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from pydantic import BaseModel, ConfigDict, Field, model_validator

class Input(BaseModel):
    model_config=ConfigDict(extra='forbid',allow_inf_nan=False)

class Questionnaire(Input):
    step: int=Field(default=0,ge=0,le=3)
    completed: bool=False
    goal: Literal['diary','maintain','lose','gain']='diary'
    age: int | None=Field(default=None,ge=1,le=120)
    height_cm: float | None=Field(default=None,ge=50,le=250)
    weight_kg: float | None=Field(default=None,ge=20,le=500)
    formula_sex: Literal['male','female','unspecified']='unspecified'
    activity: Literal['sedentary','light','moderate','active']='light'
    special_condition: bool=False
    meals: int=Field(default=3,ge=1,le=6)
    times: list[str]=Field(default_factory=lambda:['08:00','13:00','19:00'],max_length=6)
    preferences: str=Field(default='',max_length=400)
    exclusions: list[str]=Field(default_factory=list,max_length=20)
    allergies: list[str]=Field(default_factory=list,max_length=20)
    budget: str=Field(default='',max_length=100)
    cooking_minutes: int=Field(default=30,ge=5,le=240)

    @model_validator(mode='after')
    def valid(self):
        import re
        if any(not re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d',x) for x in self.times): raise ValueError('invalid_time')
        if any(not x.strip() or len(x)>80 for x in self.exclusions+self.allergies): raise ValueError('invalid_restriction')
        if self.completed and self.goal!='diary' and None in (self.age,self.height_cm,self.weight_kg):
            raise ValueError('missing_calculation_fields')
        if self.completed and len(self.times)!=self.meals: raise ValueError('time_count')
        return self

class FitnessWrite(Input):
    version: int=Field(ge=0)
    document: Questionnaire

class Targets(Input):
    calories: int | None=Field(default=None,ge=1,le=10000)
    protein: int | None=Field(default=None,ge=1,le=1000)
    fat: int | None=Field(default=None,ge=1,le=1000)
    carbs: int | None=Field(default=None,ge=1,le=1000)

class GoalWrite(Input):
    effective_date: date
    timezone: str=Field(default='UTC',max_length=80)
    origin: Literal['manual','calculated']='manual'
    targets: Targets
    fitness_version: int | None=None
    @model_validator(mode='after')
    def valid(self):
        try: today=datetime.now(ZoneInfo(self.timezone)).date()
        except (ZoneInfoNotFoundError,ValueError): raise ValueError('invalid_timezone') from None
        if self.effective_date<today: raise ValueError('historical_goal_immutable')
        if (self.effective_date-today).days>366: raise ValueError('date_too_far')
        return self
