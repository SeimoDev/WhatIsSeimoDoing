import { IsString, Length } from 'class-validator';

export class RequestScreenshotDto {
  @IsString()
  @Length(1, 128)
  deviceId!: string;

  @IsString()
  @Length(4, 128)
  password!: string;

  @IsString()
  @Length(1, 128)
  requesterSocketId!: string;
}
