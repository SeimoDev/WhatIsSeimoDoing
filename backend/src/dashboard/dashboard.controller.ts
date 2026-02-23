import { Controller, Get, Param, ParseIntPipe, Query } from '@nestjs/common';
import { DashboardService } from './dashboard.service';

@Controller('dashboard')
export class DashboardController {
  constructor(private readonly dashboardService: DashboardService) {}

  @Get('devices')
  getDevices() {
    return this.dashboardService.getDevices();
  }

  @Get('devices/:deviceId/today')
  getToday(@Param('deviceId') deviceId: string) {
    return this.dashboardService.getToday(deviceId);
  }

  @Get('devices/:deviceId/history')
  getHistory(
    @Param('deviceId') deviceId: string,
    @Query('days', new ParseIntPipe({ optional: true })) days?: number,
  ) {
    return this.dashboardService.getHistory(deviceId, days ?? 7);
  }
}
