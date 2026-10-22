import json
import os
from decimal import Decimal


def money(name, default):
    amount = Decimal(os.environ.get(name, default))
    if not amount.is_finite() or amount <= 0 or amount * 1_000_000 != (amount * 1_000_000).to_integral_value():
        raise ValueError(f"Invalid {name}")
    return int(amount * 1_000_000)


def invitations(environment, explicit=None):
    if explicit is not None:
        return [explicit]  # dependency injection in tests
    if environment == "development" and os.environ.get("PILOT_INVITE_TOKEN"):
        return [os.environ["PILOT_INVITE_TOKEN"]]
    try:
        values = json.loads(os.environ.get("PILOT_INVITE_TOKENS", "[]"))
    except ValueError:
        raise ValueError("PILOT_INVITE_TOKENS must be a JSON array") from None
    if not isinstance(values, list) or not 1 <= len(values) <= 20 or any(
        not isinstance(v, str) or len(v) < 32 or len(v) > 256 for v in values
    ) or len(set(values)) != len(values):
        raise ValueError("Configure 1..20 distinct random pilot invitations (32+ characters)")
    return values
