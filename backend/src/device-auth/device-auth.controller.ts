import { Body, Controller, Post } from '@nestjs/common';
import { RegisterDeviceDto } from './dto/register-device.dto';
import { DeviceAuthService } from './device-auth.service';

@Controller('devices')
export class DeviceAuthController {
  constructor(private readonly deviceAuthService: DeviceAuthService) {}

  @Post('register')
  register(@Body() dto: RegisterDeviceDto) {
    return this.deviceAuthService.registerDevice(dto);
  }
}
