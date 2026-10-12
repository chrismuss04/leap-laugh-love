"""
Runs the real INSERT against a Postgres loaded with the schema file. Skipped unless
REPORTING_TEST_DB_URL is set, which CI does in its Unit Tests stage.
"""

import os
import uuid
from datetime import date
from decimal import Decimal
from pathlib import Path

import psycopg
import pytest

from reporting_etl.load import DatabaseUnavailable, OrderLoader, RowRejected
from reporting_etl.transform import to_order_row
from tests.events import encode, filled_event, rejected_event

DB_URL = os.environ.get("REPORTING_TEST_DB_URL")

pytestmark = pytest.mark.skipif(not DB_URL, reason="REPORTING_TEST_DB_URL is not set")


@pytest.fixture
def loader():
    loader = OrderLoader.for_url(DB_URL)
    yield loader
    loader.close()


@pytest.fixture
def order_ids():
    """Order ids the test loads; their rows, and their clients' rollup rows, are deleted afterwards."""
    ids = []
    yield ids
    with psycopg.connect(DB_URL, autocommit=True) as conn:
        conn.execute("DELETE FROM reporting.client_months WHERE client_id IN"
                     " (SELECT client_id FROM reporting.orders WHERE order_id = ANY(%s))", (ids,))
        conn.execute("DELETE FROM reporting.orders WHERE order_id = ANY(%s)", (ids,))


def stored(order_id):
    with psycopg.connect(DB_URL) as conn:
        return conn.execute(
            "SELECT symbol, side, quantity, status, fill_price, rejection_reason, completed_at"
            " FROM reporting.orders WHERE order_id = %s", (order_id,)).fetchone()


def test_inserts_a_filled_order(loader, order_ids):
    row = to_order_row(encode(filled_event()))
    order_ids.append(row.order_id)

    assert loader.load(row) is True

    symbol, side, quantity, status, fill_price, rejection_reason, completed_at = stored(row.order_id)
    assert (symbol, side, quantity, status) == ("AAPL", "BUY", 10, "FILLED")
    assert fill_price == Decimal("187.420000")
    assert rejection_reason is None
    assert completed_at == row.completed_at


def test_inserts_a_rejected_order_without_a_price(loader, order_ids):
    row = to_order_row(encode(rejected_event()))
    order_ids.append(row.order_id)

    assert loader.load(row) is True

    assert stored(row.order_id)[3:6] == ("REJECTED", None, row.rejection_reason)


def test_a_repeated_order_is_ignored(loader, order_ids):
    row = to_order_row(encode(filled_event()))
    order_ids.append(row.order_id)

    assert loader.load(row) is True
    assert loader.load(row) is False

    with psycopg.connect(DB_URL) as conn:
        count = conn.execute("SELECT count(*) FROM reporting.orders WHERE order_id = %s",
                             (row.order_id,)).fetchone()[0]
    assert count == 1


def test_a_price_too_large_for_the_column_is_refused(loader, order_ids):
    row = to_order_row(encode(filled_event(fillPrice=10 ** 13)))
    order_ids.append(row.order_id)

    with pytest.raises(RowRejected):
        loader.load(row)
    # The connection is still usable after a refused row.
    ok = to_order_row(encode(filled_event()))
    order_ids.append(ok.order_id)
    assert loader.load(ok) is True


def test_an_unreachable_database_is_reported_as_unavailable(order_ids):
    loader = OrderLoader(lambda: psycopg.connect("postgresql://nobody@127.0.0.1:1/none", connect_timeout=1))

    with pytest.raises(DatabaseUnavailable):
        loader.load(to_order_row(encode(filled_event())))


# ---- reporting.client_months rollup ----------------------------------------------------------

MIGRATION = Path(__file__).resolve().parents[2] / "scripts" / "migrate-reporting-client-months.sql"


def load_all(loader, order_ids, *events):
    rows = [to_order_row(encode(event)) for event in events]
    for row in rows:
        order_ids.append(row.order_id)
        assert loader.load(row) is True
    return rows


def rollup(client_id):
    """The client's rollup as {month: (order_count, filled_count, filled_value)}."""
    with psycopg.connect(DB_URL) as conn:
        rows = conn.execute(
            "SELECT month, order_count, filled_count, filled_value FROM reporting.client_months"
            " WHERE client_id = %s", (client_id,)).fetchall()
    return {month: (orders, filled, value) for month, orders, filled, value in rows}


def test_a_filled_order_adds_its_count_and_value_to_its_month(loader, order_ids):
    [row] = load_all(loader, order_ids, filled_event(quantity=10, fillPrice=187.42))

    assert rollup(row.client_id) == {date(2026, 10, 1): (1, 1, Decimal("1874.200000"))}


def test_a_rejected_order_counts_as_activity_but_adds_no_value(loader, order_ids):
    [row] = load_all(loader, order_ids, rejected_event())

    assert rollup(row.client_id) == {date(2026, 10, 1): (1, 0, Decimal("0"))}


def test_orders_in_the_same_month_accumulate_and_other_months_stay_apart(loader, order_ids):
    client = str(uuid.uuid4())
    load_all(loader, order_ids,
             filled_event(clientId=client, quantity=2, fillPrice=100),
             filled_event(clientId=client, quantity=1, fillPrice=50.5),
             rejected_event(clientId=client),
             filled_event(clientId=client, quantity=3, fillPrice=10, completedAt="2026-11-02T09:00:00Z"))

    assert rollup(uuid.UUID(client)) == {
        date(2026, 10, 1): (3, 2, Decimal("250.500000")),
        date(2026, 11, 1): (1, 1, Decimal("30.000000")),
    }


def test_a_repeated_order_does_not_count_twice(loader, order_ids):
    [row] = load_all(loader, order_ids, filled_event())

    assert loader.load(row) is False

    assert rollup(row.client_id) == {date(2026, 10, 1): (1, 1, Decimal("1874.200000"))}


@pytest.mark.parametrize("completed_at, month", [
    ("2026-09-30T23:59:59.999Z", date(2026, 9, 1)),
    ("2026-10-01T00:00:00Z", date(2026, 10, 1)),
    # 23:30 on 30 September in UTC, although the offset puts it in October locally.
    ("2026-10-01T01:30:00+02:00", date(2026, 9, 1)),
    ("2025-12-31T23:59:59Z", date(2025, 12, 1)),
])
def test_an_order_lands_in_the_utc_month_it_completed_in(loader, order_ids, completed_at, month):
    [row] = load_all(loader, order_ids, filled_event(completedAt=completed_at))

    assert list(rollup(row.client_id)) == [month]


def test_a_refused_order_adds_nothing_to_the_rollup(loader, order_ids):
    row = to_order_row(encode(filled_event(fillPrice=10 ** 13)))
    order_ids.append(row.order_id)

    with pytest.raises(RowRejected):
        loader.load(row)

    assert rollup(row.client_id) == {}


def test_the_migration_rebuilds_the_same_rollup_the_loader_kept(loader, order_ids):
    client = str(uuid.uuid4())
    load_all(loader, order_ids,
             filled_event(clientId=client, quantity=2, fillPrice=100),
             rejected_event(clientId=client),
             filled_event(clientId=client, quantity=7, fillPrice=3.25, completedAt="2026-08-15T12:00:00Z"))
    kept = rollup(uuid.UUID(client))

    with psycopg.connect(DB_URL, autocommit=True) as conn:
        conn.execute(MIGRATION.read_text(encoding="utf-8"))
        # Twice: a rerun gives the same result.
        conn.execute(MIGRATION.read_text(encoding="utf-8"))

    assert rollup(uuid.UUID(client)) == kept
    assert kept == {
        date(2026, 10, 1): (2, 1, Decimal("200.000000")),
        date(2026, 8, 1): (1, 1, Decimal("22.750000")),
    }
