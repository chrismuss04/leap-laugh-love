"""
Runs the real INSERT against a Postgres loaded with the schema file. Skipped unless
REPORTING_TEST_DB_URL is set, which CI does in its Unit Tests stage.
"""

import os
import uuid
from decimal import Decimal

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
    """Order ids the test loads; their rows are deleted afterwards."""
    ids = []
    yield ids
    with psycopg.connect(DB_URL, autocommit=True) as conn:
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
