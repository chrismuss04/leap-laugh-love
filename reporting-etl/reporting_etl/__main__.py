"""Entry point: python -m reporting_etl"""

import logging
import os
import signal
import threading

from confluent_kafka import Consumer

from .config import Settings
from .consumer import OrderEventsConsumer
from .load import OrderLoader
from .clients import ClientEventsConsumer, ClientLoader

log = logging.getLogger("reporting_etl")


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )
    settings = Settings.from_env()

    stop = threading.Event()
    # docker stop sends SIGTERM; Ctrl+C sends SIGINT. Either finishes the current message first.
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())

    consumer = Consumer({
        "bootstrap.servers": settings.bootstrap_servers,
        "group.id": settings.group_id,
        # Names this process in the group's member list (CI's Verify Services looks for it).
        "client.id": "user-etl-consumer" if settings.mode == "clients" else "reporting-etl-consumer",
        # A new group starts from the beginning of the topic, so nothing published before the
        # ETL first ran is missed.
        "auto.offset.reset": "earliest",
        # Committed by OrderEventsConsumer only once the row is in the database.
        "enable.auto.commit": False,
    })
    loader_type = ClientLoader if settings.mode == "clients" else OrderLoader
    consumer_type = ClientEventsConsumer if settings.mode == "clients" else OrderEventsConsumer
    loader = loader_type.for_url(settings.db_url)
    consumer.subscribe([settings.topic])
    log.info("Consuming %s as group %s", settings.topic, settings.group_id)

    try:
        consumer_type(
            consumer, loader,
            poll_timeout_seconds=settings.poll_timeout_seconds,
            max_retry_backoff_seconds=settings.max_retry_backoff_seconds,
        ).run(stop)
    finally:
        # Leaves the group straight away, so its partitions are reassigned without waiting for
        # the session to time out.
        consumer.close()
        loader.close()
        log.info("Stopped")


if __name__ == "__main__":
    main()
