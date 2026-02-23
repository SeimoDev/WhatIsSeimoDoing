import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { DeviceAuthController } from './device-auth.controller';
import { DeviceAuthService } from './device-auth.service';
import { DatabaseModule } from '../database/database.module';
import { AppConfig } from '../config/app-config';
import { DeviceJwtGuard } from './guards/device-jwt.guard';

@Module({
  imports: [
    ConfigModule,
    DatabaseModule,
    JwtModule.registerAsync({
      imports: [ConfigModule],
      useFactory: (configService: ConfigService) => {
        const security = configService.getOrThrow<AppConfig['security']>('security');
        return {
          secret: security.jwtSecret,
          signOptions: { expiresIn: '1h' },
        };
      },
      inject: [ConfigService],
    }),
  ], 
  controllers: [DeviceAuthController],
  providers: [DeviceAuthService, DeviceJwtGuard],
  exports: [DeviceAuthService, DeviceJwtGuard],
})
export class DeviceAuthModule {}
