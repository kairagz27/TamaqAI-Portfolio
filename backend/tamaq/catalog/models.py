"""Catalogue contracts keep unknown nutrition and unknown mass explicitly nullable."""
from datetime import date
from typing import Literal
import re
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class Nutrients(Input):
    calories: float | None = Field(default=None, ge=0, le=10000)
    protein: float | None = Field(default=None, ge=0, le=1000)
    fat: float | None = Field(default=None, ge=0, le=1000)
    carbs: float | None = Field(default=None, ge=0, le=1000)

    @property
    def complete(self):
        return all(value is not None for value in self.model_dump().values())


class Source(Input):
    url: str = Field(max_length=2000)
    checked_at: date
    external_id: str = Field(min_length=1, max_length=200)
    license: str = Field(min_length=1, max_length=1000)
    status: Literal["official", "open", "user", "estimate"]
    namespace: str = Field(pattern=r"^[a-z0-9][a-z0-9_-]{0,63}$")

    @field_validator("external_id", "license")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("source_metadata_required")
        return value.strip()

    @model_validator(mode="after")
    def provenance(self):
        if self.url:
            parsed = urlsplit(self.url)
            if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
                raise ValueError("source_https_url_required")
        elif self.status in ("official", "open"):
            raise ValueError("source_url_required")
        if self.checked_at > date.today():
            raise ValueError("source_date_in_future")
        return self


class EntryInput(Input):
    id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9_:.-]{0,159}$")
    kind: Literal["product", "dish"]
    name: str = Field(min_length=1, max_length=120)
    aliases: list[str] = Field(default_factory=list, max_length=30)
    brand: str | None = Field(default=None, max_length=120)
    barcode: str | None = Field(default=None, max_length=14)
    country_of_sale: list[str] = Field(default_factory=list, max_length=30)
    country_of_manufacture: list[str] = Field(default_factory=list, max_length=30)
    city: str | None = Field(default=None, max_length=100)
    chain: str | None = Field(default=None, max_length=120)
    branch_scope: str | None = Field(default=None, max_length=300)
    variant: str | None = Field(default=None, max_length=100)
    basis: Literal["per_100g", "per_100ml", "per_portion", "unknown"]
    portion_grams: float | None = Field(default=None, gt=0, le=10000)
    portion_ml: float | None = Field(default=None, gt=0, le=10000)
    nutrients: Nutrients
    source: Source
    addon_ids: list[str] = Field(default_factory=list, max_length=30)

    @field_validator("name")
    @classmethod
    def name_not_blank(cls, value):
        if not value.strip():
            raise ValueError("name_required")
        return value.strip()

    @field_validator("aliases", "addon_ids")
    @classmethod
    def bounded_strings(cls, values):
        if any(not value.strip() or len(value) > 200 for value in values):
            raise ValueError("invalid_alias_or_addon")
        return list(dict.fromkeys(value.strip() for value in values))

    @field_validator("country_of_sale", "country_of_manufacture")
    @classmethod
    def countries(cls, values):
        if any(not re.fullmatch(r"[A-Z]{2}", value) for value in values):
            raise ValueError("country_requires_iso_alpha2")
        return list(dict.fromkeys(values))

    @field_validator("barcode")
    @classmethod
    def barcode_checksum(cls, value):
        if value is None:
            return value
        if not value.isascii() or not value.isdigit() or len(value) not in (8, 12, 13, 14):
            raise ValueError("invalid_gtin")
        digits = [int(digit) for digit in value]
        checksum = sum(digit * (3 if index % 2 == 0 else 1)
                       for index, digit in enumerate(reversed(digits[:-1])))
        if (10 - checksum % 10) % 10 != digits[-1]:
            raise ValueError("invalid_gtin_checksum")
        return value

    @model_validator(mode="after")
    def semantics(self):
        if self.kind == "dish" and (not self.chain or not self.branch_scope):
            raise ValueError("dish_menu_applicability_required")
        if self.basis == "unknown" and any(v is not None for v in self.nutrients.model_dump().values()):
            raise ValueError("unknown_basis_requires_review")
        if self.basis in ("per_100g", "per_100ml"):
            maximum = 100 if self.basis == "per_100g" else 150
            if any(v is not None and v > maximum for key, v in self.nutrients.model_dump().items()
                   if key != "calories"):
                raise ValueError("suspicious_macronutrients_requires_review")
            macro_sum = sum(value for key, value in self.nutrients.model_dump().items()
                            if key != "calories" and value is not None)
            if macro_sum > (105 if self.basis == "per_100g" else 150):
                raise ValueError("suspicious_macronutrient_sum_requires_review")
            if self.nutrients.calories is not None and self.nutrients.calories > 1500:
                raise ValueError("suspicious_energy_requires_review")
        if self.id in self.addon_ids:
            raise ValueError("self_addon")
        return self


class Entry(EntryInput):
    version_id: str
    revision: int


class Selection(Input):
    version_id: str = Field(pattern=r"^[0-9a-f]{64}$")
    quantity: float = Field(gt=0, le=10000)
    unit: Literal["g", "ml", "portion"]


class Calculation(Selection):
    addons: list[Selection] = Field(default_factory=list, max_length=10)


class Match(Input):
    names: list[str] = Field(default_factory=list, max_length=20)
    barcode: str | None = Field(default=None, max_length=14)
    city: str | None = Field(default=None, max_length=100)
    chain: str | None = Field(default=None, max_length=120)
    branch_scope: str | None = Field(default=None, max_length=300)

    @field_validator("names")
    @classmethod
    def bounded_names(cls, values):
        if any(len(value) > 200 for value in values):
            raise ValueError("name_too_long")
        return values
