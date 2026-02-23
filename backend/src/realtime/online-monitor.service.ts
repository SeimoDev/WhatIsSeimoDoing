import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Cron, CronExpression } from '@nestjs/schedule';
import { AppConfig } from '../config/app-config';
import { DatabaseService } from '../database/database.service';
import { RealtimeGateway } from './realtime.gateway';

interface OfflineDevice {
  device_id: string;
}

@Injectable()
export class OnlineMonitorService {
  constructor(
    private readonly db: DatabaseService,
    private readonly configService: ConfigService,
    private readonly realtimeGateway: RealtimeGateway,
  ) {}

  @Cron(CronExpression.EVERY_10_SECONDS)
  detectOfflineDevices(): void {
    const timeoutSec = this.configService.getOrThrow<AppConfig['realtime']>('realtime').heartbeatTimeoutSec;
    const timeoutMs = timeoutSec * 1000;
    const graceMs = 10_000;
    const threshold = Date.now() - timeoutMs - graceMs;

    const rows = this.db.all<OfflineDevice>(
      `SELECT device_id
       FROM device_realtime_state
       WHERE is_online = 1 AND IFNULL(last_heartbeat_at, 0) < @threshold`,
      { threshold },
    );

    if (rows.length === 0) {
      return;
    }

    this.db.transaction(() => {
      for (const row of rows) {
        this.db.run(
          `UPDATE device_realtime_state
           SET is_online = 0, updated_at = @updatedAt
           WHERE device_id = @deviceId`,
          {
            deviceId: row.device_id,
            updatedAt: Date.now(),
          },
        );
      }
    });

    for (const row of rows) {
      this.realtimeGateway.emitToWeb('device.offline', {
        deviceId: row.device_id,
        ts: Date.now(),
      });
    }
  }
}
