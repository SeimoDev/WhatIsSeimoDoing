import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { ScreenshotController } from './screenshot.controller';
import { ScreenshotService } from './screenshot.service';
import { DatabaseModule } from '../database/database.module';
import { RealtimeModule } from '../realtime/realtime.module';
import { DeviceAuthModule } from '../device-auth/device-auth.module';

@Module({
  imports: [
    ConfigModule,
    DatabaseModule,
    RealtimeModule,
    DeviceAuthModule,
  ],
  controllers: [ScreenshotController],
  providers: [ScreenshotService],
  exports: [ScreenshotService],
})
export class ScreenshotModule {}
