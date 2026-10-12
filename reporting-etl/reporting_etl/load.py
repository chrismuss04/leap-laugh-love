"""
Writes reporting rows using shared connection and error handling.

The insert ignores an order that is already there. Kafka delivers at least once - a message is
re-read if the ETL stops after loading it but before committing its offset - and each order
publishes once, at its final status, so a repeat is always the same row.
"""

import logging
from dataclasses import asdict
from typing import Callable, Optional, TYPE_CHECKING

import psycopg

from .transform import OrderRow

if TYPE_CHECKING:
    from .clients import ClientRow

log = logging.getLogger(__name__)

INSERT_ORDER = """
INSERT INTO reporting.orders (
    order_id, account_id, client_id, instrument_id, symbol, side, quantity, status,
    fill_price, rejection_reason, submitted_at, completed_at)
VALUES (
    %(order_id)s, %(account_id)s, %(client_id)s, %(instrument_id)s, %(symbol)s, %(side)s,
    %(quantity)s, %(status)s, %(fill_price)s, %(rejection_reason)s, %(submitted_at)s,
    %(completed_at)s)
ON CONFLICT (order_id) DO NOTHING
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
