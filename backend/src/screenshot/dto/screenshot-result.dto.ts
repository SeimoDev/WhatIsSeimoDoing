import { IsString, Length } from 'class-validator';

export class ScreenshotResultDto {
  @IsString()
  @Length(1, 128)
  requestId!: string;
}
