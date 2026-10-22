from math import isfinite
from typing import Literal

from pydantic import BaseModel, ConfigDict


class Component(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    name: str
    mass_grams: float | None
    calories: float
    protein: float
    fat: float
    carbs: float


class FoodResult(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    status: Literal["food", "no_food", "ambiguous"]
    name: str
    components: list[Component]
    explanation: str | None
    question: str | None

    def validate_semantics(self) -> "FoodResult":
        if not self.name.strip() or len(self.name) > 120 or len(self.components) > 20:
            raise ValueError("invalid_name_or_components")
        for text in (self.explanation, self.question):
            if text is not None and (not text.strip() or len(text) > 600):
                raise ValueError("invalid_explanation")
        if self.status == "food" and not self.components:
            raise ValueError("empty_food")
        if self.status == "no_food" and self.components:
            raise ValueError("no_food_has_components")
        if self.status == "ambiguous" and not self.question:
            raise ValueError("ambiguity_needs_question")
        for c in self.components:
            if not c.name.strip() or len(c.name) > 120:
                raise ValueError("invalid_component_name")
            values = (c.calories, c.protein, c.fat, c.carbs)
            if any(not isfinite(v) or v < 0 for v in values):
                raise ValueError("invalid_nutrients")
            if c.calories > 10000 or max(c.protein, c.fat, c.carbs) > 1000:
                raise ValueError("excessive_nutrients")
            if c.mass_grams is not None and (not isfinite(c.mass_grams) or not 0 < c.mass_grams <= 10000):
                raise ValueError("invalid_mass")
        if sum(c.mass_grams or 0 for c in self.components) > 10000:
            raise ValueError("excessive_total_mass")
        if sum(c.calories for c in self.components) > 10000 or any(
            sum(getattr(c, nutrient) for c in self.components) > 1000
            for nutrient in ("protein", "fat", "carbs")
        ):
            raise ValueError("excessive_total")
        return self


class AnalysisResponse(BaseModel):
    analysis_id: str
    schema_version: str
    prompt_version: str
    model: str
    is_demo: bool
    result: FoodResult


class AnalysisError(Exception):
    def __init__(self, code: str, status_code: int = 422):
        self.code = code
        self.status_code = status_code
        super().__init__(code)
