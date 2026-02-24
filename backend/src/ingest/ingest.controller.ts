import { Body, Controller, Post, Req, UseGuards } from '@nestjs/common';
import { Request } from 'express';
import { DeviceJwtGuard } from '../device-auth/guards/device-jwt.guard';
import { AppCatalogSyncDto } from './dto/app-catalog-sync.dto';
import { DailySnapshotDto } from './dto/daily-snapshot.dto';
import { ForegroundSwitchDto } from './dto/foreground-switch.dto';
import { HeartbeatDto } from './dto/heartbeat.dto';
import { ScreenStateDto } from './dto/screen-state.dto';
import { IngestService } from './ingest.service';

type DeviceRequest = Request & { deviceId: string };

@Controller()
@UseGuards(DeviceJwtGuard)
export class IngestController {
  constructor(private readonly ingestService: IngestService) {}

  @Post('devices/heartbeat')
  heartbeat(@Req() req: DeviceRequest, @Body() dto: HeartbeatDto) {
    return this.ingestService.ingestHeartbeat(req.deviceId, dto);
  }

  @Post('events/foreground-switch')
  foregroundSwitch(@Req() req: DeviceRequest, @Body() dto: ForegroundSwitchDto) {
    return this.ingestService.ingestForegroundSwitch(req.deviceId, dto);
  }

  @Post('events/screen-state')
  screenState(@Req() req: DeviceRequest, @Body() dto: ScreenStateDto) {
    return this.ingestService.ingestScreenState(req.deviceId, dto);
  }

  @Post('events/app-catalog-sync')
  appCatalogSync(@Req() req: DeviceRequest, @Body() dto: AppCatalogSyncDto) {
    return this.ingestService.ingestAppCatalogSync(req.deviceId, dto);
  }

  @Post('stats/daily-snapshot')
  dailySnapshot(@Req() req: DeviceRequest, @Body() dto: DailySnapshotDto) {
    return this.ingestService.ingestDailySnapshot(req.deviceId, dto);
  }
}
