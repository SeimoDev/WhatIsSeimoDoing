import {
  IsArray,
  IsInt,
  IsString,
  Min,
  ValidateNested,
} from 'class-validator';
import { Type } from 'class-transformer';

class DailySnapshotAppItemDto {
  @IsString()
  packageName!: string;

  @IsInt()
  @Min(0)
  usageMsToday!: number;
}

export class DailySnapshotDto {
  @IsInt()
  @Min(1)
  ts!: number;

  @IsString()
  timezone!: string;

  @IsInt()
  @Min(0)
  totalNotificationCount!: number;

  @IsInt()
  @Min(0)
  unlockCount!: number;

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => DailySnapshotAppItemDto)
  apps!: DailySnapshotAppItemDto[];
}
