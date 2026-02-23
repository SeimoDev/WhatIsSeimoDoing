import {
  CanActivate,
  ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { DeviceAuthService } from '../device-auth.service';

@Injectable()
export class DeviceJwtGuard implements CanActivate {
  constructor(private readonly deviceAuthService: DeviceAuthService) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<{
      headers: Record<string, string | undefined>;
      deviceId?: string;
    }>();

    const authHeader = request.headers.authorization;
    if (!authHeader?.startsWith('Bearer ')) {
      throw new UnauthorizedException('Missing device bearer token');
    }

    const token = authHeader.slice('Bearer '.length);
    const payload = this.deviceAuthService.verifyDeviceAccessToken(token);

    if (payload.type !== 'device' || !payload.sub) {
      throw new UnauthorizedException('Invalid device token payload');
    }

    request.deviceId = payload.sub;
    return true;
  }
}
