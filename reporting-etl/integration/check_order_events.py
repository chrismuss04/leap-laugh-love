"""
End-to-end check of order events against a running compose stack (see scripts/test-order-events.sh).

Places real orders through order-app and checks what reaches the order-events topic:

1. A filled BUY publishes one event, keyed by its orderId, matching the order-app response; the
   ETL's own OrderEventsConsumer turns that message into the right OrderRow and commits it.
2. An order rejected by validation publishes nothing.

Runs inside the compose network, since Kafka has no host port. The database is not checked: the
row is handed to a recording loader instead of Postgres. Reads its own consumer group, so the
running reporting-etl is unaffected. Standard library plus the ETL's own modules only.
"""

import base64
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime
from decimal import Decimal

from confluent_kafka import Consumer, TopicPartition

from reporting_etl.consumer import OrderEventsConsumer
from reporting_etl.transform import OrderRow

IAM_URL = os.environ.get("IAM_URL", "http://iam-app:8081")
ACCOUNT_URL = os.environ.get("ACCOUNT_URL", "http://account-app:8082")
MARKETDATA_URL = os.environ.get("MARKETDATA_URL", "http://market-data-app:8083")
ORDER_URL = os.environ.get("ORDER_URL", "http://order-app:8084")
BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
TOPIC = os.environ.get("ORDER_EVENTS_TOPIC", "order-events")
EMAIL = os.environ.get("CHECK_EMAIL", "alice.johnson@leap.com")
PASSWORD = os.environ.get("CHECK_PASSWORD", "Password123!")
SYMBOL = os.environ.get("CHECK_SYMBOL", "AAPL")

QUOTE_WAIT_SECONDS = float(os.environ.get("QUOTE_WAIT_SECONDS", "900"))
EVENT_WAIT_SECONDS = 15
NO_EVENT_WAIT_SECONDS = 10


class CheckFailed(AssertionError):
    pass


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise CheckFailed(message)


# ---- HTTP ------------------------------------------------------------------------------------

def http(method: str, url: str, token: str | None = None, body: dict | None = None) -> tuple[int, object]:
    """Sends a JSON request; returns the status and the parsed body, with numbers as Decimal."""
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as ex:
        status, raw = ex.code, ex.read()
    try:
        parsed = json.loads(raw, parse_float=Decimal) if raw else None
    except json.JSONDecodeError:
        parsed = raw.decode(errors="replace")
    return status, parsed


def login() -> tuple[str, str]:
    """Signs in; returns the access token and the client ID it carries (the JWT subject)."""
    status, body = http("POST", f"{IAM_URL}/api/iam/auth/login", body={"email": EMAIL, "password": PASSWORD})
    expect(status == 200, f"login as {EMAIL} returned {status}: {body}")
    token = body["accessToken"]
    payload = token.split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    return token, claims["sub"]


def first_account(token: str) -> dict:
    status, body = http("GET", f"{ACCOUNT_URL}/api/account/accounts", token)
    expect(status == 200 and body, f"listing accounts returned {status}: {body}")
    return body[0]


def ask_price(token: str, wait_seconds: float = 60) -> Decimal:
    """The current ask, waiting for the quote feed if it hasn't produced one for the symbol yet."""
    started = time.monotonic()
    reported = started
    while True:
        status, body = http("GET", f"{MARKETDATA_URL}/api/marketdata/quotes/{SYMBOL}", token)
        if status == 200 and isinstance(body, dict) and body.get("askPrice"):
            return body["askPrice"]
        now = time.monotonic()
        if now - started > wait_seconds:
            raise CheckFailed(f"no quote for {SYMBOL} after {wait_seconds:.0f}s: {status} {body}")
        if now - reported >= 30:
            reported = now
            print(f"  still no {SYMBOL} quote after {now - started:.0f}s "
                  f"(market-data is probably still backfilling price history)", flush=True)
        time.sleep(2)


def place_order(token: str, account_id: str, side: str, quantity: int, quoted_price: Decimal) -> tuple[int, dict]:
    # A wide tolerance, so a quote that moves between reading it and filling doesn't reject the
    # order; quotedPrice is required anyway if the account has a saved tolerance.
    return http("POST", f"{ORDER_URL}/api/order/orders", token, {
        "accountId": account_id,
        "symbol": SYMBOL,
        "side": side,
        "quantity": quantity,
        "quotedPrice": str(quoted_price),
        "maxSlippagePercent": "10.00",
    })


# ---- Kafka -----------------------------------------------------------------------------------

def consumer_at_end() -> Consumer:
    """
    A consumer on its own group, assigned every partition at the current end, so only events
    published from now on are read (not the seed history). Positions are set explicitly before
    any order is placed, so an event can't slip in before the consumer finds its offsets.
    """
    consumer = Consumer({
        "bootstrap.servers": BOOTSTRAP_SERVERS,
        "group.id": f"order-events-check-{uuid.uuid4()}",
        "enable.auto.commit": False,
    })
    metadata = consumer.list_topics(TOPIC, timeout=10)
    topic = metadata.topics.get(TOPIC)
    expect(topic is not None and topic.error is None and topic.partitions,
           f"topic {TOPIC} not found on {BOOTSTRAP_SERVERS}")
    partitions = []
    for partition in sorted(topic.partitions):
        _, high = consumer.get_watermark_offsets(TopicPartition(TOPIC, partition), timeout=10)
        partitions.append(TopicPartition(TOPIC, partition, high))
    consumer.assign(partitions)
    return consumer


def wait_for_event(consumer: Consumer, order_id: str, seconds: float):
    """The first message keyed by order_id within the time limit, or None. Others are skipped."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        message = consumer.poll(1.0)
        if message is None or message.error():
            continue
        if message.key() and message.key().decode() == order_id:
            return message
    return None


class RecordingLoader:
    """Stands in for OrderLoader: keeps each row instead of writing it to Postgres."""

    def __init__(self):
        self.rows: list[OrderRow] = []

    def load(self, row: OrderRow) -> bool:
        self.rows.append(row)
        return True


def timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


# ---- Checks ----------------------------------------------------------------------------------

def check_filled_order(consumer: Consumer, token: str, client_id: str, account_id: str) -> None:
    status, order = place_order(token, account_id, "BUY", 1, ask_price(token))
    expect(status == 200 and order.get("status") == "FILLED",
           f"BUY 1 {SYMBOL} should fill, got {status}: {order}")
    order_id = order["orderId"]
    fill_price = order["execution"]["fillPrice"]
    print(f"  placed BUY 1 {SYMBOL}: order {order_id} FILLED at {fill_price}")

    message = wait_for_event(consumer, order_id, EVENT_WAIT_SECONDS)
    expect(message is not None, f"no order-events message for order {order_id} within {EVENT_WAIT_SECONDS}s")
    print(f"  received it from {message.topic()}[{message.partition()}]@{message.offset()}")

    # The contract on the wire: plain JSON, no Java type headers, ISO timestamps with an offset.
    header_names = [name for name, _ in (message.headers() or [])]
    expect("__TypeId__" not in header_names, f"message carries Java type headers: {header_names}")
    event = json.loads(message.value(), parse_float=Decimal)
    expected = {
        "orderId": order_id,
        "accountId": account_id,
        "clientId": client_id,
        "instrumentId": order["instrumentId"],
        "symbol": order["symbol"],
        "side": "BUY",
        "quantity": 1,
        "status": "FILLED",
        "rejectionReason": None,
    }
    for field, value in expected.items():
        expect(event.get(field) == value, f"event {field} is {event.get(field)!r}, expected {value!r}")
    expect(Decimal(event["fillPrice"]) == Decimal(fill_price),
           f"event fillPrice is {event['fillPrice']}, the order filled at {fill_price}")
    for field in ("submittedAt", "completedAt"):
        expect(isinstance(event.get(field), str), f"event {field} is not an ISO string: {event.get(field)!r}")
        expect(timestamp(event[field]).tzinfo is not None, f"event {field} has no offset: {event[field]}")
    expect(timestamp(event["submittedAt"]) == timestamp(order["submittedAt"]),
           f"event submittedAt {event['submittedAt']} != order's {order['submittedAt']}")
    expect(timestamp(event["completedAt"]) == timestamp(order["filledAt"]),
           f"event completedAt {event['completedAt']} != order's filledAt {order['filledAt']}")
    uuid.UUID(event["eventId"])

    # The ETL's own consumer, on the real message: transform, load, then commit.
    loader = RecordingLoader()
    OrderEventsConsumer(consumer, loader).process(message, threading.Event())
    expect(len(loader.rows) == 1, f"the ETL loaded {len(loader.rows)} rows, expected 1")
    row = loader.rows[0]
    expected_row = OrderRow(
        order_id=uuid.UUID(order_id),
        account_id=uuid.UUID(account_id),
        client_id=uuid.UUID(client_id),
        instrument_id=uuid.UUID(order["instrumentId"]),
        symbol=order["symbol"].upper(),
        side="BUY",
        quantity=1,
        status="FILLED",
        fill_price=Decimal(fill_price),
        rejection_reason=None,
        submitted_at=timestamp(order["submittedAt"]),
        completed_at=timestamp(order["filledAt"]),
    )
    expect(row == expected_row, f"the ETL built\n    {row}\n  expected\n    {expected_row}")
    expect(isinstance(row.fill_price, Decimal), f"fill_price is a {type(row.fill_price).__name__}, not Decimal")

    committed = consumer.committed([TopicPartition(TOPIC, message.partition())], timeout=10)[0]
    expect(committed.offset == message.offset() + 1,
           f"committed offset is {committed.offset}, expected {message.offset() + 1}")
    print(f"  the ETL built the expected row and committed offset {committed.offset}")


def check_validation_rejection(consumer: Consumer, token: str, account_id: str) -> None:
    status, order = place_order(token, account_id, "SELL", 1_000_000, ask_price(token))
    expect(status == 200 and order.get("status") == "REJECTED",
           f"SELL 1,000,000 {SYMBOL} should be rejected by validation, got {status}: {order}")
    order_id = order["orderId"]
    print(f"  placed SELL 1000000 {SYMBOL}: order {order_id} REJECTED ({order.get('rejectionReason')})")

    message = wait_for_event(consumer, order_id, NO_EVENT_WAIT_SECONDS)
    if message is not None:
        raise CheckFailed(f"a validation rejection published "
                          f"{message.topic()}[{message.partition()}]@{message.offset()}")
    print(f"  no event for it within {NO_EVENT_WAIT_SECONDS}s")


def main() -> int:
    token, client_id = login()
    account = first_account(token)
    print(f"Signed in as {EMAIL}; using account {account['accountNumber']} ({account['accountId']})")

    # market-data serves its health endpoint while it backfills price history, but only starts
    # quoting once that is done, so wait for quotes here rather than fail both checks.
    print(f"Waiting for a live {SYMBOL} quote (up to {QUOTE_WAIT_SECONDS:.0f}s)", flush=True)
    ask_price(token, QUOTE_WAIT_SECONDS)
    print(f"  market-data is quoting {SYMBOL}")

    consumer = consumer_at_end()
    failures = 0
    try:
        checks = [
            ("filled order is published and transformed",
             lambda: check_filled_order(consumer, token, client_id, account["accountId"])),
            ("validation rejection publishes nothing",
             lambda: check_validation_rejection(consumer, token, account["accountId"])),
        ]
        for name, check in checks:
            print(f"\n{name}")
            try:
                check()
                print(f"PASS  {name}")
            except Exception as ex:  # report each check, keep going
                failures += 1
                print(f"FAIL  {name}: {ex}")
    finally:
        consumer.close()

    print(f"\n{len(checks) - failures} of {len(checks)} checks passed")
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except CheckFailed as ex:
        print(f"FAIL  setup: {ex}")
        sys.exit(1)
