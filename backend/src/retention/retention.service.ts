import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Cron } from '@nestjs/schedule';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';
import { AppConfig } from '../config/app-config';
import { DatabaseService } from '../database/database.service';

dayjs.extend(utc);
dayjs.extend(timezone);
const CHINA_TIMEZONE = 'Asia/Shanghai';
const TODAY_USAGE_OVERFLOW_TOLERANCE_MS = 2 * 60 * 1000;

@Injectable()
export class RetentionService {
  private readonly logger = new Logger(RetentionService.name);
  private readonly retentionDays: number;

  constructor(
    private readonly db: DatabaseService,
    private readonly configService: ConfigService,
  ) {
    this.retentionDays =
      this.configService.getOrThrow<AppConfig['storage']>('storage').retentionDays;

    // Apply one immediate pass so legacy polluted "today" values are fixed
    // even before the first cron tick.
    this.sanitizeTodayUsage('startup');
  }

  @Cron('0 30 3 * * *', {
    timeZone: CHINA_TIMEZONE,
  })
  cleanup(): void {
    const cutoffTs = Date.now() - this.retentionDays * 24 * 60 * 60 * 1000;
    const cutoffDate = dayjs()
      .tz(CHINA_TIMEZONE)
      .subtract(this.retentionDays, 'day')
      .format('YYYY-MM-DD');

    this.db.transaction(() => {
      this.db.run('DELETE FROM foreground_events WHERE ts < @cutoffTs', {
        cutoffTs,
      });
      this.db.run('DELETE FROM screenshot_requests WHERE requested_at < @cutoffTs', {
        cutoffTs,
      });
      this.db.run('DELETE FROM daily_device_stats WHERE stat_date < @cutoffDate', {
        cutoffDate,
      });
      this.db.run('DELETE FROM daily_app_usage_stats WHERE stat_date < @cutoffDate', {
        cutoffDate,
      });
      this.db.run('DELETE FROM device_tokens WHERE expires_at < @now OR revoked = 1', {
        now: Date.now(),
      });
    });

    this.logger.log(`Retention cleanup completed. cutoffDate=${cutoffDate}`);
  }

  @Cron('0 * * * * *', {
    timeZone: CHINA_TIMEZONE,
  })
  sanitizeTodayUsageCron(): void {
    this.sanitizeTodayUsage('cron');
  }

  private sanitizeTodayUsage(trigger: 'startup' | 'cron'): void {
    const nowTs = Date.now();
    const nowInChina = dayjs(nowTs).tz(CHINA_TIMEZONE);
    const statDate = nowInChina.format('YYYY-MM-DD');
    const elapsedMsSinceMidnight = nowInChina.diff(
      nowInChina.startOf('day'),
      'millisecond',
    );

    let appRowsUpdated = 0;
    let realtimeRowsUpdated = 0;
    let overflowDeviceCount = 0;
    let overflowAppRowsTrimmed = 0;

    this.db.transaction(() => {
      const appResult = this.db.run(
        `UPDATE daily_app_usage_stats
         SET usage_ms = CASE
           WHEN usage_ms < 0 THEN 0
           WHEN usage_ms > @elapsedMs THEN @elapsedMs
           ELSE usage_ms
         END
         WHERE stat_date = @statDate
           AND (usage_ms < 0 OR usage_ms > @elapsedMs)`,
        {
          statDate,
          elapsedMs: elapsedMsSinceMidnight,
        },
      );
      appRowsUpdated = appResult.changes;

      const realtimeResult = this.db.run(
        `UPDATE device_realtime_state
         SET today_usage_ms = CASE
           WHEN today_usage_ms < 0 THEN 0
           WHEN today_usage_ms > @elapsedMs THEN @elapsedMs
           ELSE today_usage_ms
         END,
             updated_at = @updatedAt
         WHERE today_usage_ms < 0 OR today_usage_ms > @elapsedMs`,
        {
          elapsedMs: elapsedMsSinceMidnight,
          updatedAt: nowTs,
        },
      );
      realtimeRowsUpdated = realtimeResult.changes;

      const overflowDevices = this.db.all<{
        device_id: string;
        total_usage_ms: number;
      }>(
        `SELECT device_id, SUM(usage_ms) AS total_usage_ms
         FROM daily_app_usage_stats
         WHERE stat_date = @statDate
         GROUP BY device_id
         HAVING SUM(usage_ms) > @maxTotalUsageMs`,
        {
          statDate,
          maxTotalUsageMs:
            elapsedMsSinceMidnight + TODAY_USAGE_OVERFLOW_TOLERANCE_MS,
        },
      );

      overflowDeviceCount = overflowDevices.length;
      for (const device of overflowDevices) {
        let remainingAllowedMs = elapsedMsSinceMidnight;
        const appRows = this.db.all<{
          package_name: string;
          usage_ms: number;
        }>(
          `SELECT package_name, usage_ms
           FROM daily_app_usage_stats
           WHERE device_id = @deviceId
             AND stat_date = @statDate
           ORDER BY usage_ms DESC, package_name ASC`,
          {
            deviceId: device.device_id,
            statDate,
          },
        );

        for (const appRow of appRows) {
          const normalizedUsageMs = Math.max(0, Math.floor(appRow.usage_ms));
          const trimmedUsageMs = Math.min(normalizedUsageMs, remainingAllowedMs);
          remainingAllowedMs = Math.max(0, remainingAllowedMs - trimmedUsageMs);

          if (trimmedUsageMs !== normalizedUsageMs) {
            this.db.run(
              `UPDATE daily_app_usage_stats
               SET usage_ms = @usageMs
               WHERE device_id = @deviceId
                 AND stat_date = @statDate
                 AND package_name = @packageName`,
              {
                deviceId: device.device_id,
                statDate,
                packageName: appRow.package_name,
                usageMs: trimmedUsageMs,
              },
            );
            overflowAppRowsTrimmed += 1;
          }
        }
      }
    });

    if (
      appRowsUpdated > 0 ||
      realtimeRowsUpdated > 0 ||
      overflowDeviceCount > 0
    ) {
      this.logger.warn(
        `Today usage sanitized. trigger=${trigger} statDate=${statDate} elapsedMs=${elapsedMsSinceMidnight} appRowsUpdated=${appRowsUpdated} realtimeRowsUpdated=${realtimeRowsUpdated} overflowDeviceCount=${overflowDeviceCount} overflowAppRowsTrimmed=${overflowAppRowsTrimmed}`,
      );
    }
  }
}
