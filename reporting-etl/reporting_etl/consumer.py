"""
The poll loop: read an order event, load it, then commit its offset.

Offsets are committed by hand and only after the row is in the database, so a crash at any
point re-reads the message instead of losing it; the load ignores an order it already has.
"""

import logging
import threading
import time
from typing import Callable, Protocol

from .load import DatabaseUnavailable, RowRejected
from .transform import InvalidEvent, OrderRow, to_order_row

log = logging.getLogger(__name__)


class Loader(Protocol):
    def load(self, row: OrderRow) -> bool: ...


class OrderEventsConsumer:
    """
    Runs the ETL over a subscribed confluent_kafka Consumer (or anything with its poll/commit
    shape, which is how the tests drive it).
    """

    def __init__(self, consumer, loader: Loader, *, poll_timeout_seconds: float = 1.0,
                 max_retry_backoff_seconds: float = 30.0,
                 sleep: Callable[[float], None] = time.sleep):
        self._consumer = consumer
        self._loader = loader
        self._poll_timeout = poll_timeout_seconds
        self._max_backoff = max_retry_backoff_seconds
        self._sleep = sleep

    def run(self, stop: threading.Event) -> None:
        """Polls until stop is set."""
        while not stop.is_set():
            message = self._consumer.poll(self._poll_timeout)
            if message is None:
                continue
            if message.error():
                # librdkafka retries broker and network errors itself; these are informational.
                log.warning("Kafka reported an error: %s", message.error())
                continue
            self.process(message, stop)

    def process(self, message, stop: threading.Event) -> None:
        """
        Loads one message and commits it. A message that can never load is logged and committed,
        so it can't block the partition. While the database is down the same message is retried,
        uncommitted, until it loads or the ETL is stopped.
        """
        where = f"{message.topic()}[{message.partition()}]@{message.offset()}"
        try:
            row = to_order_row(message.value())
        except InvalidEvent as ex:
            log.warning("Skipping invalid order event at %s: %s", where, ex)
            self._commit(message)
            return

        backoff = 1.0
        while True:
            try:
                inserted = self._loader.load(row)
                break
            except RowRejected as ex:
                log.error("Database refused order %s from %s; skipping: %s", row.order_id, where, ex)
                self._commit(message)
                return
            except DatabaseUnavailable as ex:
                if stop.is_set():
                    # Left uncommitted: whoever reads this partition next loads it.
                    return
                log.warning("Database unavailable loading order %s; retrying in %.0fs: %s",
                            row.order_id, backoff, ex)
                self._sleep(backoff)
                backoff = min(backoff * 2, self._max_backoff)

        if inserted:
            log.info("Loaded %s order %s (%s %s x%d)", row.status, row.order_id, row.side,
                     row.symbol, row.quantity)
        else:
            log.info("Order %s was already loaded; skipped duplicate at %s", row.order_id, where)
        self._commit(message)

    def _commit(self, message) -> None:
        try:
            self._consumer.commit(message=message, asynchronous=False)
        except Exception as ex:  # confluent_kafka.KafkaException, e.g. after a rebalance
            # The row is loaded; if this offset is read again, the load skips it as a duplicate.
            log.warning("Could not commit offset %s: %s", message.offset(), ex)
