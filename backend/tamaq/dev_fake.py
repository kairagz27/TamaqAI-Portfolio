"""Explicit development fixture, never an inference result."""
from .contracts import Component, FoodResult
from .provider import ProviderOutput


class FakeProvider:
    model = "FAKE-DEVELOPMENT"
    is_demo = True

    def analyze(self, jpeg: bytes):
        return ProviderOutput(FoodResult(status="food", name="ДЕМО: тестовая порция",
            components=[Component(name="ДЕМО компонент", mass_grams=100.0,
                calories=120.0, protein=5.0, fat=4.0, carbs=16.0)],
            explanation="Искусственные данные для проверки интерфейса; фото не анализировалось.",
            question=None), 0)

    def plan(self, parameters):
        from .plans.models import PlanContent, PlannedMeal, Ingredient, Nutrients
        count=parameters['meals']; targets=parameters['targets']
        nutrients=Nutrients(**{k:(targets[k] or 0)/count for k in ('calories','protein','fat','carbs')})
        meals=[PlannedMeal(id=f'm{i}',time=parameters['times'][i],dish='DEMO: editable meal',ingredients=[Ingredient(id='i0',name='DEMO fixture ingredient',quantity=100,unit='g',basis_quantity=100,nutrition=nutrients)],total=nutrients) for i in range(count)]
        result=PlanContent(meals=meals,total=Nutrients(**{k:(targets[k] or 0) for k in ('calories','protein','fat','carbs')}))
        return ProviderOutput(result,0,{'input_tokens':0,'output_tokens':0,'pricing_version':'fake-no-charge'})
