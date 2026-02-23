import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { ScheduleModule } from '@nestjs/schedule';
import { loadConfiguration } from './config/configuration';
import { DatabaseModule } from './database/database.module';
import { DeviceAuthModule } from './device-auth/device-auth.module';
import { RealtimeModule } from './realtime/realtime.module';
import { IngestModule } from './ingest/ingest.module';
import { ScreenshotModule } from './screenshot/screenshot.module';
import { DashboardModule } from './dashboard/dashboard.module';
import { RetentionModule } from './retention/retention.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      ignoreEnvFile: true,
      load: [loadConfiguration],
    }),
    ScheduleModule.forRoot(),
    DatabaseModule,
    DeviceAuthModule,
    RealtimeModule,
    IngestModule,
    ScreenshotModule,
    DashboardModule,
    RetentionModule,
  ],
})
export class AppModule {}
