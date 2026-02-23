import { Injectable, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';
import { AppConfig } from '../config/app-config';
import { DatabaseService } from '../database/database.service';

dayjs.extend(utc);
dayjs.extend(timezone);

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
          lastSeenAt: row.last_seen_at,
          currentApp: row.package_name
            ? {
                packageName: row.package_name,
                appName: row.app_name,
                iconHash: row.icon_hash,
                iconBase64: row.icon_base64,
                foregroundStartedAt: row.foreground_started_at,
                todayUsageMs: row.today_usage_ms,
              }
            : null,
        };
      }),
      serverTs: now,
    };
  }

  getToday(deviceId: string) {
    this.ensureDeviceExists(deviceId);

    const today = dayjs().tz('Asia/Shanghai').format('YYYY-MM-DD');

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

    const threshold = Date.now() - this.realtime.heartbeatTimeoutSec * 1000;
    const online =
      state.is_online === 1 &&
      Number(state.last_heartbeat_at ?? 0) >= threshold;

    return {
      deviceId,
      statDate: today,
      online,
      currentApp: state.package_name
        ? {
            packageName: state.package_name,
            appName: state.app_name,
            iconHash: state.icon_hash,
            iconBase64: state.icon_base64,
            foregroundStartedAt: state.foreground_started_at,
            todayUsageMs: state.today_usage_ms,
          }
        : null,
      totalNotificationCount: deviceStats?.total_notification_count ?? 0,
      unlockCount: deviceStats?.unlock_count ?? 0,
      snapshotTs: deviceStats?.snapshot_ts ?? null,
      apps: appRows.map((row) => ({
        packageName: row.package_name,
        usageMs: row.usage_ms,
        appName: row.app_name ?? row.package_name,
        iconBase64: row.icon_base64,
      })),
      serverTs: Date.now(),
    };
  }

  getHistory(deviceId: string, days: number) {
    this.ensureDeviceExists(deviceId);

    const boundedDays = Math.max(1, Math.min(30, days));
    const fromDate = dayjs()
      .tz('Asia/Shanghai')
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
      const dayBucket = byDate.get(app.stat_date) ?? {
        statDate: app.stat_date,
        totalUsageMs: 0,
        totalNotificationCount: 0,
        unlockCount: 0,
        apps: [],
      };

      dayBucket.totalUsageMs += app.usage_ms;
      dayBucket.apps.push({
        packageName: app.package_name,
        appName: app.app_name ?? app.package_name,
        iconBase64: app.icon_base64,
        usageMs: app.usage_ms,
      });
      byDate.set(app.stat_date, dayBucket);
    }

    return {
      deviceId,
      days: boundedDays,
      points: Array.from(byDate.values()).sort((a, b) =>
        a.statDate.localeCompare(b.statDate),
      ),
      serverTs: Date.now(),
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
}
