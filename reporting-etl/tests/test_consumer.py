import threading

import pytest

from reporting_etl.consumer import OrderEventsConsumer
from reporting_etl.load import DatabaseUnavailable, RowRejected
from tests.events import encode, filled_event


class FakeMessage:
    def __init__(self, value, offset=0, error=None):
        self._value = value
        self._offset = offset
        self._error = error

    def value(self):
        return self._value

    def error(self):
        return self._error

    def topic(self):
        return "order-events"

    def partition(self):
        return 0

    def offset(self):
        return self._offset


class FakeConsumer:
    """Hands out queued messages, then sets stop so run() returns."""

    def __init__(self, messages, stop):
        self._messages = list(messages)
        self._stop = stop
        self.committed = []
        self.commit_error = None

    def poll(self, timeout):
        if not self._messages:
            self._stop.set()
            return None
        return self._messages.pop(0)

    def commit(self, message, asynchronous):
        assert asynchronous is False
        if self.commit_error:
            raise self.commit_error
        self.committed.append(message.offset())


class FakeLoader:
    """Fails with each queued exception in turn, then loads; records every row it loaded."""

    def __init__(self, failures=(), inserted=True):
        self._failures = list(failures)
        self._inserted = inserted
        self.loaded = []
        self.calls = 0

    def load(self, row):
        self.calls += 1
        if self._failures:
            raise self._failures.pop(0)
        self.loaded.append(row)
        return self._inserted


def run(messages, loader, sleeps=None):
    stop = threading.Event()
    consumer = FakeConsumer(messages, stop)
    OrderEventsConsumer(consumer, loader, sleep=(sleeps.append if sleeps is not None else lambda s: None),
                        max_retry_backoff_seconds=4).run(stop)
    return consumer


def test_loads_each_event_then_commits_its_offset():
    first, second = filled_event(), filled_event(symbol="MSFT")
    loader = FakeLoader()

    consumer = run([FakeMessage(encode(first), 0), FakeMessage(encode(second), 1)], loader)

    assert [str(row.order_id) for row in loader.loaded] == [first["orderId"], second["orderId"]]
    assert consumer.committed == [0, 1]


def test_commits_a_duplicate_without_loading_it_twice():
    loader = FakeLoader(inserted=False)

    consumer = run([FakeMessage(encode(filled_event()), 7)], loader)

    assert loader.calls == 1
    assert consumer.committed == [7]


def test_skips_and_commits_an_invalid_event_without_touching_the_database():
    loader = FakeLoader()

    consumer = run([FakeMessage(b"not json", 3), FakeMessage(encode(filled_event()), 4)], loader)

    assert len(loader.loaded) == 1
    assert consumer.committed == [3, 4]


def test_skips_and_commits_a_row_the_database_refuses():
    loader = FakeLoader(failures=[RowRejected("value too long")])

    consumer = run([FakeMessage(encode(filled_event()), 5)], loader)

    assert loader.loaded == []
    assert consumer.committed == [5]


def test_retries_the_same_row_with_backoff_while_the_database_is_down():
    loader = FakeLoader(failures=[DatabaseUnavailable("down")] * 4)
    sleeps = []

    consumer = run([FakeMessage(encode(filled_event()), 9)], loader, sleeps)

    assert loader.calls == 5
    assert len(loader.loaded) == 1
    assert sleeps == [1.0, 2.0, 4.0, 4.0]  # doubles up to the cap
    assert consumer.committed == [9]


def test_never_commits_before_the_row_is_loaded():
    loader = FakeLoader(failures=[DatabaseUnavailable("down")])
    stop = threading.Event()
    consumer = FakeConsumer([], stop)
    commits_during_failure = []

    def sleep(_seconds):
        commits_during_failure.append(list(consumer.committed))

    OrderEventsConsumer(consumer, loader, sleep=sleep).process(FakeMessage(encode(filled_event()), 2), stop)

    assert commits_during_failure == [[]]
    assert consumer.committed == [2]


def test_leaves_the_message_uncommitted_when_stopped_while_the_database_is_down():
    loader = FakeLoader(failures=[DatabaseUnavailable("down")])
    stop = threading.Event()
    stop.set()
    consumer = FakeConsumer([], stop)

    OrderEventsConsumer(consumer, loader, sleep=lambda s: None).process(
        FakeMessage(encode(filled_event()), 2), stop)

    assert consumer.committed == []


def test_ignores_kafka_error_messages():
    loader = FakeLoader()

    consumer = run([FakeMessage(None, 0, error="broker transport failure"),
                    FakeMessage(encode(filled_event()), 1)], loader)

    assert len(loader.loaded) == 1
    assert consumer.committed == [1]


def test_keeps_going_when_a_commit_fails():
    loader = FakeLoader()
    stop = threading.Event()
    consumer = FakeConsumer([], stop)
    consumer.commit_error = RuntimeError("rebalance in progress")

    OrderEventsConsumer(consumer, loader).process(FakeMessage(encode(filled_event()), 1), stop)

    assert len(loader.loaded) == 1


def test_an_unexpected_loader_error_stops_the_etl():
    # A bug, not an outage: crash so the container restarts and the message is re-read,
    # rather than retry it forever or skip it.
    loader = FakeLoader(failures=[TypeError("bug")])

    with pytest.raises(TypeError):
        run([FakeMessage(encode(filled_event()), 1)], loader)
