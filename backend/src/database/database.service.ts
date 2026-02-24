import * as fs from 'node:fs';
import * as path from 'node:path';
import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import Database from 'better-sqlite3';
import { AppConfig } from '../config/app-config';

@Injectable()
export class DatabaseService implements OnModuleDestroy {
  private readonly db: Database.Database;

  constructor(private readonly configService: ConfigService) {
    const storage = this.configService.getOrThrow<AppConfig['storage']>('storage');
    const sqlitePath = path.resolve(process.cwd(), storage.sqlitePath);
    fs.mkdirSync(path.dirname(sqlitePath), { recursive: true });
    this.db = new Database(sqlitePath);
    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.initializeSchema();
  }

  onModuleDestroy(): void {
    this.db.close();
  }

  run(sql: string, params?: Record<string, unknown>): Database.RunResult {
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

  private initializeSchema(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY,
        device_code TEXT NOT NULL UNIQUE,
        device_name TEXT NOT NULL,
        manufacturer TEXT NOT NULL,
        model TEXT NOT NULL,
        android_version TEXT NOT NULL,
        app_version TEXT NOT NULL,
        root_enabled INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        last_seen_at INTEGER
      );

      CREATE TABLE IF NOT EXISTS device_tokens (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        refresh_token_hash TEXT NOT NULL,
        revoked INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE
      );
      CREATE INDEX IF NOT EXISTS idx_device_tokens_device_id ON device_tokens(device_id);

      CREATE TABLE IF NOT EXISTS app_catalog (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        package_name TEXT NOT NULL,
        app_name TEXT NOT NULL,
        icon_hash TEXT,
        icon_base64 TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        UNIQUE(device_id, package_name),
        FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE
      );

      CREATE TABLE IF NOT EXISTS foreground_events (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        ts INTEGER NOT NULL,
        package_name TEXT NOT NULL,
        app_name TEXT NOT NULL,
        icon_hash TEXT,
        today_usage_ms_at_switch INTEGER NOT NULL,
        FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE
      );
      CREATE INDEX IF NOT EXISTS idx_foreground_events_device_ts ON foreground_events(device_id, ts DESC);

      CREATE TABLE IF NOT EXISTS device_realtime_state (
        device_id TEXT PRIMARY KEY,
        package_name TEXT,
        app_name TEXT,
        icon_hash TEXT,
        foreground_started_at INTEGER,
        today_usage_ms INTEGER NOT NULL DEFAULT 0,
        is_screen_locked INTEGER NOT NULL DEFAULT 0,
        screen_state_updated_at INTEGER,
        updated_at INTEGER NOT NULL,
        last_heartbeat_at INTEGER,
        is_online INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE
      );

      CREATE TABLE IF NOT EXISTS daily_device_stats (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        stat_date TEXT NOT NULL,
        timezone TEXT NOT NULL,
        total_notification_count INTEGER NOT NULL,
        unlock_count INTEGER NOT NULL,
        snapshot_ts INTEGER NOT NULL,
        UNIQUE(device_id, stat_date),
        FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE
      );
      CREATE INDEX IF NOT EXISTS idx_daily_device_stats_device_date ON daily_device_stats(device_id, stat_date DESC);

      CREATE TABLE IF NOT EXISTS daily_app_usage_stats (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        stat_date TEXT NOT NULL,
        package_name TEXT NOT NULL,
        usage_ms INTEGER NOT NULL,
        UNIQUE(device_id, stat_date, package_name),
        FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE
      );
      CREATE INDEX IF NOT EXISTS idx_daily_app_usage_device_date ON daily_app_usage_stats(device_id, stat_date DESC);

      CREATE TABLE IF NOT EXISTS screenshot_requests (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        status TEXT NOT NULL,
        requested_at INTEGER NOT NULL,
        completed_at INTEGER,
        error_message TEXT,
        FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE
      );
      CREATE INDEX IF NOT EXISTS idx_screenshot_requests_device_requested ON screenshot_requests(device_id, requested_at DESC);
    `);

    this.ensureRealtimeStateColumns();
  }

  private ensureRealtimeStateColumns(): void {
    const columns = this.all<{ name: string }>(
      `PRAGMA table_info(device_realtime_state)`,
    );
    const existing = new Set(columns.map((column) => column.name));

    if (!existing.has('is_screen_locked')) {
      this.db.exec(
        `ALTER TABLE device_realtime_state ADD COLUMN is_screen_locked INTEGER NOT NULL DEFAULT 0`,
      );
    }

    if (!existing.has('screen_state_updated_at')) {
      this.db.exec(
        `ALTER TABLE device_realtime_state ADD COLUMN screen_state_updated_at INTEGER`,
      );
    }
  }
}
