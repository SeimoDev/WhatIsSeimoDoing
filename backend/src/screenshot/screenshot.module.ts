import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { ScreenshotController } from './screenshot.controller';
import { ScreenshotService } from './screenshot.service';
import { DatabaseModule } from '../database/database.module';
import { RealtimeModule } from '../realtime/realtime.module';
import { DeviceAuthModule } from '../device-auth/device-auth.module';
import { AppConfig } from '../config/app-config';
import { ScreenshotTokenGuard } from './guards/screenshot-token.guard';

@Module({
  imports: [
    ConfigModule,
    JwtModule.registerAsync({
      imports: [ConfigModule],
      useFactory: (configService: ConfigService) => {
        const security = configService.getOrThrow<AppConfig['security']>('security');
        return {
          secret: security.jwtSecret,
          signOptions: { expiresIn: `${security.screenshotTokenTtlMinutes}m` },
        };
      },
      inject: [ConfigService],
    }),
    DatabaseModule,
    RealtimeModule,
    DeviceAuthModule,
  ], 
  controllers: [ScreenshotController],
  providers: [ScreenshotService, ScreenshotTokenGuard],
  exports: [ScreenshotService],
})
export class ScreenshotModule {}
