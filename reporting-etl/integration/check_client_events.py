"""Check real IAM registration, Kafka delivery, ETL storage and duplicate replay.

Run through scripts/test-client-events.sh against a development/test stack.
The generated client and provisioned account remain available for inspection.
"""
import json
import os
import re
import time
import uuid
from datetime import datetime
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

import psycopg
from confluent_kafka import Consumer, Producer, TopicPartition

from reporting_etl.config import Settings


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def wait_for(check, description, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for {description}")


def post(path, body):
    url = os.environ.get("IAM_URL", "http://iam-app:8081") + path
    request = Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=30) as response:
            return response.status, response.read().decode()
    except HTTPError as error:
        return error.code, error.read().decode()


def register(body):
    return post("/api/iam/v1/clients/register", body)


# IAM only opens the client once the link it emails is followed, so read it from the mail catcher.
def confirmation_token(email):
    mailpit = os.environ.get("MAILPIT_URL", "http://mailpit:8025")

    def newest():
        query = quote(f'to:"{email}" subject:"Confirm your email"')
        with urlopen(f"{mailpit}/api/v1/search?query={query}", timeout=10) as response:
            messages = json.load(response)["messages"]
        return messages[0]["ID"] if messages else None

    with urlopen(f"{mailpit}/api/v1/message/{wait_for(newest, 'confirmation email')}", timeout=10) as response:
        text = json.load(response)["Text"]
    match = re.search(r"/verify-email\?token=([\w-]+)", text)
    require(match, "Confirmation email has no link")
    return match.group(1)


# Verify a real registration reaches Kafka and the running ETL stores its original timestamp.
def check_registration(observer, conn):
    digits = f"{uuid.uuid4().int % 1_000_000_000:09d}"
    body = {
        "email": f"user-etl-{uuid.uuid4().hex}@example.com", "password": "TestPassword123!",
        "fullName": "User ETL Test", "dateOfBirth": "1990-01-01",
        "ssn": f"{digits[:3]}-{digits[3:5]}-{digits[5:]}", "addressLine1": "1 Test Street",
        "city": "Boston", "postalCode": "02101", "countryCode": "US",
        "experienceLevel": "NOVICE", "initialDepositAmount": 5000,
    }
    status, response = register(body)
    require(status == 202, f"Registration returned {status}: {response}")
    accepted = response
    require(conn.execute("SELECT 1 FROM iam.clients WHERE email=%s", (body["email"],)).fetchone() is None,
            "Client was opened before its email was confirmed")
    token = confirmation_token(body["email"])
    status, response = post("/api/iam/v1/clients/register/verify", {"token": token})
    require(status == 204, f"Email confirmation returned {status}: {response}")
    client_id = str(conn.execute("SELECT client_id FROM iam.clients WHERE email=%s", (body["email"],)).fetchone()[0])
    print(f"Created test client {client_id}", flush=True)

    def event_for_client():
        message = observer.poll(1)
        if message is None:
            return None
        require(not message.error(), f"Kafka error: {message.error()}")
        event = json.loads(message.value())
        return message if event.get("clientId") == client_id else None

    message = wait_for(event_for_client, "registration event")
    event = json.loads(message.value())
    require(set(event) == {"clientId", "registered_at"}, "Unexpected event fields")
    require(message.key().decode() == client_id, "Kafka key is not the client ID")
    source_time = conn.execute("SELECT created_at FROM iam.clients WHERE client_id=%s", (client_id,)).fetchone()[0]
    event_time = datetime.fromisoformat(event["registered_at"])
    require(event_time.tzinfo is not None, "Registration timestamp has no offset")
    # PostgreSQL stores microseconds; Java timestamps can carry nanoseconds.
    require(abs((source_time - event_time).total_seconds()) <= 0.000001, "Event timestamp differs from IAM")
    row = wait_for(lambda: conn.execute(
        "SELECT registered_at, loaded_at FROM reporting.clients WHERE client_id=%s", (client_id,)).fetchone(),
        "registration reporting row")
    require(row[0] == event_time, "ETL changed the registration timestamp")
    # A duplicate is answered exactly like the original and must open nothing.
    status, response = register(body)
    require((status, response) == (202, accepted), "Duplicate registration was answered differently")
    status, _ = post("/api/iam/v1/clients/register/verify", {"token": token})
    require(status == 400, "Confirmation link worked twice")
    require(conn.execute("SELECT COUNT(*) FROM iam.clients WHERE email=%s", (body["email"],)).fetchone()[0] == 1,
            "Duplicate registration opened a second client")
    return client_id, message, row


# Verify replay is processed by the running user consumer without duplicating or rewriting the row.
def check_replay(settings, conn, client_id, message, original):
    delivered = []
    producer = Producer({"bootstrap.servers": settings.bootstrap_servers})
    producer.produce(settings.topic, key=message.key(), value=message.value(),
                     on_delivery=lambda error, result: delivered.append((error, result)))
    require(producer.flush(30) == 0 and delivered, "Replay was not delivered")
    error, result = delivered[0]
    require(error is None, f"Replay delivery failed: {error}")
    # Query offsets only: never subscribe or join the real ETL group.
    tracker = Consumer({"bootstrap.servers": settings.bootstrap_servers,
                        "group.id": settings.group_id, "enable.auto.commit": False})
    try:
        wait_for(lambda: tracker.committed([TopicPartition(settings.topic, result.partition())], timeout=5)[0].offset
                 > result.offset(), "user ETL to commit the replay")
    finally:
        tracker.close()
    rows = conn.execute("SELECT registered_at, loaded_at FROM reporting.clients WHERE client_id=%s",
                        (client_id,)).fetchall()
    require(rows == [original], "Replay duplicated or changed the registration row")


def main():
    settings = Settings.from_env()
    require(settings.mode == "clients", "Run with ETL_MODE=clients")
    observer = Consumer({"bootstrap.servers": settings.bootstrap_servers,
                         "group.id": f"client-check-{uuid.uuid4()}", "enable.auto.commit": False})
    try:
        metadata = observer.list_topics(settings.topic, timeout=30).topics[settings.topic]
        require(not metadata.error, f"Topic unavailable: {metadata.error}")
        partitions = []
        for partition in metadata.partitions:
            tp = TopicPartition(settings.topic, partition)
            _, high = observer.get_watermark_offsets(tp, timeout=10)
            partitions.append(TopicPartition(settings.topic, partition, high))
        observer.assign(partitions)
        with psycopg.connect(settings.db_url, autocommit=True, connect_timeout=5) as conn:
            require(conn.execute("SELECT to_regclass('reporting.clients')").fetchone()[0],
                    "Apply scripts/migrate-reporting-clients.sql first")
            client_id, message, original = check_registration(observer, conn)
            check_replay(settings, conn, client_id, message, original)
        print("PASS: registration event, database row, duplicate rejection and replay", flush=True)
    finally:
        observer.close()


if __name__ == "__main__":
    main()
