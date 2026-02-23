import {
  BadRequestException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { compareSync, hashSync } from 'bcryptjs';
import { v4 as uuidv4 } from 'uuid';
import { AppConfig } from '../config/app-config';
import { DatabaseService } from '../database/database.service';
import { RegisterDeviceDto } from './dto/register-device.dto';

interface DeviceRecord {
  id: string;
  device_code: string;
  device_name: string;
  manufacturer: string;
  model: string;
  android_version: string;
  app_version: string;
}

interface DeviceTokenPayload {
  sub: string;
  type: 'device';
}

@Injectable()
export class DeviceAuthService {
  private readonly security: AppConfig['security'];
  private readonly server: AppConfig['server'];

  constructor(
    private readonly db: DatabaseService,
    private readonly configService: ConfigService,
    private readonly jwtService: JwtService,
  ) {
    this.security = this.configService.getOrThrow<AppConfig['security']>('security');
    this.server = this.configService.getOrThrow<AppConfig['server']>('server');
  }

  registerDevice(dto: RegisterDeviceDto) {
    const now = Date.now();
    const device = this.db.get<DeviceRecord>(
      'SELECT * FROM devices WHERE device_code = @deviceCode',
      { deviceCode: dto.deviceCode },
    );

    const deviceId = device?.id ?? uuidv4();

    if (device) {
      this.db.run(
        `UPDATE devices
         SET device_name = @deviceName,
             manufacturer = @manufacturer,
             model = @model,
             android_version = @androidVersion,
             app_version = @appVersion,
             root_enabled = @rootEnabled,
             updated_at = @updatedAt
         WHERE id = @id`,
        {
          id: deviceId,
          deviceName: dto.deviceName,
          manufacturer: dto.manufacturer,
          model: dto.model,
          androidVersion: dto.androidVersion,
          appVersion: dto.appVersion,
          rootEnabled: dto.rootEnabled ? 1 : 0,
          updatedAt: now,
        },
      );
    } else {
      this.db.run(
        `INSERT INTO devices (
            id, device_code, device_name, manufacturer, model,
            android_version, app_version, root_enabled, created_at, updated_at
         ) VALUES (
            @id, @deviceCode, @deviceName, @manufacturer, @model,
            @androidVersion, @appVersion, @rootEnabled, @createdAt, @updatedAt
         )`,
        {
          id: deviceId,
          deviceCode: dto.deviceCode,
          deviceName: dto.deviceName,
          manufacturer: dto.manufacturer,
          model: dto.model,
          androidVersion: dto.androidVersion,
          appVersion: dto.appVersion,
          rootEnabled: dto.rootEnabled ? 1 : 0,
          createdAt: now,
          updatedAt: now,
        },
      );

      this.db.run(
        `INSERT INTO device_realtime_state (
           device_id, today_usage_ms, updated_at, last_heartbeat_at, is_online
         ) VALUES (
           @deviceId, 0, @updatedAt, @lastHeartbeatAt, 0
         )`,
        {
          deviceId,
          updatedAt: now,
          lastHeartbeatAt: 0,
        },
      );
    }

    const accessToken = this.signAccessToken(deviceId);
    const refreshToken = this.signRefreshToken(deviceId);
    const refreshTokenHash = hashSync(refreshToken, 10);

    const expiresAt = now + 30 * 24 * 60 * 60 * 1000;
    this.db.run(
      `INSERT INTO device_tokens (
         id, device_id, refresh_token_hash, revoked, created_at, expires_at
       ) VALUES (
         @id, @deviceId, @refreshTokenHash, 0, @createdAt, @expiresAt
       )`,
      {
        id: uuidv4(),
        deviceId,
        refreshTokenHash,
        createdAt: now,
        expiresAt,
      },
    );

    return {
      deviceId,
      accessToken,
      refreshToken,
      wsUrl: `ws://${this.server.host}:${this.server.port}/ws`,
      heartbeatIntervalSec: 30,
    };
  }

  verifyDeviceAccessToken(token: string): DeviceTokenPayload {
    try {
      return this.jwtService.verify<DeviceTokenPayload>(token, {
        secret: this.security.jwtSecret,
      });
    } catch {
      throw new UnauthorizedException('Invalid device token');
    }
  }

  verifyDeviceRefreshToken(deviceId: string, token: string): boolean {
    const rows = this.db.all<{ refresh_token_hash: string }>(
      `SELECT refresh_token_hash
       FROM device_tokens
       WHERE device_id = @deviceId AND revoked = 0 AND expires_at > @now`,
      { deviceId, now: Date.now() },
    );

    return rows.some((row) => compareSync(token, row.refresh_token_hash));
  }

  ensureDeviceExists(deviceId: string): void {
    const exists = this.db.get<{ id: string }>('SELECT id FROM devices WHERE id = @id', {
      id: deviceId,
    });

    if (!exists) {
      throw new BadRequestException('Unknown device id');
    }
  }

  private signAccessToken(deviceId: string): string {
    return this.jwtService.sign(
      {
        sub: deviceId,
        type: 'device',
      },
      {
        secret: this.security.jwtSecret,
        expiresIn: '1h',
      },
    );
  }

  private signRefreshToken(deviceId: string): string {
    return this.jwtService.sign(
      {
        sub: deviceId,
        type: 'device',
      },
      {
        secret: this.security.refreshSecret,
        expiresIn: '30d',
      },
    );
  }
}
