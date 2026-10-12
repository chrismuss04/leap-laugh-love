"""
Turns one order-events message into a reporting.orders row.

Pure functions with no Kafka or database, so the mapping is tested on its own. Anything that
doesn't match the event contract raises InvalidEvent: the consumer skips such a message rather
than retrying it, since the same bytes will never parse differently.
"""

import json
import uuid
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Any, Optional

SIDES = frozenset({"BUY", "SELL"})
STATUSES = frozenset({"FILLED", "REJECTED"})


class InvalidEvent(ValueError):
    """The message is not a usable order event."""


@dataclass(frozen=True)
class OrderRow:
    order_id: uuid.UUID
    account_id: uuid.UUID
    client_id: uuid.UUID
    instrument_id: uuid.UUID
    symbol: str
    side: str
    quantity: int
    status: str
    fill_price: Optional[Decimal]
    rejection_reason: Optional[str]
    submitted_at: datetime
    completed_at: datetime


def to_order_row(payload: bytes | str | None) -> OrderRow:
    """
    Parses and validates an order event.
    :param payload: the Kafka message value, JSON as published by order-app's OrderEventPublisher
    :return: the row to load
    :raises InvalidEvent: if the payload is not valid JSON or breaks the event contract
    """
    if payload is None:
        raise InvalidEvent("message has no value")
    try:
        # Prices as Decimal, never float, so 187.42 is stored as 187.42.
        event = json.loads(payload, parse_float=Decimal)
    except (UnicodeDecodeError, json.JSONDecodeError) as ex:
        raise InvalidEvent(f"not valid JSON: {ex}") from ex
    if not isinstance(event, dict):
        raise InvalidEvent("event is not a JSON object")

    status = _one_of(event, "status", STATUSES)
    fill_price = _price(event, "fillPrice") if status == "FILLED" else None

    return OrderRow(
        order_id=_uuid(event, "orderId"),
        account_id=_uuid(event, "accountId"),
        client_id=_uuid(event, "clientId"),
        instrument_id=_uuid(event, "instrumentId"),
        symbol=_text(event, "symbol").upper(),
        side=_one_of(event, "side", SIDES),
        quantity=_positive_int(event, "quantity"),
        status=status,
        fill_price=fill_price,
        rejection_reason=_optional_text(event, "rejectionReason"),
        submitted_at=_timestamp(event, "submittedAt"),
        completed_at=_timestamp(event, "completedAt"),
    )


def _required(event: dict, field: str) -> Any:
    value = event.get(field)
    if value is None:
        raise InvalidEvent(f"{field} is missing")
    return value


def _uuid(event: dict, field: str) -> uuid.UUID:
    value = _required(event, field)
    try:
        return uuid.UUID(str(value))
    except ValueError as ex:
        raise InvalidEvent(f"{field} is not a UUID: {value!r}") from ex


def _text(event: dict, field: str) -> str:
    value = _required(event, field)
    if not isinstance(value, str) or not value.strip():
        raise InvalidEvent(f"{field} must be a non-empty string")
    return value.strip()


def _optional_text(event: dict, field: str) -> Optional[str]:
    value = event.get(field)
    if value is None:
        return None
    if not isinstance(value, str):
        raise InvalidEvent(f"{field} must be a string")
    return value


def _one_of(event: dict, field: str, allowed: frozenset[str]) -> str:
    value = _required(event, field)
    if value not in allowed:
        raise InvalidEvent(f"{field} must be one of {sorted(allowed)}, got {value!r}")
    return value


def _positive_int(event: dict, field: str) -> int:
    value = _required(event, field)
    # bool is an int subclass; true is not a quantity.
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise InvalidEvent(f"{field} must be a positive whole number, got {value!r}")
    return value


def _price(event: dict, field: str) -> Decimal:
    value = _required(event, field)
    # A whole-number price arrives as a JSON int, not a Decimal.
    if isinstance(value, bool) or not isinstance(value, (int, Decimal)) or value <= 0:
        raise InvalidEvent(f"{field} must be a positive number, got {value!r}")
    return Decimal(value)


def _timestamp(event: dict, field: str) -> datetime:
    value = _required(event, field)
    if not isinstance(value, str):
        raise InvalidEvent(f"{field} must be an ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as ex:
        raise InvalidEvent(f"{field} is not an ISO-8601 timestamp: {value!r}") from ex
    if parsed.tzinfo is None:
        # The columns are TIMESTAMPTZ; a bare local time would be read in the DB session's zone.
        raise InvalidEvent(f"{field} has no UTC offset: {value!r}")
    return parsed
