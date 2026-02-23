import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Param,
  Post,
  Res,
  UploadedFile,
  UseGuards,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { Response } from 'express';
import { DeviceJwtGuard } from '../device-auth/guards/device-jwt.guard';
import { AuthScreenshotDto } from './dto/auth-screenshot.dto';
import { RequestScreenshotDto } from './dto/request-screenshot.dto';
import { ScreenshotResultDto } from './dto/screenshot-result.dto';
import { ScreenshotTokenGuard } from './guards/screenshot-token.guard';
import { ScreenshotService } from './screenshot.service';

@Controller('screenshots')
export class ScreenshotController {
  constructor(private readonly screenshotService: ScreenshotService) {}

  @Post('auth')
  auth(@Body() dto: AuthScreenshotDto) {
    return this.screenshotService.authenticate(dto.password);
  }

  @Post('request')
  @UseGuards(ScreenshotTokenGuard)
  request(@Body() dto: RequestScreenshotDto) {
    return this.screenshotService.requestScreenshot(dto.deviceId);
  }

  @Post('result')
  @UseGuards(DeviceJwtGuard)
  @UseInterceptors(FileInterceptor('image'))
  result(
    @Body() dto: ScreenshotResultDto,
    @UploadedFile() file: Express.Multer.File,
  ) {
    if (!file?.buffer) {
      throw new BadRequestException('Missing uploaded image file');
    }

    return this.screenshotService.completeScreenshot(
      dto.requestId,
      file.buffer,
      file.mimetype || 'image/png',
    );
  }

  @Get('result/:requestId')
  getResult(@Param('requestId') requestId: string, @Res() response: Response) {
    const data = this.screenshotService.getScreenshotByRequestId(requestId);
    response.setHeader('Content-Type', data.mimeType);
    response.send(data.buffer);
  }
}
