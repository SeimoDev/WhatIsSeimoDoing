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
  }

  @Cron('0 30 3 * * *', {
    timeZone: 'Asia/Shanghai',
  })
  cleanup(): void {
    const cutoffTs = Date.now() - this.retentionDays * 24 * 60 * 60 * 1000;
    const cutoffDate = dayjs()
      .tz('Asia/Shanghai')
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
}
