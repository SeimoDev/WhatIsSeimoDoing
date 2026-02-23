import { IsString, Length } from 'class-validator';

export class AuthScreenshotDto {
  @IsString()
  @Length(4, 128)
  password!: string;
}
