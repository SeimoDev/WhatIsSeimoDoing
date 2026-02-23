import { IsInt, IsOptional, IsString, Length, Min } from 'class-validator';

export class ForegroundSwitchDto {
  @IsInt()
  @Min(1)
  ts!: number;

  @IsString()
  @Length(1, 255)
  packageName!: string;

  @IsString()
  @Length(1, 255)
  appName!: string;

  @IsOptional()
  @IsString()
  @Length(1, 255)
  iconHash?: string;

  @IsOptional()
  @IsString()
  iconBase64?: string;

  @IsInt()
  @Min(0)
  todayUsageMsAtSwitch!: number;
}
