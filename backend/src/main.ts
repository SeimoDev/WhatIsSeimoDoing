import { ValidationPipe } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { AppConfig } from './config/app-config';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  const configService = app.get(ConfigService);
  const server = configService.getOrThrow<AppConfig['server']>('server');
  const web = configService.getOrThrow<AppConfig['web']>('web');

  app.setGlobalPrefix('api/v1');
  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      transform: true,
      forbidNonWhitelisted: true,
    }),
  );
  app.enableCors({
    origin: [web.allowedOrigin],
    credentials: false,
  });

  const host = server.host;
  const port = server.port;
  await app.listen(port, host);
  // eslint-disable-next-line no-console
  console.log(`Backend listening on http://${host}:${port}`);
}

bootstrap();
