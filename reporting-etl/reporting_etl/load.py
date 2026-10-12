"""
Writes reporting rows using shared connection and error handling.

The insert ignores an order that is already there. Kafka delivers at least once - a message is
re-read if the ETL stops after loading it but before committing its offset - and each order
publishes once, at its final status, so a repeat is always the same row.

A new order also adds itself to its client's month in reporting.client_months, in the same
statement: the rollup only sees the orders the insert actually stored, so a repeat that the
insert ignores never counts twice, and the two tables can't disagree after a failure.
"""

import logging
from dataclasses import asdict
from typing import Callable, Optional, TYPE_CHECKING

import psycopg

from .transform import OrderRow

if TYPE_CHECKING:
    from .clients import ClientRow

log = logging.getLogger(__name__)

# RETURNING yields a row only when the order was new, so the rollup insert below adds nothing for
# a repeat, and the statement's row count is 1 for a new order and 0 for one already loaded.
INSERT_ORDER = """
WITH inserted AS (
    INSERT INTO reporting.orders (
        order_id, account_id, client_id, instrument_id, symbol, side, quantity, status,
        fill_price, rejection_reason, submitted_at, completed_at)
    VALUES (
        %(order_id)s, %(account_id)s, %(client_id)s, %(instrument_id)s, %(symbol)s, %(side)s,
        %(quantity)s, %(status)s, %(fill_price)s, %(rejection_reason)s, %(submitted_at)s,
        %(completed_at)s)
    ON CONFLICT (order_id) DO NOTHING
    RETURNING client_id, status, quantity, fill_price, completed_at
)
INSERT INTO reporting.client_months AS cm (client_id, month, order_count, filled_count, filled_value)
SELECT client_id,
       date_trunc('month', completed_at AT TIME ZONE 'UTC')::date,
       1,
       CASE WHEN status = 'FILLED' THEN 1 ELSE 0 END,
       CASE WHEN status = 'FILLED' THEN quantity * fill_price ELSE 0 END
FROM inserted
ON CONFLICT (client_id, month) DO UPDATE SET
    order_count = cm.order_count + EXCLUDED.order_count,
    filled_count = cm.filled_count + EXCLUDED.filled_count,
    filled_value = cm.filled_value + EXCLUDED.filled_value
"""


class RowRejected(Exception):
    """The database refused this row (a constraint or a bad value); retrying won't change that."""


class DatabaseUnavailable(Exception):
    """The database couldn't be reached or failed for a reason that may pass; retry later."""


class RowLoader:
    """Loads rows over one connection, opened on first use and reopened after a failure."""

    statement: str

    def __init__(self, connect: Callable[[], psycopg.Connection]):
        self._connect = connect
        self._conn: Optional[psycopg.Connection] = None

    @classmethod
    def for_url(cls, db_url: str) -> "RowLoader":
        # Autocommit: each load is one INSERT, so it is its own transaction.
        return cls(lambda: psycopg.connect(db_url, autocommit=True, connect_timeout=5))

    def load(self, row: "OrderRow | ClientRow") -> bool:
        """
        Inserts the row.
        :return: True if it was inserted, False if the row was already loaded
        :raises RowRejected: if the database refuses the row itself
        :raises DatabaseUnavailable: for any other database failure
        """
        try:
            if self._conn is None or self._conn.closed:
                self._conn = self._connect()
            cursor = self._conn.execute(self.statement, asdict(row))
            return cursor.rowcount == 1
        except (psycopg.errors.DataError, psycopg.errors.IntegrityError) as ex:
            raise RowRejected(str(ex)) from ex
        except psycopg.Error as ex:
            # Connection lost, server restarting, schema not loaded yet: start over on a fresh
            # connection next time rather than reuse one in an unknown state.
            self.close()
            raise DatabaseUnavailable(str(ex)) from ex

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except psycopg.Error:
                log.debug("Ignoring error closing the database connection", exc_info=True)
            self._conn = None


class OrderLoader(RowLoader):
    """Load completed orders using the shared connection recovery behavior."""
    statement = INSERT_ORDER
