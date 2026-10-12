import pytest

from reporting_etl.config import Settings

REQUIRED = {
    "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
    "REPORTING_DB_URL": "postgresql://paysprint:secret@db:5432/paysprint",
}


def test_reads_required_settings_and_defaults():
    settings = Settings.from_env(REQUIRED)

    assert settings.bootstrap_servers == "kafka:9092"
    assert settings.db_url == REQUIRED["REPORTING_DB_URL"]
    assert settings.topic == "order-events"
    assert settings.group_id == "reporting-etl"
    assert settings.poll_timeout_seconds == 1.0
    assert settings.max_retry_backoff_seconds == 30.0


def test_reads_overrides():
    settings = Settings.from_env({**REQUIRED, "ORDER_EVENTS_TOPIC": "orders-test",
                                  "CONSUMER_GROUP": "etl-test", "POLL_TIMEOUT_SECONDS": "0.5",
                                  "MAX_RETRY_BACKOFF_SECONDS": "5"})

    assert settings.topic == "orders-test"
    assert settings.group_id == "etl-test"
    assert settings.poll_timeout_seconds == 0.5
    assert settings.max_retry_backoff_seconds == 5.0


@pytest.mark.parametrize("missing", list(REQUIRED))
def test_fails_fast_without_a_required_setting(missing):
    env = {name: value for name, value in REQUIRED.items() if name != missing}

    with pytest.raises(ValueError, match=missing):
        Settings.from_env(env)
