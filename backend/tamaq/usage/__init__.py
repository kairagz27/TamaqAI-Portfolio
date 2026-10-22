"""Usage and financial accounting. Identifiers are server authenticated, never client UIDs."""
from dataclasses import dataclass

@dataclass(frozen=True)
class Actor:
    principal: str
    user_id: str | None = None
    session_id: str | None = None
    guest_hash: str | None = None
