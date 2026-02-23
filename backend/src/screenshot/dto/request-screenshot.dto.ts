import { IsString, Length } from 'class-validator';

export class RequestScreenshotDto {
  @IsString()
  @Length(1, 128)
  deviceId!: string;
}
