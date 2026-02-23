import { Body, Controller, Post, Req, UseGuards } from '@nestjs/common';
import { Request } from 'express';
import { DeviceJwtGuard } from '../device-auth/guards/device-jwt.guard';
import { DailySnapshotDto } from './dto/daily-snapshot.dto';
import { ForegroundSwitchDto } from './dto/foreground-switch.dto';
import { HeartbeatDto } from './dto/heartbeat.dto';
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

  @Post('stats/daily-snapshot')
  dailySnapshot(@Req() req: DeviceRequest, @Body() dto: DailySnapshotDto) {
    return this.ingestService.ingestDailySnapshot(req.deviceId, dto);
  }
}
