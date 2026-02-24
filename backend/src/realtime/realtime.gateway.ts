import {
  ConnectedSocket,
  MessageBody,
  OnGatewayConnection,
  OnGatewayDisconnect,
  SubscribeMessage,
  WebSocketGateway,
  WebSocketServer,
} from '@nestjs/websockets';
import { Injectable, Logger } from '@nestjs/common';
import { Server, Socket } from 'socket.io';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { load } from 'js-yaml';
import { DeviceAuthService } from '../device-auth/device-auth.service';

interface DeviceSocketAuth {
  clientType?: 'device' | 'web';
  token?: string;
}

interface PartialConfigFile {
  realtime?: {
    namespace?: string;
  };
}

const resolveWsNamespace = (): string => {
  const envNamespace = process.env.WS_NAMESPACE;
  if (envNamespace && envNamespace.startsWith('/')) {
    return envNamespace;
  }

  const configPath = process.env.APP_CONFIG_PATH ?? path.resolve(process.cwd(), 'config.yaml');
  if (!fs.existsSync(configPath)) {
    return '/ws';
  }

  try {
    const raw = fs.readFileSync(configPath, 'utf-8');
    const parsed = load(raw) as PartialConfigFile | null;
    const fromConfig = parsed?.realtime?.namespace;
    if (fromConfig && fromConfig.startsWith('/')) {
      return fromConfig;
    }
  } catch {
    return '/ws';
  }

  return '/ws';
};

const wsNamespace = resolveWsNamespace();

@Injectable()
@WebSocketGateway({
  namespace: wsNamespace,
  cors: {
    origin: true,
  },
})
export class RealtimeGateway implements OnGatewayConnection, OnGatewayDisconnect {
  @WebSocketServer()
  server!: Server;

  private readonly logger = new Logger(RealtimeGateway.name);
  private readonly socketToDeviceId = new Map<string, string>();

  constructor(private readonly deviceAuthService: DeviceAuthService) {}

  handleConnection(client: Socket): void {
    const auth = (client.handshake.auth ?? {}) as DeviceSocketAuth;
    const query = client.handshake.query as Record<string, string | undefined>;
    const clientType = auth.clientType ?? (query.clientType as 'device' | 'web' | undefined);
    const token = auth.token ?? query.token;

    if (clientType === 'device') {
      if (!token) {
        client.emit('error', { message: 'Missing device token' });
        client.disconnect(true);
        return;
      }

      try {
        const payload = this.deviceAuthService.verifyDeviceAccessToken(token);
        const deviceId = payload.sub;
        this.socketToDeviceId.set(client.id, deviceId);
        client.join(`device:${deviceId}`);
        client.emit('connected', { role: 'device', deviceId });
      } catch {
        client.emit('error', { message: 'Invalid device token' });
        client.disconnect(true);
      }

      return;
    }

    client.join('web');
    client.emit('connected', { role: 'web' });
  }

  handleDisconnect(client: Socket): void {
    this.socketToDeviceId.delete(client.id);
  }

  emitToWeb(event: string, payload: unknown): void {
    if (!this.server) {
      this.logger.warn(`Socket server not ready, skip emitToWeb(${event})`);
      return;
    }
    this.server.to('web').emit(event, payload);
  }

  emitToDevice(deviceId: string, event: string, payload: unknown): void {
    if (!this.server) {
      this.logger.warn(`Socket server not ready, skip emitToDevice(${event})`);
      return;
    }
    this.server.to(`device:${deviceId}`).emit(event, payload);
  }

  isDeviceConnected(deviceId: string): boolean {
    const rooms = this.server?.sockets?.adapter?.rooms;
    if (!rooms) {
      return false;
    }
    const room = rooms.get(`device:${deviceId}`);
    return Boolean(room && room.size > 0);
  }

  @SubscribeMessage('device.ping')
  handlePing(@ConnectedSocket() client: Socket, @MessageBody() body: unknown): void {
    const deviceId = this.socketToDeviceId.get(client.id);
    if (!deviceId) {
      return;
    }

    this.logger.debug(`Received ping from ${deviceId}`);
    client.emit('device.pong', body ?? { ts: Date.now() });
  }
}
