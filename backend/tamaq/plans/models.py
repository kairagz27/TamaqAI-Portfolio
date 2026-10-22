from datetime import date
from typing import Literal
from uuid import UUID
from pydantic import Field, model_validator
from ..nutrition.models import Input

class Nutrients(Input):
    calories: float=Field(ge=0,le=10000)
    protein: float=Field(ge=0,le=1000)
    fat: float=Field(ge=0,le=1000)
    carbs: float=Field(ge=0,le=1000)
    def scaled(self,factor): return Nutrients(**{k:v*factor for k,v in self.model_dump().items()})

class Ingredient(Input):
    id: str=Field(min_length=1,max_length=80)
    name: str=Field(min_length=1,max_length=120)
    quantity: float=Field(gt=0,le=10000)
    unit: Literal['g','ml','piece']
    basis_quantity: float=Field(gt=0,le=10000)
    nutrition: Nutrients | None

class PlannedMeal(Input):
    id: str=Field(min_length=1,max_length=80)
    time: str=Field(pattern=r'^(?:[01]\d|2[0-3]):[0-5]\d$')
    dish: str=Field(min_length=1,max_length=120)
    ingredients: list[Ingredient]=Field(min_length=1,max_length=12)
    total: Nutrients | None

def sum_nutrition(ingredients):
    if any(i.nutrition is None for i in ingredients): return None
    values=[i.nutrition.scaled(i.quantity/i.basis_quantity) for i in ingredients]
    return Nutrients(**{k:round(sum(getattr(v,k) for v in values),4) for k in ('calories','protein','fat','carbs')})

class PlanContent(Input):
    meals: list[PlannedMeal]=Field(max_length=6)
    total: Nutrients | None
    def validate_semantics(self,ai=False):
        if len({m.id for m in self.meals})!=len(self.meals): raise ValueError('duplicate_meal')
        for m in self.meals:
            if not m.dish.strip() or len({i.id for i in m.ingredients})!=len(m.ingredients): raise ValueError('invalid_meal')
            if any(not i.name.strip() for i in m.ingredients): raise ValueError('invalid_ingredient')
            actual=sum_nutrition(m.ingredients)
            if (actual is None)!=(m.total is None): raise ValueError('unknown_nutrition')
            if actual and any(abs(getattr(actual,k)-getattr(m.total,k))>.5 for k in actual.model_dump()): raise ValueError('meal_total_mismatch')
            if ai:
                if actual is None or any(i.basis_quantity!=i.quantity for i in m.ingredients): raise ValueError('ai_unknown_nutrition')
                energy=4*actual.protein+9*actual.fat+4*actual.carbs
                if abs(energy-actual.calories)>max(50,actual.calories*.2): raise ValueError('energy_mismatch')
        actual=None if any(m.total is None for m in self.meals) else Nutrients(**{k:round(sum(getattr(m.total,k) for m in self.meals),4) for k in ('calories','protein','fat','carbs')})
        if (actual is None)!=(self.total is None): raise ValueError('unknown_total')
        if actual and any(abs(getattr(actual,k)-getattr(self.total,k))>.5 for k in actual.model_dump()): raise ValueError('day_total_mismatch')
        return self

class PlanDocument(Input):
    date: date
    source: Literal['manual','ai']
    is_demo: bool=False
    content: PlanContent
    @model_validator(mode='after')
    def valid(self): self.content.validate_semantics();return self

class PlanWrite(Input):
    version: int=Field(ge=0)
    operation: UUID
    document: PlanDocument

class Generate(Input):
    date: date
    fitness_version: int=Field(ge=1)
    goal_date: date
    language: Literal['ru','kk','en']
