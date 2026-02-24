import { Injectable } from '@nestjs/common';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';
import { v4 as uuidv4 } from 'uuid';
import { DatabaseService } from '../database/database.service';
import { RealtimeGateway } from '../realtime/realtime.gateway';
import { AppCatalogSyncDto } from './dto/app-catalog-sync.dto';
import { DailySnapshotDto } from './dto/daily-snapshot.dto';
import { ForegroundSwitchDto } from './dto/foreground-switch.dto';
import { HeartbeatDto } from './dto/heartbeat.dto';
import { ScreenStateDto } from './dto/screen-state.dto';

dayjs.extend(utc);
dayjs.extend(timezone);
const CHINA_TIMEZONE = 'Asia/Shanghai';

interface RealtimeStateRow {
  device_id: string;
  package_name: string | null;
  app_name: string | null;
  icon_hash: string | null;
  foreground_started_at: number | null;
  today_usage_ms: number;
  is_screen_locked: number | null;
  screen_state_updated_at: number | null;
  updated_at: number;
  last_heartbeat_at: number | null;
  is_online: number;
}

interface AppCatalogRow {
  app_name: string;
  icon_hash: string | null;
  icon_base64: string | null;
}

interface DailyDeviceStatsRow {
  total_notification_count: number;
  unlock_count: number;
  snapshot_ts: number;
}

@Injectable()
export class IngestService {
  constructor(
    private readonly db: DatabaseService,
    private readonly realtimeGateway: RealtimeGateway,
  ) {}

  ingestHeartbeat(deviceId: string, dto: HeartbeatDto) {
    const now = Date.now();
    const state = this.db.get<RealtimeStateRow>(
      `SELECT * FROM device_realtime_state WHERE device_id = @deviceId`,
      { deviceId },
    );

    const wasOnline = Boolean(state?.is_online);

    if (state) {
      this.db.run(
        `UPDATE device_realtime_state
         SET last_heartbeat_at = @lastHeartbeatAt,
             updated_at = @updatedAt,
             is_online = 1
         WHERE device_id = @deviceId`,
        {
          deviceId,
          // Use server-side timestamp for liveness checks to avoid device clock drift.
          lastHeartbeatAt: now,
          updatedAt: now,
        },
      );
    } else {
      this.db.run(
        `INSERT INTO device_realtime_state (
           device_id, today_usage_ms, updated_at, last_heartbeat_at, is_online
         ) VALUES (
           @deviceId, 0, @updatedAt, @lastHeartbeatAt, 1
        )`,
        {
          deviceId,
          updatedAt: now,
          lastHeartbeatAt: now,
        },
      );
    }

    this.db.run(
      `UPDATE devices
       SET last_seen_at = @lastSeenAt, updated_at = @updatedAt
       WHERE id = @deviceId`,
      {
        deviceId,
        lastSeenAt: now,
        updatedAt: now,
      },
    );

    if (!wasOnline) {
      this.realtimeGateway.emitToWeb('device.online', {
        deviceId,
        ts: now,
      });
    }

    return {
      ok: true,
      serverTs: now,
      heartbeatAcceptedAt: now,
    };
  }

  ingestForegroundSwitch(deviceId: string, dto: ForegroundSwitchDto) {
    const now = Date.now();
    const eventStatDate = this.toChinaStatDate(dto.ts);
    const todayStatDate = this.toChinaStatDate(now);
    const shouldUpdateTodayUsage = eventStatDate === todayStatDate;
    const normalizedTodayUsageMs = this.clampTodayUsageMs(
      dto.todayUsageMsAtSwitch,
      now,
    );
    const catalog = this.db.get<AppCatalogRow>(
      `SELECT app_name, icon_hash, icon_base64
       FROM app_catalog
       WHERE device_id = @deviceId AND package_name = @packageName`,
      {
        deviceId,
        packageName: dto.packageName,
      },
    );

    const nextIconHash = dto.iconHash ?? catalog?.icon_hash ?? null;
    const nextIconBase64 = dto.iconBase64 ?? catalog?.icon_base64 ?? null;

    this.db.transaction(() => {
      this.db.run(
        `INSERT INTO app_catalog (
           id, device_id, package_name, app_name, icon_hash, icon_base64, created_at, updated_at
         ) VALUES (
           @id, @deviceId, @packageName, @appName, @iconHash, @iconBase64, @createdAt, @updatedAt
         )
         ON CONFLICT(device_id, package_name)
         DO UPDATE SET
           app_name = excluded.app_name,
           icon_hash = COALESCE(excluded.icon_hash, app_catalog.icon_hash),
           icon_base64 = COALESCE(excluded.icon_base64, app_catalog.icon_base64),
           updated_at = excluded.updated_at`,
        {
          id: uuidv4(),
          deviceId,
          packageName: dto.packageName,
          appName: dto.appName,
          iconHash: nextIconHash,
          iconBase64: nextIconBase64,
          createdAt: now,
          updatedAt: now,
        },
      );

      this.db.run(
        `INSERT INTO foreground_events (
           id, device_id, ts, package_name, app_name, icon_hash, today_usage_ms_at_switch
         ) VALUES (
           @id, @deviceId, @ts, @packageName, @appName, @iconHash, @todayUsageMsAtSwitch
         )`,
        {
          id: uuidv4(),
          deviceId,
          ts: dto.ts,
          packageName: dto.packageName,
          appName: dto.appName,
          iconHash: nextIconHash,
          todayUsageMsAtSwitch: dto.todayUsageMsAtSwitch,
        },
      );

      this.db.run(
        `INSERT INTO device_realtime_state (
           device_id, package_name, app_name, icon_hash, foreground_started_at,
           today_usage_ms, is_screen_locked, screen_state_updated_at, updated_at,
           last_heartbeat_at, is_online
         ) VALUES (
           @deviceId, @packageName, @appName, @iconHash, @foregroundStartedAt,
           @todayUsageMs, 0, @screenStateUpdatedAt, @updatedAt, @lastHeartbeatAt, 1
         )
         ON CONFLICT(device_id)
         DO UPDATE SET
           package_name = excluded.package_name,
           app_name = excluded.app_name,
           icon_hash = excluded.icon_hash,
           foreground_started_at = excluded.foreground_started_at,
           today_usage_ms = CASE
             WHEN @shouldUpdateTodayUsage = 1 THEN excluded.today_usage_ms
             ELSE device_realtime_state.today_usage_ms
           END,
           is_screen_locked = 0,
           screen_state_updated_at = excluded.screen_state_updated_at,
           updated_at = excluded.updated_at,
           last_heartbeat_at = excluded.last_heartbeat_at,
           is_online = 1`,
        {
          deviceId,
          packageName: dto.packageName,
          appName: dto.appName,
          iconHash: nextIconHash,
          foregroundStartedAt: dto.ts,
          todayUsageMs: normalizedTodayUsageMs,
          screenStateUpdatedAt: now,
          updatedAt: now,
          lastHeartbeatAt: now,
          shouldUpdateTodayUsage: shouldUpdateTodayUsage ? 1 : 0,
        },
      );
    });

    const payload = {
      deviceId,
      ts: dto.ts,
      packageName: dto.packageName,
      appName: dto.appName,
      iconHash: nextIconHash,
      iconBase64: nextIconBase64,
      todayUsageMsAtSwitch: shouldUpdateTodayUsage ? normalizedTodayUsageMs : 0,
      foregroundStartedAt: dto.ts,
    };

    this.realtimeGateway.emitToWeb('foreground.updated', payload);

    return {
      ok: true,
      receivedAt: now,
    };
  }

  ingestScreenState(deviceId: string, dto: ScreenStateDto) {
    const now = Date.now();
    const state = this.db.get<RealtimeStateRow>(
      `SELECT * FROM device_realtime_state WHERE device_id = @deviceId`,
      { deviceId },
    );
    const wasOnline = Boolean(state?.is_online);

    if (state) {
      this.db.run(
        `UPDATE device_realtime_state
         SET is_screen_locked = @isScreenLocked,
             screen_state_updated_at = @screenStateUpdatedAt,
             updated_at = @updatedAt,
             last_heartbeat_at = @lastHeartbeatAt,
             is_online = 1
         WHERE device_id = @deviceId`,
        {
          deviceId,
          isScreenLocked: dto.isScreenLocked ? 1 : 0,
          screenStateUpdatedAt: dto.ts,
          updatedAt: now,
          lastHeartbeatAt: now,
        },
      );
    } else {
      this.db.run(
        `INSERT INTO device_realtime_state (
           device_id, today_usage_ms, is_screen_locked, screen_state_updated_at,
           updated_at, last_heartbeat_at, is_online
         ) VALUES (
           @deviceId, 0, @isScreenLocked, @screenStateUpdatedAt,
           @updatedAt, @lastHeartbeatAt, 1
         )`,
        {
          deviceId,
          isScreenLocked: dto.isScreenLocked ? 1 : 0,
          screenStateUpdatedAt: dto.ts,
          updatedAt: now,
          lastHeartbeatAt: now,
        },
      );
    }

    this.db.run(
      `UPDATE devices
       SET last_seen_at = @lastSeenAt, updated_at = @updatedAt
       WHERE id = @deviceId`,
      {
        deviceId,
        lastSeenAt: now,
        updatedAt: now,
      },
    );

    if (!wasOnline) {
      this.realtimeGateway.emitToWeb('device.online', {
        deviceId,
        ts: now,
      });
    }

    this.realtimeGateway.emitToWeb('screen.state.updated', {
      deviceId,
      isScreenLocked: dto.isScreenLocked,
      ts: dto.ts,
    });

    return {
      ok: true,
      receivedAt: now,
    };
  }

  ingestAppCatalogSync(deviceId: string, dto: AppCatalogSyncDto) {
    const now = Date.now();
    const state = this.db.get<RealtimeStateRow>(
      `SELECT * FROM device_realtime_state WHERE device_id = @deviceId`,
      { deviceId },
    );
    const wasOnline = Boolean(state?.is_online);

    this.db.transaction(() => {
      for (const app of dto.apps) {
        const catalog = this.db.get<AppCatalogRow>(
          `SELECT app_name, icon_hash, icon_base64
           FROM app_catalog
           WHERE device_id = @deviceId AND package_name = @packageName`,
          {
            deviceId,
            packageName: app.packageName,
          },
        );

        const nextIconHash = app.iconHash ?? catalog?.icon_hash ?? null;
        const nextIconBase64 = app.iconBase64 ?? catalog?.icon_base64 ?? null;

        this.db.run(
          `INSERT INTO app_catalog (
             id, device_id, package_name, app_name, icon_hash, icon_base64, created_at, updated_at
           ) VALUES (
             @id, @deviceId, @packageName, @appName, @iconHash, @iconBase64, @createdAt, @updatedAt
           )
           ON CONFLICT(device_id, package_name)
           DO UPDATE SET
             app_name = excluded.app_name,
             icon_hash = COALESCE(excluded.icon_hash, app_catalog.icon_hash),
             icon_base64 = COALESCE(excluded.icon_base64, app_catalog.icon_base64),
             updated_at = excluded.updated_at`,
          {
            id: uuidv4(),
            deviceId,
            packageName: app.packageName,
            appName: app.appName,
            iconHash: nextIconHash,
            iconBase64: nextIconBase64,
            createdAt: now,
            updatedAt: now,
          },
        );
      }

      this.db.run(
        `INSERT INTO device_realtime_state (
           device_id, today_usage_ms, updated_at, last_heartbeat_at, is_online
         ) VALUES (
           @deviceId, 0, @updatedAt, @lastHeartbeatAt, 1
         )
         ON CONFLICT(device_id)
         DO UPDATE SET
           updated_at = excluded.updated_at,
           last_heartbeat_at = excluded.last_heartbeat_at,
           is_online = 1`,
        {
          deviceId,
          updatedAt: now,
          lastHeartbeatAt: now,
        },
      );

      this.db.run(
        `UPDATE devices
         SET last_seen_at = @lastSeenAt, updated_at = @updatedAt
         WHERE id = @deviceId`,
        {
          deviceId,
          lastSeenAt: now,
          updatedAt: now,
        },
      );
    });

    if (!wasOnline) {
      this.realtimeGateway.emitToWeb('device.online', {
        deviceId,
        ts: now,
      });
    }

    this.realtimeGateway.emitToWeb('app.catalog.synced', {
      deviceId,
      ts: dto.ts,
      count: dto.apps.length,
    });

    return {
      ok: true,
      syncedCount: dto.apps.length,
      receivedAt: now,
    };
  }

  ingestDailySnapshot(deviceId: string, dto: DailySnapshotDto) {
    const now = Date.now();
    const statDate = this.toChinaStatDate(dto.ts);
    const todayStatDate = this.toChinaStatDate(now);
    const shouldUpdateRealtimeTodayUsage = statDate === todayStatDate;
    let latestStats: DailyDeviceStatsRow | undefined;

    this.db.transaction(() => {
      this.db.run(
        `INSERT INTO daily_device_stats (
           id, device_id, stat_date, timezone, total_notification_count,
           unlock_count, snapshot_ts
         ) VALUES (
           @id, @deviceId, @statDate, @timezone, @totalNotificationCount,
           @unlockCount, @snapshotTs
         )
         ON CONFLICT(device_id, stat_date)
         DO UPDATE SET
           timezone = CASE
             WHEN excluded.snapshot_ts >= daily_device_stats.snapshot_ts THEN excluded.timezone
             ELSE daily_device_stats.timezone
           END,
           total_notification_count = MAX(daily_device_stats.total_notification_count, excluded.total_notification_count),
           unlock_count = MAX(daily_device_stats.unlock_count, excluded.unlock_count),
           snapshot_ts = MAX(daily_device_stats.snapshot_ts, excluded.snapshot_ts)`,
        {
          id: uuidv4(),
          deviceId,
          statDate,
          timezone: dto.timezone,
          totalNotificationCount: dto.totalNotificationCount,
          unlockCount: dto.unlockCount,
          snapshotTs: dto.ts,
        },
      );

      for (const app of dto.apps) {
        this.db.run(
          `INSERT INTO daily_app_usage_stats (
             id, device_id, stat_date, package_name, usage_ms
           ) VALUES (
             @id, @deviceId, @statDate, @packageName, @usageMs
           )
           ON CONFLICT(device_id, stat_date, package_name)
           DO UPDATE SET usage_ms = excluded.usage_ms`,
          {
            id: uuidv4(),
            deviceId,
            statDate,
            packageName: app.packageName,
            usageMs: app.usageMsToday,
          },
        );
      }

      const currentState = this.db.get<{ package_name: string | null }>(
        `SELECT package_name FROM device_realtime_state WHERE device_id = @deviceId`,
        { deviceId },
      );

      if (shouldUpdateRealtimeTodayUsage && currentState?.package_name) {
        const currentUsage = dto.apps.find(
          (app) => app.packageName === currentState.package_name,
        );

        if (currentUsage) {
          const normalizedTodayUsageMs = this.clampTodayUsageMs(
            currentUsage.usageMsToday,
            now,
          );
          this.db.run(
            `UPDATE device_realtime_state
             SET today_usage_ms = @todayUsageMs,
                 updated_at = @updatedAt
             WHERE device_id = @deviceId`,
            {
              deviceId,
              todayUsageMs: normalizedTodayUsageMs,
              updatedAt: now,
            },
          );
        }
      }

      latestStats = this.db.get<DailyDeviceStatsRow>(
        `SELECT total_notification_count, unlock_count, snapshot_ts
         FROM daily_device_stats
         WHERE device_id = @deviceId AND stat_date = @statDate`,
        {
          deviceId,
          statDate,
        },
      );
    });

    this.realtimeGateway.emitToWeb('stats.updated', {
      deviceId,
      ts: latestStats?.snapshot_ts ?? dto.ts,
      statDate,
      totalNotificationCount:
        latestStats?.total_notification_count ?? dto.totalNotificationCount,
      unlockCount: latestStats?.unlock_count ?? dto.unlockCount,
      apps:
        statDate === todayStatDate
          ? dto.apps.map((app) => ({
              ...app,
              usageMsToday: this.clampTodayUsageMs(app.usageMsToday, now),
            }))
          : dto.apps,
    });

    return {
      ok: true,
      statDate,
      receivedAt: now,
    };
  }

  private toChinaStatDate(epochMs: number): string {
    return dayjs(epochMs).tz(CHINA_TIMEZONE).format('YYYY-MM-DD');
  }

  private elapsedMsSinceChinaMidnight(nowEpochMs: number): number {
    const nowInChina = dayjs(nowEpochMs).tz(CHINA_TIMEZONE);
    return nowInChina.diff(nowInChina.startOf('day'), 'millisecond');
  }

  private clampTodayUsageMs(usageMs: number, nowEpochMs: number): number {
    const normalizedUsageMs = Number.isFinite(usageMs)
      ? Math.max(0, Math.floor(usageMs))
      : 0;
    return Math.min(
      normalizedUsageMs,
      this.elapsedMsSinceChinaMidnight(nowEpochMs),
    );
  }
}
