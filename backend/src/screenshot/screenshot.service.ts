import {
  BadRequestException,
  Injectable,
  Logger,
  NotFoundException,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { compareSync, hashSync } from 'bcryptjs';
import { v4 as uuidv4 } from 'uuid';
import { AppConfig } from '../config/app-config';
import { DatabaseService } from '../database/database.service';
import { RealtimeGateway } from '../realtime/realtime.gateway';

interface RequestRow {
  id: string;
  device_id: string;
  status: string;
}

interface RealtimeState {
  is_online: number;
  last_heartbeat_at: number | null;
  updated_at: number | null;
}

interface ScreenshotTokenPayload {
  type: 'screenshot';
  scope: 'request';
}

@Injectable()
export class ScreenshotService {
  private readonly logger = new Logger(ScreenshotService.name);
  private readonly security: AppConfig['security'];
  private readonly realtime: AppConfig['realtime'];
  private readonly hashedScreenshotPassword: string;
  private readonly screenshotBufferStore = new Map<
    string,
    {
      mimeType: string;
      buffer: Buffer;
      createdAt: number;
    }
  >();

  constructor(
    private readonly db: DatabaseService,
    private readonly configService: ConfigService,
    private readonly jwtService: JwtService,
    private readonly realtimeGateway: RealtimeGateway,
  ) {
    this.security = this.configService.getOrThrow<AppConfig['security']>('security');
    this.realtime = this.configService.getOrThrow<AppConfig['realtime']>('realtime');
    this.hashedScreenshotPassword = hashSync(this.security.screenshotPassword, 10);
  }

  authenticate(password: string) {
    const valid = compareSync(password, this.hashedScreenshotPassword);
    if (!valid) {
      throw new UnauthorizedException('Invalid screenshot password');
    }

    const screenshotToken = this.jwtService.sign(
      {
        type: 'screenshot',
        scope: 'request',
      } satisfies ScreenshotTokenPayload,
      {
        secret: this.security.jwtSecret,
        expiresIn: `${this.security.screenshotTokenTtlMinutes}m`,
      },
    );

    return {
      screenshotToken,
      expiresInMinutes: this.security.screenshotTokenTtlMinutes,
    };
  }

  verifyScreenshotToken(token: string): ScreenshotTokenPayload {
    try {
      const payload = this.jwtService.verify<ScreenshotTokenPayload>(token, {
        secret: this.security.jwtSecret,
      });

      if (payload.type !== 'screenshot') {
        throw new UnauthorizedException('Invalid screenshot token');
      }

      return payload;
    } catch {
      throw new UnauthorizedException('Invalid screenshot token');
    }
  }

  requestScreenshot(deviceId: string) {
    const device = this.db.get<{ id: string }>('SELECT id FROM devices WHERE id = @id', {
      id: deviceId,
    });

    if (!device) {
      throw new NotFoundException('Device not found');
    }

    const state = this.db.get<RealtimeState>(
      `SELECT is_online, last_heartbeat_at, updated_at
       FROM device_realtime_state
       WHERE device_id = @deviceId`,
      { deviceId },
    );

    const now = Date.now();
    const timeoutMs = this.realtime.heartbeatTimeoutSec * 1000;
    const screenshotToleranceMs = timeoutMs * 2;
    const threshold = now - screenshotToleranceMs;
    const lastHeartbeatAt = Number(state?.last_heartbeat_at ?? 0);
    const lastUpdatedAt = Number(state?.updated_at ?? 0);
    const wsConnected = this.realtimeGateway.isDeviceConnected(deviceId);
    const heartbeatRecent =
      state?.is_online === 1 && lastHeartbeatAt >= threshold;
    const stateUpdatedRecently = lastUpdatedAt >= threshold;
    const onlineForScreenshot = wsConnected || heartbeatRecent || stateUpdatedRecently;

    if (!onlineForScreenshot) {
      this.logger.warn(
        `Reject screenshot as offline deviceId=${deviceId} ` +
          `isOnlineFlag=${state?.is_online ?? 'null'} ` +
          `lastHeartbeatAt=${lastHeartbeatAt} lastUpdatedAt=${lastUpdatedAt} ` +
          `threshold=${threshold} wsConnected=${wsConnected}`,
      );
      this.realtimeGateway.emitToWeb('screenshot.error', {
        deviceId,
        reason: 'DEVICE_OFFLINE',
        ts: now,
      });

      throw new BadRequestException('Device offline');
    }

    const requestId = uuidv4();
    this.db.run(
      `INSERT INTO screenshot_requests (
         id, device_id, status, requested_at
       ) VALUES (
         @id, @deviceId, @status, @requestedAt
       )`,
      {
        id: requestId,
        deviceId,
        status: 'pending',
        requestedAt: Date.now(),
      },
    );

    this.realtimeGateway.emitToDevice(deviceId, 'screenshot.request', {
      requestId,
      ts: now,
    });
    this.scheduleRequestTimeout(requestId, deviceId);

    return {
      requestId,
      status: 'pending',
      wsConnected,
    };
  }

  completeScreenshot(requestId: string, imageBuffer: Buffer, mimeType = 'image/png') {
    const request = this.db.get<RequestRow>(
      `SELECT id, device_id, status
       FROM screenshot_requests
       WHERE id = @id`,
      { id: requestId },
    );

    if (!request) {
      throw new NotFoundException('Screenshot request not found');
    }

    if (request.status !== 'pending') {
      throw new BadRequestException('Screenshot request already completed');
    }

    this.db.run(
      `UPDATE screenshot_requests
       SET status = @status, completed_at = @completedAt
       WHERE id = @id`,
      {
        id: requestId,
        status: 'done',
        completedAt: Date.now(),
      },
    );

    this.screenshotBufferStore.set(requestId, {
      mimeType,
      buffer: imageBuffer,
      createdAt: Date.now(),
    });

    setTimeout(() => {
      this.screenshotBufferStore.delete(requestId);
    }, 2 * 60 * 1000).unref();

    const dataUrl = `data:${mimeType};base64,${imageBuffer.toString('base64')}`;

    this.realtimeGateway.emitToWeb('screenshot.ready', {
      requestId,
      deviceId: request.device_id,
      imageDataUrl: dataUrl,
      ts: Date.now(),
    });

    return {
      ok: true,
      requestId,
    };
  }

  getScreenshotByRequestId(requestId: string) {
    const data = this.screenshotBufferStore.get(requestId);
    if (!data) {
      throw new NotFoundException('Screenshot not available');
    }

    return data;
  }

  private scheduleRequestTimeout(requestId: string, deviceId: string): void {
    const timeoutMs = 8_000;
    setTimeout(() => {
      const request = this.db.get<RequestRow>(
        `SELECT id, device_id, status
         FROM screenshot_requests
         WHERE id = @id`,
        { id: requestId },
      );

      if (!request || request.status !== 'pending') {
        return;
      }

      this.db.run(
        `UPDATE screenshot_requests
         SET status = @status, completed_at = @completedAt, error_message = @errorMessage
         WHERE id = @id`,
        {
          id: requestId,
          status: 'failed',
          completedAt: Date.now(),
          errorMessage: 'SCREENSHOT_TIMEOUT',
        },
      );

      this.realtimeGateway.emitToWeb('screenshot.error', {
        requestId,
        deviceId,
        reason: 'SCREENSHOT_TIMEOUT',
        ts: Date.now(),
      });
    }, timeoutMs).unref();
  }
}
