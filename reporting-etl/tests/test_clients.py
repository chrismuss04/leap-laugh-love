import json
import threading
import uuid
from unittest.mock import Mock

import psycopg
import pytest

from reporting_etl.clients import ClientEventsConsumer, ClientLoader, to_client_row
from reporting_etl.config import Settings
from reporting_etl.load import DatabaseUnavailable
from reporting_etl.transform import InvalidEvent
from tests.test_consumer import FakeConsumer, FakeLoader, FakeMessage


def payload(**changes):
    return json.dumps({"clientId": "ac025319-3149-4af1-aedd-530e441b57be",
                       "registered_at": "2026-10-06T12:00:00+00:00", **changes}).encode()


# Verify the producer's two-field contract maps to typed registration columns.
def test_contract():
    row = to_client_row(payload())
    assert row.client_id == uuid.UUID("ac025319-3149-4af1-aedd-530e441b57be")
    assert row.registered_at.isoformat() == "2026-10-06T12:00:00+00:00"


# Reject malformed registrations before they reach the database.
@pytest.mark.parametrize("value", [None, b"bad", b"[]", b"{}", b"\xff",
    payload(clientId="bad"), payload(registered_at="2026-10-06T12:00:00"),
    payload(registered_at=None), payload(registered_at=123)])
def test_invalid(value):
    with pytest.raises(InvalidEvent):
        to_client_row(value)


# Verify database retries do not commit offsets before a registration is stored.
def test_retry():
    stop = threading.Event()
    consumer = FakeConsumer([], stop)
    loader = FakeLoader(failures=[DatabaseUnavailable("offline")])
    sleeps = []
    def sleep(seconds):
        assert consumer.committed == []
        sleeps.append(seconds)
    ClientEventsConsumer(consumer, loader, sleep=sleep).process(FakeMessage(payload(), 3), stop)
    assert sleeps == [1.0]
    assert len(loader.loaded) == 1
    assert consumer.committed == [3]


# Verify duplicate deliveries and malformed messages follow the existing skip/commit policy.
def test_duplicate_and_invalid():
    stop = threading.Event()
    loader = FakeLoader(inserted=False)
    consumer = FakeConsumer([FakeMessage(b"bad", 1), FakeMessage(payload(), 2)], stop)
    ClientEventsConsumer(consumer, loader).run(stop)
    assert loader.calls == 1
    assert consumer.committed == [1, 2]


# Verify stopping during an outage leaves the registration available for replay.
def test_stop():
    stop = threading.Event()
    stop.set()
    consumer = FakeConsumer([], stop)
    ClientEventsConsumer(consumer, FakeLoader([DatabaseUnavailable("offline")])).process(
        FakeMessage(payload()), stop)
    assert consumer.committed == []


# Verify the client loader uses client columns and reports duplicate inserts without rewriting timestamps.
def test_load():
    conn = Mock(closed=False)
    conn.execute.return_value.rowcount = 1
    loader = ClientLoader(lambda: conn)
    row = to_client_row(payload())
    assert loader.load(row)
    sql, params = conn.execute.call_args.args
    assert "INSERT INTO reporting.clients" in sql
    assert "ON CONFLICT (client_id) DO NOTHING" in sql
    assert params == {"client_id": row.client_id, "registered_at": row.registered_at}
    conn.execute.return_value.rowcount = 0
    assert not loader.load(row)


# Verify a failed connection is discarded and the next attempt reconnects.
def test_reconnect():
    broken, working = Mock(closed=False), Mock(closed=False)
    broken.execute.side_effect = psycopg.OperationalError("offline")
    working.execute.return_value.rowcount = 1
    loader = ClientLoader(Mock(side_effect=[broken, working]))
    with pytest.raises(DatabaseUnavailable):
        loader.load(to_client_row(payload()))
    broken.close.assert_called_once()
    assert loader.load(to_client_row(payload()))


# Verify registration mode has its own topic and group and rejects unknown pipeline modes.
def test_settings():
    env = {"KAFKA_BOOTSTRAP_SERVERS": "kafka:9092", "REPORTING_DB_URL": "postgresql://db/test",
           "ETL_MODE": "clients"}
    settings = Settings.from_env(env)
    assert (settings.topic, settings.group_id, settings.mode) == ("client-register", "user-etl", "clients")
    assert Settings.from_env({**env, "CLIENT_EVENTS_TOPIC": "custom"}).topic == "custom"
    with pytest.raises(ValueError, match="ETL_MODE"):
        Settings.from_env({**env, "ETL_MODE": "typo"})
