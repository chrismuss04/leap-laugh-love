"""Registration contract and database loader for the client-register topic."""

import json
from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from .consumer import EventsConsumer
from .load import RowLoader
from .transform import InvalidEvent, _timestamp, _uuid


@dataclass(frozen=True)
class ClientRow:
    client_id: UUID
    registered_at: datetime


def to_client_row(payload: bytes | str | None) -> ClientRow:
    """Validate the client ID and offset-aware registration timestamp."""
    if payload is None:
        raise InvalidEvent("message has no value")
    try:
        event = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as ex:
        raise InvalidEvent("not valid JSON") from ex
    if not isinstance(event, dict):
        raise InvalidEvent("event is not a JSON object")
    return ClientRow(_uuid(event, "clientId"), _timestamp(event, "registered_at"))


class ClientLoader(RowLoader):
    """Reuse connection recovery; duplicate delivery preserves the original registration row."""
    statement = """
        INSERT INTO reporting.clients (client_id, registered_at)
        VALUES (%(client_id)s, %(registered_at)s)
        ON CONFLICT (client_id) DO NOTHING
    """


class ClientEventsConsumer(EventsConsumer):
    """Consume registration events with the same commit/retry policy as order events."""
    transform = staticmethod(to_client_row)
