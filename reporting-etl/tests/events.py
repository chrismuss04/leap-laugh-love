"""Builds order events shaped like the ones order-app publishes."""

import json
import uuid


def filled_event(**overrides) -> dict:
    event = {
        "eventId": str(uuid.uuid4()),
        "orderId": str(uuid.uuid4()),
        "accountId": str(uuid.uuid4()),
        "clientId": str(uuid.uuid4()),
        "instrumentId": str(uuid.uuid4()),
        "symbol": "AAPL",
        "side": "BUY",
        "quantity": 10,
        "status": "FILLED",
        "fillPrice": 187.4200,
        "rejectionReason": None,
        "submittedAt": "2026-10-05T14:32:07.118Z",
        "completedAt": "2026-10-05T14:32:07.402Z",
    }
    event.update(overrides)
    return event


def rejected_event(**overrides) -> dict:
    event = filled_event(
        status="REJECTED",
        fillPrice=None,
        rejectionReason="Settlement failed: insufficient position quantity",
        side="SELL",
    )
    event.update(overrides)
    return event


def encode(event: dict) -> bytes:
    return json.dumps(event).encode("utf-8")
