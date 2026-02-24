import { Injectable, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';
import { AppConfig } from '../config/app-config';
import { DatabaseService } from '../database/database.service';

dayjs.extend(utc);
dayjs.extend(timezone);
const CHINA_TIMEZONE = 'Asia/Shanghai';

interface DeviceListRow {
  id: string;
  device_name: string;
  manufacturer: string;
  model: string;
  android_version: string;
  app_version: string;
  root_enabled: number;
  last_seen_at: number | null;
  package_name: string | null;
  app_name: string | null;
  icon_hash: string | null;
  icon_base64: string | null;
  foreground_started_at: number | null;
  today_usage_ms: number;
  is_screen_locked: number | null;
  last_heartbeat_at: number | null;
  is_online: number;
}

interface AppUsageRow {
  package_name: string;
  usage_ms: number;
  app_name: string | null;
  icon_base64: string | null;
}

interface DailyStatsRow {
  stat_date: string;
  total_notification_count: number;
  unlock_count: number;
  snapshot_ts: number;
}

@Injectable()
export class DashboardService {
  private readonly realtime: AppConfig['realtime'];

  constructor(
    private readonly db: DatabaseService,
    private readonly configService: ConfigService,
  ) {
    this.realtime = this.configService.getOrThrow<AppConfig['realtime']>('realtime');
  }

  getDevices() {
    const rows = this.db.all<DeviceListRow>(
      `SELECT
         d.id,
         d.device_name,
         d.manufacturer,
         d.model,
         d.android_version,
         d.app_version,
         d.root_enabled,
         d.last_seen_at,
         rs.package_name,
         rs.app_name,
         rs.icon_hash,
         ac.icon_base64,
         rs.foreground_started_at,
         rs.today_usage_ms,
         rs.is_screen_locked,
         rs.last_heartbeat_at,
         rs.is_online
       FROM devices d
       LEFT JOIN device_realtime_state rs ON rs.device_id = d.id
       LEFT JOIN app_catalog ac
         ON ac.device_id = d.id
        AND ac.package_name = rs.package_name
       ORDER BY d.updated_at DESC`,
    );

    const now = Date.now();
    const threshold = now - this.realtime.heartbeatTimeoutSec * 1000;
    const elapsedMsSinceChinaMidnight = this.elapsedMsSinceChinaMidnight(now);

    return {
      devices: rows.map((row) => {
        const online =
          row.is_online === 1 &&
          Number(row.last_heartbeat_at ?? 0) >= threshold;

        return {
          deviceId: row.id,
          deviceName: row.device_name,
          manufacturer: row.manufacturer,
          model: row.model,
          androidVersion: row.android_version,
          appVersion: row.app_version,
          rootEnabled: row.root_enabled === 1,
          online,
          screenLocked: row.is_screen_locked === 1,
          lastSeenAt: row.last_seen_at,
          currentApp: row.package_name
            ? {
                packageName: row.package_name,
                appName: row.app_name,
                iconHash: row.icon_hash,
                iconBase64: row.icon_base64,
                foregroundStartedAt: row.foreground_started_at,
                todayUsageMs: this.clampTodayUsageMs(
                  row.today_usage_ms,
                  elapsedMsSinceChinaMidnight,
                ),
              }
            : null,
        };
      }),
      serverTs: now,
    };
  }

  getToday(deviceId: string) {
    this.ensureDeviceExists(deviceId);

    const now = Date.now();
    const today = this.toChinaStatDate(now);

    const deviceStats = this.db.get<{
      total_notification_count: number;
      unlock_count: number;
      snapshot_ts: number;
    }>(
      `SELECT total_notification_count, unlock_count, snapshot_ts
       FROM daily_device_stats
       WHERE device_id = @deviceId AND stat_date = @statDate`,
      {
        deviceId,
        statDate: today,
      },
    );

    const appRows = this.db.all<AppUsageRow>(
      `SELECT
         a.package_name,
         a.usage_ms,
         c.app_name,
         c.icon_base64
       FROM daily_app_usage_stats a
       LEFT JOIN app_catalog c
         ON c.device_id = a.device_id
        AND c.package_name = a.package_name
       WHERE a.device_id = @deviceId
         AND a.stat_date = @statDate
       ORDER BY a.usage_ms DESC`,
      {
        deviceId,
        statDate: today,
      },
    );

    const state = this.db.get<DeviceListRow>(
      `SELECT
         rs.package_name,
         rs.app_name,
         rs.icon_hash,
         ac.icon_base64,
         rs.foreground_started_at,
         rs.today_usage_ms,
         rs.is_screen_locked,
         rs.last_heartbeat_at,
         rs.is_online,
         d.id,
         d.device_name,
         d.manufacturer,
         d.model,
         d.android_version,
         d.app_version,
         d.root_enabled,
         d.last_seen_at
       FROM devices d
       LEFT JOIN device_realtime_state rs ON rs.device_id = d.id
       LEFT JOIN app_catalog ac
         ON ac.device_id = d.id
        AND ac.package_name = rs.package_name
       WHERE d.id = @deviceId`,
      { deviceId },
    );

    const threshold = now - this.realtime.heartbeatTimeoutSec * 1000;
    const online =
      state.is_online === 1 &&
      Number(state.last_heartbeat_at ?? 0) >= threshold;
    const elapsedMsSinceChinaMidnight = this.elapsedMsSinceChinaMidnight(now);

    return {
      deviceId,
      statDate: today,
      online,
      screenLocked: state.is_screen_locked === 1,
      currentApp: state.package_name
        ? {
            packageName: state.package_name,
            appName: state.app_name,
            iconHash: state.icon_hash,
            iconBase64: state.icon_base64,
            foregroundStartedAt: state.foreground_started_at,
            todayUsageMs: this.clampTodayUsageMs(
              state.today_usage_ms,
              elapsedMsSinceChinaMidnight,
            ),
          }
        : null,
      totalNotificationCount: deviceStats?.total_notification_count ?? 0,
      unlockCount: deviceStats?.unlock_count ?? 0,
      snapshotTs: deviceStats?.snapshot_ts ?? null,
      apps: appRows.map((row) => ({
        packageName: row.package_name,
        usageMs: this.clampTodayUsageMs(row.usage_ms, elapsedMsSinceChinaMidnight),
        appName: row.app_name ?? row.package_name,
        iconBase64: row.icon_base64,
      })),
      serverTs: now,
    };
  }

  getHistory(deviceId: string, days: number) {
    this.ensureDeviceExists(deviceId);

    const now = Date.now();
    const today = this.toChinaStatDate(now);
    const elapsedMsSinceChinaMidnight = this.elapsedMsSinceChinaMidnight(now);
    const boundedDays = Math.max(1, Math.min(30, days));
    const fromDate = dayjs(now)
      .tz(CHINA_TIMEZONE)
      .subtract(boundedDays - 1, 'day')
      .format('YYYY-MM-DD');

    const dailyStats = this.db.all<DailyStatsRow>(
      `SELECT stat_date, total_notification_count, unlock_count, snapshot_ts
       FROM daily_device_stats
       WHERE device_id = @deviceId
         AND stat_date >= @fromDate
       ORDER BY stat_date ASC`,
      {
        deviceId,
        fromDate,
      },
    );

    const appRows = this.db.all<{
      stat_date: string;
      package_name: string;
      usage_ms: number;
      app_name: string | null;
      icon_base64: string | null;
    }>(
      `SELECT
         a.stat_date,
         a.package_name,
         a.usage_ms,
         c.app_name,
         c.icon_base64
       FROM daily_app_usage_stats a
       LEFT JOIN app_catalog c
         ON c.device_id = a.device_id
        AND c.package_name = a.package_name
       WHERE a.device_id = @deviceId
         AND a.stat_date >= @fromDate
       ORDER BY a.stat_date ASC, a.usage_ms DESC`,
      {
        deviceId,
        fromDate,
      },
    );

    const byDate = new Map<
      string,
      {
        statDate: string;
        totalUsageMs: number;
        totalNotificationCount: number;
        unlockCount: number;
        apps: Array<{
          packageName: string;
          appName: string;
          iconBase64: string | null;
          usageMs: number;
        }>;
      }
    >();

    for (const row of dailyStats) {
      byDate.set(row.stat_date, {
        statDate: row.stat_date,
        totalUsageMs: 0,
        totalNotificationCount: row.total_notification_count,
        unlockCount: row.unlock_count,
        apps: [],
      });
    }

    for (const app of appRows) {
      const usageMs =
        app.stat_date === today
          ? this.clampTodayUsageMs(app.usage_ms, elapsedMsSinceChinaMidnight)
          : this.normalizeUsageMs(app.usage_ms);
      const dayBucket = byDate.get(app.stat_date) ?? {
        statDate: app.stat_date,
        totalUsageMs: 0,
        totalNotificationCount: 0,
        unlockCount: 0,
        apps: [],
      };

      dayBucket.totalUsageMs += usageMs;
      dayBucket.apps.push({
        packageName: app.package_name,
        appName: app.app_name ?? app.package_name,
        iconBase64: app.icon_base64,
        usageMs,
      });
      byDate.set(app.stat_date, dayBucket);
    }

    return {
      deviceId,
      days: boundedDays,
      points: Array.from(byDate.values()).sort((a, b) =>
        a.statDate.localeCompare(b.statDate),
      ),
      serverTs: now,
    };
  }

  private ensureDeviceExists(deviceId: string): void {
    const row = this.db.get<{ id: string }>('SELECT id FROM devices WHERE id = @id', {
      id: deviceId,
    });

    if (!row) {
      throw new NotFoundException('Device not found');
    }
  }

  private toChinaStatDate(epochMs: number): string {
    return dayjs(epochMs).tz(CHINA_TIMEZONE).format('YYYY-MM-DD');
  }

  private elapsedMsSinceChinaMidnight(nowEpochMs: number): number {
    const nowInChina = dayjs(nowEpochMs).tz(CHINA_TIMEZONE);
    return nowInChina.diff(nowInChina.startOf('day'), 'millisecond');
  }

  private clampTodayUsageMs(usageMs: number, maxMs: number): number {
    return Math.min(this.normalizeUsageMs(usageMs), Math.max(0, maxMs));
  }

  private normalizeUsageMs(usageMs: number): number {
    return Number.isFinite(usageMs) ? Math.max(0, Math.floor(usageMs)) : 0;
  }
}
