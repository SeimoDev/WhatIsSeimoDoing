import {
  IsBoolean,
  IsOptional,
  IsString,
  Length,
} from 'class-validator';

export class RegisterDeviceDto {
  @IsString()
  @Length(3, 120)
  deviceCode!: string;

  @IsString()
  @Length(1, 120)
  deviceName!: string;

  @IsString()
  @Length(1, 120)
  manufacturer!: string;

  @IsString()
  @Length(1, 120)
  model!: string;

  @IsString()
  @Length(1, 32)
  androidVersion!: string;

  @IsString()
  @Length(1, 32)
  appVersion!: string;

  @IsOptional()
  @IsBoolean()
  rootEnabled?: boolean;
}
