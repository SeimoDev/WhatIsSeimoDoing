import {
  CanActivate,
  ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ScreenshotService } from '../screenshot.service';

@Injectable()
export class ScreenshotTokenGuard implements CanActivate {
  constructor(private readonly screenshotService: ScreenshotService) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<{
      headers: Record<string, string | undefined>;
    }>();

    const authHeader = request.headers.authorization;
    if (!authHeader?.startsWith('Bearer ')) {
      throw new UnauthorizedException('Missing screenshot token');
    }

    const token = authHeader.slice('Bearer '.length);
    this.screenshotService.verifyScreenshotToken(token);
    return true;
  }
}
