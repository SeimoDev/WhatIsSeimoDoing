import {
  IsArray,
  IsInt,
  IsOptional,
  IsString,
  Length,
  Min,
  ValidateNested,
} from 'class-validator';
import { Type } from 'class-transformer';

class AppCatalogItemDto {
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
}

export class AppCatalogSyncDto {
  @IsInt()
  @Min(1)
  ts!: number;

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => AppCatalogItemDto)
  apps!: AppCatalogItemDto[];
}
