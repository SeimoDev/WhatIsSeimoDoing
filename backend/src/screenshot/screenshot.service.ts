import {
  BadRequestException,
  Injectable,
  Logger,
  NotFoundException,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { compareSync, hashSync } from 'bcryptjs';
import { v4 as uuidv4 } from 'uuid';
import { AppConfig } from '../config/app-config';
import { DatabaseService } from '../database/database.service';
import { RealtimeGateway } from '../realtime/realtime.gateway';

interface RequestRow {
  id: string;
  device_id: string;
  requester_socket_id: string | null;
  status: string;
  error_message: string | null;
}

interface RealtimeState {
  is_online: number;
  last_heartbeat_at: number | null;
  updated_at: number | null;
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
    private readonly realtimeGateway: RealtimeGateway,
  ) {
    this.security = this.configService.getOrThrow<AppConfig['security']>('security');
    this.realtime = this.configService.getOrThrow<AppConfig['realtime']>('realtime');
    this.hashedScreenshotPassword = hashSync(this.security.screenshotPassword, 10);
  }

  authenticate(password: string) {
    this.assertValidScreenshotPassword(password);
    return {
      ok: true,
    };
  }

  requestScreenshot(deviceId: string, password: string, requesterSocketId: string) {
    this.assertValidScreenshotPassword(password);

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
      throw new BadRequestException('Device offline');
    }

    const requestId = uuidv4();
    this.db.run(
      `INSERT INTO screenshot_requests (
         id, device_id, requester_socket_id, status, requested_at
       ) VALUES (
         @id, @deviceId, @requesterSocketId, @status, @requestedAt
       )`,
      {
        id: requestId,
        deviceId,
        requesterSocketId,
        status: 'pending',
        requestedAt: Date.now(),
      },
    );

    this.realtimeGateway.emitToDevice(deviceId, 'screenshot.request', {
      requestId,
      ts: now,
    });
    this.scheduleRequestTimeout(requestId, deviceId, requesterSocketId);

    return {
      requestId,
      status: 'pending',
      wsConnected,
      timeoutSec: this.realtime.screenshotTimeoutSec,
    };
  }

  completeScreenshot(requestId: string, imageBuffer: Buffer, mimeType = 'image/png') {
    const request = this.db.get<RequestRow>(
      `SELECT id, device_id, requester_socket_id, status, error_message
       FROM screenshot_requests
       WHERE id = @id`,
      { id: requestId },
    );

    if (!request) {
      throw new NotFoundException('Screenshot request not found');
    }

    const isLateTimeoutResult =
      request.status === 'failed' && request.error_message === 'SCREENSHOT_TIMEOUT';

    if (request.status !== 'pending' && !isLateTimeoutResult) {
      throw new BadRequestException('Screenshot request already completed');
    }

    this.db.run(
      `UPDATE screenshot_requests
       SET status = @status, completed_at = @completedAt, error_message = NULL
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

    this.emitToRequester(request.requester_socket_id, 'screenshot.ready', {
      requestId,
      deviceId: request.device_id,
      late: isLateTimeoutResult,
      ts: Date.now(),
    });

    return {
      ok: true,
      requestId,
    };
  }

  getScreenshotByRequestId(requestId: string, password: string) {
    this.assertValidScreenshotPassword(password);

    const data = this.screenshotBufferStore.get(requestId);
    if (!data) {
      throw new NotFoundException('Screenshot not available');
    }

    return data;
  }

  private scheduleRequestTimeout(
    requestId: string,
    deviceId: string,
    requesterSocketId: string,
  ): void {
    const timeoutMs = this.realtime.screenshotTimeoutSec * 1000;
    setTimeout(() => {
      const request = this.db.get<RequestRow>(
        `SELECT id, device_id, requester_socket_id, status
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

      this.emitToRequester(request.requester_socket_id ?? requesterSocketId, 'screenshot.error', {
        requestId,
        deviceId,
        reason: 'SCREENSHOT_TIMEOUT',
        ts: Date.now(),
      });
    }, timeoutMs).unref();
  }

  private assertValidScreenshotPassword(password: string): void {
    const valid = compareSync(password, this.hashedScreenshotPassword);
    if (!valid) {
      throw new UnauthorizedException('Invalid screenshot password');
    }
  }

  private emitToRequester(socketId: string | null, event: string, payload: unknown): void {
    if (!socketId) {
      return;
    }
    this.realtimeGateway.emitToSocket(socketId, event, payload);
  }
}
