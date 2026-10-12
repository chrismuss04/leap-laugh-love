import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest

from reporting_etl.transform import InvalidEvent, to_order_row
from tests.events import encode, filled_event, rejected_event


def test_maps_a_filled_event_to_a_row():
    event = filled_event()

    row = to_order_row(encode(event))

    assert row.order_id == uuid.UUID(event["orderId"])
    assert row.account_id == uuid.UUID(event["accountId"])
    assert row.client_id == uuid.UUID(event["clientId"])
    assert row.instrument_id == uuid.UUID(event["instrumentId"])
    assert row.symbol == "AAPL"
    assert row.side == "BUY"
    assert row.quantity == 10
    assert row.status == "FILLED"
    assert row.fill_price == Decimal("187.42")
    assert row.rejection_reason is None
    assert row.submitted_at == datetime(2026, 10, 5, 14, 32, 7, 118000, tzinfo=timezone.utc)
    assert row.completed_at == datetime(2026, 10, 5, 14, 32, 7, 402000, tzinfo=timezone.utc)


def test_keeps_the_price_exact():
    row = to_order_row(b'{"orderId": "%s", "accountId": "%s", "clientId": "%s", "instrumentId": "%s",'
                       b' "symbol": "MSFT", "side": "SELL", "quantity": 3, "status": "FILLED",'
                       b' "fillPrice": 0.1000, "submittedAt": "2026-10-05T14:32:07Z",'
                       b' "completedAt": "2026-10-05T14:32:08Z"}'
                       % tuple(str(uuid.uuid4()).encode() for _ in range(4)))

    assert row.fill_price == Decimal("0.1000")
    assert isinstance(row.fill_price, Decimal)


def test_accepts_a_whole_number_price():
    row = to_order_row(encode(filled_event(fillPrice=250)))

    assert row.fill_price == Decimal(250)


def test_maps_a_rejected_event_without_a_price():
    row = to_order_row(encode(rejected_event()))

    assert row.status == "REJECTED"
    assert row.fill_price is None
    assert row.rejection_reason == "Settlement failed: insufficient position quantity"


def test_ignores_a_price_on_a_rejected_event():
    row = to_order_row(encode(rejected_event(fillPrice=12.5)))

    assert row.fill_price is None


def test_reads_java_offsets_and_nanosecond_precision():
    # Jackson writes OffsetDateTime with the offset it was created in and up to 9 fraction digits.
    row = to_order_row(encode(filled_event(
        submittedAt="2026-10-05T10:32:07.118123456-04:00",
        completedAt="2026-10-05T14:32:08+00:00",
    )))

    assert row.submitted_at.utcoffset() == timedelta(hours=-4)
    assert row.submitted_at.astimezone(timezone.utc).hour == 14
    assert row.submitted_at.microsecond == 118123


def test_normalises_the_symbol():
    row = to_order_row(encode(filled_event(symbol=" aapl ")))

    assert row.symbol == "AAPL"


def test_ignores_fields_it_does_not_store():
    row = to_order_row(encode(filled_event(somethingNew="later field")))

    assert row.symbol == "AAPL"


@pytest.mark.parametrize("payload", [None, b"", b"not json", b"[1, 2]", b"\xff\xfe"])
def test_rejects_a_payload_that_is_not_an_event_object(payload):
    with pytest.raises(InvalidEvent):
        to_order_row(payload)


@pytest.mark.parametrize("field", [
    "orderId", "accountId", "clientId", "instrumentId", "symbol", "side", "quantity", "status",
    "submittedAt", "completedAt",
])
def test_rejects_an_event_missing_a_required_field(field):
    event = filled_event()
    del event[field]

    with pytest.raises(InvalidEvent, match=field):
        to_order_row(encode(event))


def test_rejects_a_filled_event_without_a_price():
    with pytest.raises(InvalidEvent, match="fillPrice"):
        to_order_row(encode(filled_event(fillPrice=None)))


@pytest.mark.parametrize("overrides, field", [
    ({"orderId": "not-a-uuid"}, "orderId"),
    ({"side": "HOLD"}, "side"),
    ({"status": "ACCEPTED"}, "status"),
    ({"status": "SUBMITTED"}, "status"),
    ({"quantity": 0}, "quantity"),
    ({"quantity": -5}, "quantity"),
    ({"quantity": 2.5}, "quantity"),
    ({"quantity": True}, "quantity"),
    ({"quantity": "10"}, "quantity"),
    ({"fillPrice": 0}, "fillPrice"),
    ({"fillPrice": -1.5}, "fillPrice"),
    ({"fillPrice": "187.42"}, "fillPrice"),
    ({"symbol": "  "}, "symbol"),
    ({"symbol": 42}, "symbol"),
    ({"rejectionReason": 42}, "rejectionReason"),
    ({"submittedAt": "yesterday"}, "submittedAt"),
    ({"submittedAt": 1728138727}, "submittedAt"),
    ({"completedAt": "2026-10-05T14:32:07"}, "completedAt"),
])
def test_rejects_an_event_with_a_bad_value(overrides, field):
    with pytest.raises(InvalidEvent, match=field):
        to_order_row(encode(filled_event(**overrides)))
