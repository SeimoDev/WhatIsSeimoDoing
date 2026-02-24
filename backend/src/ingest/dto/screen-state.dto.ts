import { IsBoolean, IsInt, Min } from 'class-validator';

export class ScreenStateDto {
  @IsInt()
  @Min(1)
  ts!: number;

  @IsBoolean()
  isScreenLocked!: boolean;
}
