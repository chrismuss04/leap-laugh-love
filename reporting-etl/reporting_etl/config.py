"""Settings read from the environment, so the same image runs in compose, CI and locally."""

import os
from dataclasses import dataclass
from typing import Mapping


@dataclass(frozen=True)
class Settings:
    bootstrap_servers: str
    topic: str
    group_id: str
    db_url: str
    poll_timeout_seconds: float
    max_retry_backoff_seconds: float
    mode: str = "orders"

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ) -> "Settings":
        """
        Reads the settings. KAFKA_BOOTSTRAP_SERVERS and REPORTING_DB_URL are required: a default
        that points at the wrong broker or database would fail quietly instead of at startup.
        """
        missing = [name for name in ("KAFKA_BOOTSTRAP_SERVERS", "REPORTING_DB_URL") if not env.get(name)]
        if missing:
            raise ValueError("Missing required environment variable(s): " + ", ".join(missing))
        mode = env.get("ETL_MODE", "orders")
        if mode not in ("orders", "clients"):
            raise ValueError("ETL_MODE must be orders or clients")
        return cls(
            bootstrap_servers=env["KAFKA_BOOTSTRAP_SERVERS"],
            topic=(env.get("CLIENT_EVENTS_TOPIC", "client-register") if mode == "clients"
                   else env.get("ORDER_EVENTS_TOPIC", "order-events")),
            group_id=env.get("CONSUMER_GROUP", "user-etl" if mode == "clients" else "reporting-etl"),
            db_url=env["REPORTING_DB_URL"],
            poll_timeout_seconds=float(env.get("POLL_TIMEOUT_SECONDS", "1.0")),
            max_retry_backoff_seconds=float(env.get("MAX_RETRY_BACKOFF_SECONDS", "30")),
            mode=mode,
        )
