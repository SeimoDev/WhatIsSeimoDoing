import Database from 'better-sqlite3';
import { RealtimeGateway } from '../realtime/realtime.gateway';
import { AppCatalogSyncDto } from './dto/app-catalog-sync.dto';
import { DailySnapshotDto } from './dto/daily-snapshot.dto';
import { ScreenStateDto } from './dto/screen-state.dto';

jest.mock('uuid', () => ({
  v4: () => 'test-uuid',
}));

// Load service after uuid mock to avoid jest ESM parse issues in uuid package.
const { IngestService } = require('./ingest.service') as {
  IngestService: new (
    db: unknown,
    realtimeGateway: RealtimeGateway,
  ) => {
    ingestDailySnapshot: (deviceId: string, dto: DailySnapshotDto) => unknown;
    ingestScreenState: (deviceId: string, dto: ScreenStateDto) => unknown;
    ingestAppCatalogSync: (deviceId: string, dto: AppCatalogSyncDto) => unknown;
  };
};

type IngestServiceInstance = InstanceType<typeof IngestService>;

class InMemoryDatabase {
  private readonly db = new Database(':memory:');

  constructor() {
    this.db.exec(`
      CREATE TABLE devices (
        id TEXT PRIMARY KEY,
        last_seen_at INTEGER,
        updated_at INTEGER
      );

      CREATE TABLE daily_device_stats (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        stat_date TEXT NOT NULL,
        timezone TEXT NOT NULL,
        total_notification_count INTEGER NOT NULL,
        unlock_count INTEGER NOT NULL,
        snapshot_ts INTEGER NOT NULL,
        UNIQUE(device_id, stat_date)
      );

      CREATE TABLE daily_app_usage_stats (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        stat_date TEXT NOT NULL,
        package_name TEXT NOT NULL,
        usage_ms INTEGER NOT NULL,
        UNIQUE(device_id, stat_date, package_name)
      );

      CREATE TABLE device_realtime_state (
        device_id TEXT PRIMARY KEY,
        package_name TEXT,
        today_usage_ms INTEGER NOT NULL DEFAULT 0,
        is_screen_locked INTEGER NOT NULL DEFAULT 0,
        screen_state_updated_at INTEGER,
        updated_at INTEGER NOT NULL DEFAULT 0,
        last_heartbeat_at INTEGER,
        is_online INTEGER NOT NULL DEFAULT 0
      );

      CREATE TABLE app_catalog (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        package_name TEXT NOT NULL,
        app_name TEXT NOT NULL,
        icon_hash TEXT,
        icon_base64 TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        UNIQUE(device_id, package_name)
      );
    `);
  }

  run(sql: string, params?: Record<string, unknown>) {
    return this.db.prepare(sql).run(params ?? {});
  }

  get<T>(sql: string, params?: Record<string, unknown>): T | undefined {
    return this.db.prepare(sql).get(params ?? {}) as T | undefined;
  }

  all<T>(sql: string, params?: Record<string, unknown>): T[] {
    return this.db.prepare(sql).all(params ?? {}) as T[];
  }

  transaction<T>(fn: () => T): T {
    return this.db.transaction(fn)();
  }

  close() {
    this.db.close();
  }
}

function buildSnapshot(
  ts: number,
  totalNotificationCount: number,
  unlockCount: number,
): DailySnapshotDto {
  return {
    ts,
    timezone: 'Asia/Shanghai',
    totalNotificationCount,
    unlockCount,
    apps: [
      {
        packageName: 'com.example.app',
        usageMsToday: 60_000,
      },
    ],
  };
}

describe('IngestService daily snapshot monotonic update', () => {
  let db: InMemoryDatabase;
  let emitToWeb: jest.Mock;
  let service: IngestServiceInstance;

  beforeEach(() => {
    db = new InMemoryDatabase();
    emitToWeb = jest.fn();
    db.run(
      `INSERT INTO devices (id, last_seen_at, updated_at)
       VALUES (@id, 0, 0)`,
      { id: 'device-1' },
    );
    db.run(
      `INSERT INTO devices (id, last_seen_at, updated_at)
       VALUES (@id, 0, 0)`,
      { id: 'device-2' },
    );
    db.run(
      `INSERT INTO devices (id, last_seen_at, updated_at)
       VALUES (@id, 0, 0)`,
      { id: 'device-3' },
    );
    db.run(
      `INSERT INTO devices (id, last_seen_at, updated_at)
       VALUES (@id, 0, 0)`,
      { id: 'device-4' },
    );
    service = new IngestService(
      db as unknown as any,
      { emitToWeb } as unknown as RealtimeGateway,
    );
  });

  afterEach(() => {
    db.close();
  });

  it('keeps unlock count monotonic when an older snapshot arrives later', () => {
    const deviceId = 'device-1';
    const nowTs = Date.now();
    const oldTs = nowTs - 10_000;

    service.ingestDailySnapshot(deviceId, buildSnapshot(nowTs, 10, 5));
    service.ingestDailySnapshot(deviceId, buildSnapshot(oldTs, 1, 0));

    const row = db.get<{
      total_notification_count: number;
      unlock_count: number;
      snapshot_ts: number;
    }>(
      `SELECT total_notification_count, unlock_count, snapshot_ts
       FROM daily_device_stats
       WHERE device_id = @deviceId`,
      { deviceId },
    );

    expect(row).toBeDefined();
    expect(row?.total_notification_count).toBe(10);
    expect(row?.unlock_count).toBe(5);
    expect(row?.snapshot_ts).toBe(nowTs);
  });

  it('emits stats.updated with final persisted values instead of stale payload values', () => {
    const deviceId = 'device-2';
    const nowTs = Date.now();
    const oldTs = nowTs - 10_000;

    service.ingestDailySnapshot(deviceId, buildSnapshot(nowTs, 12, 6));
    service.ingestDailySnapshot(deviceId, buildSnapshot(oldTs, 2, 0));

    expect(emitToWeb).toHaveBeenCalledTimes(2);

    const [eventName, payload] = emitToWeb.mock.calls[1] as [
      string,
      {
        ts: number;
        totalNotificationCount: number;
        unlockCount: number;
      },
    ];

    expect(eventName).toBe('stats.updated');
    expect(payload.totalNotificationCount).toBe(12);
    expect(payload.unlockCount).toBe(6);
    expect(payload.ts).toBe(nowTs);
  });

  it('ingestScreenState writes realtime lock state and emits screen.state.updated', () => {
    const deviceId = 'device-3';
    const nowTs = Date.now();

    service.ingestScreenState(deviceId, {
      ts: nowTs,
      isScreenLocked: true,
    });

    const row = db.get<{
      is_screen_locked: number;
      screen_state_updated_at: number;
      is_online: number;
      last_heartbeat_at: number;
    }>(
      `SELECT is_screen_locked, screen_state_updated_at, is_online, last_heartbeat_at
       FROM device_realtime_state
       WHERE device_id = @deviceId`,
      { deviceId },
    );

    expect(row).toBeDefined();
    expect(row?.is_screen_locked).toBe(1);
    expect(row?.screen_state_updated_at).toBe(nowTs);
    expect(row?.is_online).toBe(1);
    expect(typeof row?.last_heartbeat_at).toBe('number');

    const [, payload] = emitToWeb.mock.calls.find(
      ([eventName]) => eventName === 'screen.state.updated',
    ) as [string, { deviceId: string; isScreenLocked: boolean; ts: number }];

    expect(payload.deviceId).toBe(deviceId);
    expect(payload.isScreenLocked).toBe(true);
    expect(payload.ts).toBe(nowTs);
  });

  it('ingestAppCatalogSync upserts app list and keeps previous icon when omitted', () => {
    const deviceId = 'device-4';
    const ts = Date.now();

    service.ingestAppCatalogSync(deviceId, {
      ts,
      apps: [
        {
          packageName: 'com.example.foo',
          appName: 'Foo',
          iconHash: 'hash-1',
          iconBase64: 'base64-1',
        },
      ],
    });

    service.ingestAppCatalogSync(deviceId, {
      ts: ts + 1000,
      apps: [
        {
          packageName: 'com.example.foo',
          appName: 'Foo Renamed',
        },
      ],
    });

    const row = db.get<{
      app_name: string;
      icon_hash: string | null;
      icon_base64: string | null;
    }>(
      `SELECT app_name, icon_hash, icon_base64
       FROM app_catalog
       WHERE device_id = @deviceId AND package_name = @packageName`,
      {
        deviceId,
        packageName: 'com.example.foo',
      },
    );

    expect(row).toBeDefined();
    expect(row?.app_name).toBe('Foo Renamed');
    expect(row?.icon_hash).toBe('hash-1');
    expect(row?.icon_base64).toBe('base64-1');
  });
});
