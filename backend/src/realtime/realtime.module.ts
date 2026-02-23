import { Module } from '@nestjs/common';
import { RealtimeGateway } from './realtime.gateway';
import { OnlineMonitorService } from './online-monitor.service';
import { DeviceAuthModule } from '../device-auth/device-auth.module';
import { DatabaseModule } from '../database/database.module';

@Module({
  imports: [DeviceAuthModule, DatabaseModule],
  providers: [RealtimeGateway, OnlineMonitorService],
  exports: [RealtimeGateway],
})
export class RealtimeModule {}
