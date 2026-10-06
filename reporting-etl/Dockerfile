FROM python:3.12-slim

# Logs straight to docker logs, no .pyc files in the image.
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# Dependencies before the code, so a code change doesn't reinstall them.
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY reporting_etl ./reporting_etl

RUN useradd --system --no-create-home etl
USER etl

ENTRYPOINT ["python", "-m", "reporting_etl"]
