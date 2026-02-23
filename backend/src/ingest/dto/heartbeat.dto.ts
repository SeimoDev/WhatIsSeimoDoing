import { IsBoolean, IsInt, IsString, Max, Min } from 'class-validator';

export class HeartbeatDto {
  @IsInt()
  @Min(1)
  ts!: number;

  @IsInt()
  @Min(0)
  @Max(100)
  batteryPct!: number;

  @IsBoolean()
  isCharging!: boolean;

  @IsString()
  networkType!: string;
}
