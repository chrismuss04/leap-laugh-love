# Lean runner for the E2E suite in CI: Node, the suite's npm dependencies, and Chromium's
# headless shell with the system libraries it needs - nothing else. The official Playwright image
# ships all three browsers and is several times the size.
#
# The dependencies live in /node_modules, above the /e2e directory the checkout is mounted on,
# so Node, tsc and eslint all find them by walking up - the mounted source never needs an install.
# Jenkins tags the image by package-lock.json's hash and only rebuilds when that changes.
FROM node:24-bookworm-slim

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PATH=/node_modules/.bin:$PATH \
    NPM_CONFIG_UPDATE_NOTIFIER=false

WORKDIR /
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund \
    && playwright install --with-deps --only-shell chromium \
    && chmod -R a+rX /ms-playwright \
    && rm -rf /var/lib/apt/lists/* /root/.npm /root/.cache /tmp/*

WORKDIR /e2e
