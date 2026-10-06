import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { APIRequestContext, expect } from '@playwright/test';

const execute = promisify(execFile);
export const apps = ['iam-app', 'account-app', 'order-app', 'market-data-app'];
const ports: Record<string, number> = {
  'iam-app': 8081, 'account-app': 8082, 'order-app': 8084, 'market-data-app': 8083
};

/** Controls only the unique, disposable stack created by test-trade-recovery.sh. */
export class RecoveryStack {
  private args: string[] = [];
  private checked = false;

  constructor(private readonly request: APIRequestContext) {}

  async verifyIsolation(): Promise<void> {
    const project = process.env.RECOVERY_PROJECT ?? '';
    const root = process.env.RECOVERY_REPO_ROOT;
    if (!/^leap-recovery-[a-f0-9]{32}$/.test(project) || !root ||
        project !== process.env.COMPOSE_PROJECT || process.env.DB_VOLUME_NAME !== `${project}_db_data`) {
      throw new Error('Run recovery tests through scripts/test-trade-recovery.sh on the Linux Docker host.');
    }
    this.args = ['compose', '--env-file', '/dev/null', '-p', project,
      '-f', path.join(root, 'docker-compose.yml'), '-f', path.join(root, 'docker-compose.e2e.yml')];
    const config = JSON.parse(await this.command(['config', '--format', 'json']));
    expect(config.volumes.db_data.name).toBe(`${project}_db_data`);
    expect(Boolean(config.volumes.db_data.external)).toBe(false);
    const id = (await this.command(['ps', '-q', 'db'])).trim();
    expect(id).not.toBe('');
    const container = JSON.parse((await execute('docker', ['inspect', id])).stdout)[0];
    expect(container.Config.Labels['com.docker.compose.project']).toBe(project);
    expect(container.Mounts).toEqual(expect.arrayContaining([
      expect.objectContaining({ Type: 'volume', Name: `${project}_db_data`, Destination: '/var/lib/postgresql/data' })
    ]));
    this.checked = true;
    expect(await this.sql("SELECT current_setting('fsync') || '|' || current_setting('synchronous_commit') || '|' || current_setting('full_page_writes')"))
      .toBe('on|on|on');
  }

  private async command(args: string[]): Promise<string> {
    return (await execute('docker', [...this.args, ...args], { timeout: 180_000, maxBuffer: 4 * 1024 * 1024 })).stdout;
  }

  async compose(...args: string[]): Promise<string> {
    if (!this.checked) throw new Error('Isolation must be verified before controlling containers.');
    return this.command(args);
  }

  async sql(query: string): Promise<string> {
    return (await this.compose('exec', '-T', 'db', 'psql', '-U', 'paysprint', '-d', 'paysprint',
      '-At', '-v', 'ON_ERROR_STOP=1', '-c', query)).trim();
  }

  async url(service: string): Promise<string> {
    const binding = (await this.compose('port', service, String(ports[service]))).trim().split('\n')[0];
    return `http://127.0.0.1:${binding.split(':').at(-1)}`;
  }

  async json<T>(service: string, endpoint: string, token?: string, data?: unknown): Promise<T> {
    const response = await this.request.fetch(`${await this.url(service)}${endpoint}`, {
      method: data === undefined ? 'GET' : 'POST', data,
      headers: token ? { Authorization: `Bearer ${token}` } : {}, timeout: 15_000
    });
    expect(response.ok(), `${service} ${endpoint}: ${response.status()}`).toBe(true);
    return response.json();
  }

  async login(email: string): Promise<string> {
    const response = await this.json<{ accessToken: string }>('iam-app', '/api/iam/auth/login', undefined,
      { email, password: 'Password123!' });
    return response.accessToken;
  }

  async healthy(): Promise<void> {
    for (const service of apps) {
      await expect.poll(async () => {
        try {
          return (await this.request.get(`${await this.url(service)}/actuator/health`, { timeout: 5_000 })).ok();
        } catch { return false; }
      }, { message: `${service} recovers`, timeout: 120_000, intervals: [1000, 2000] }).toBe(true);
    }
  }

  async snapshot(accountId: string): Promise<Record<string, unknown[]>> {
    if (!/^[a-f0-9-]{36}$/i.test(accountId)) throw new Error('Invalid account ID');
    // Capture complete records, including IDs, timestamps and money amounts, in stable order.
    const query = `SELECT jsonb_build_object(
      'orders', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY order_id), '[]') FROM trading.orders t WHERE account_id='${accountId}'),
      'executions', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY execution_id), '[]') FROM trading.executions t WHERE order_id IN (SELECT order_id FROM trading.orders WHERE account_id='${accountId}')),
      'cash', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY cash_ledger_id), '[]') FROM trading.cash_ledger t WHERE account_id='${accountId}'),
      'holdings', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY instrument_id), '[]') FROM trading.positions t WHERE account_id='${accountId}'),
      'movements', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY movement_id), '[]') FROM trading.position_movements t WHERE account_id='${accountId}')
    )`;
    return JSON.parse(await this.sql(query));
  }

  async failedDeploy(): Promise<void> {
    // Use the exact previous image with a deliberately invalid startup command.
    const oldId = (await this.compose('ps', '-q', 'order-app')).trim();
    const image = (await execute('docker', ['inspect', '--format', '{{.Image}}', oldId])).stdout.trim();
    await this.compose('stop', 'order-app');
    const attempt = await execute('docker', [...this.args, 'run', '--rm', '--no-deps',
      '--entrypoint', '/bin/sh', 'order-app', '-c', 'exit 42'], { timeout: 30_000 })
      .then(() => 0, (error: { code?: number | string }) => error.code);
    expect(attempt, 'candidate deployment must actually fail').toBe(42);
    await this.compose('up', '-d', '--no-deps', '--no-build', '--force-recreate', 'order-app');
    const restoredId = (await this.compose('ps', '-q', 'order-app')).trim();
    expect(restoredId).not.toBe(oldId);
    expect((await execute('docker', ['inspect', '--format', '{{.Image}}', restoredId])).stdout.trim()).toBe(image);
  }
}
