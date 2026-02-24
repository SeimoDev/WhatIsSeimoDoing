import * as fs from 'node:fs';
import * as path from 'node:path';
import { load } from 'js-yaml';
import * as Joi from 'joi';
import { AppConfig } from './app-config';

const configSchema = Joi.object<AppConfig>({
  server: Joi.object({
    host: Joi.string().required(),
    port: Joi.number().port().required(),
  }).required(),
  web: Joi.object({
    port: Joi.number().port().required(),
    allowedOrigin: Joi.string().required(),
  }).required(),
  security: Joi.object({
    jwtSecret: Joi.string().min(16).required(),
    refreshSecret: Joi.string().min(16).required(),
    screenshotPassword: Joi.string().min(4).required(),
    screenshotTokenTtlMinutes: Joi.number().integer().min(1).required(),
    registerCode: Joi.string().min(3).required(),
  }).required(),
  storage: Joi.object({
    sqlitePath: Joi.string().required(),
    retentionDays: Joi.number().integer().min(1).required(),
  }).required(),
  realtime: Joi.object({
    heartbeatTimeoutSec: Joi.number().integer().min(5).required(),
    screenshotTimeoutSec: Joi.number().integer().min(5).required(),
    namespace: Joi.string().pattern(/^\/.+/).required(),
    publicWsUrl: Joi.string().uri({ scheme: ['ws', 'wss'] }).required(),
  }).required(),
}).required();

export const loadConfiguration = (): AppConfig => {
  const configPath = process.env.APP_CONFIG_PATH ?? path.resolve(process.cwd(), 'config.yaml');

  if (!fs.existsSync(configPath)) {
    throw new Error(`Missing config file: ${configPath}`);
  }

  const raw = fs.readFileSync(configPath, 'utf-8');
  const parsed = load(raw) as AppConfig;
  const overridden: AppConfig = {
    ...parsed,
    server: {
      ...parsed.server,
      host: process.env.SERVER_HOST ?? parsed.server.host,
      port: Number(process.env.SERVER_PORT ?? parsed.server.port),
    },
    web: {
      ...parsed.web,
      allowedOrigin: process.env.WEB_ALLOWED_ORIGIN ?? parsed.web.allowedOrigin,
      port: Number(process.env.WEB_PORT ?? parsed.web.port),
    },
    security: {
      ...parsed.security,
      screenshotPassword:
        process.env.SCREENSHOT_PASSWORD ?? parsed.security.screenshotPassword,
    },
    storage: {
      ...parsed.storage,
      retentionDays: Number(process.env.RETENTION_DAYS ?? parsed.storage.retentionDays),
      sqlitePath: process.env.SQLITE_PATH ?? parsed.storage.sqlitePath,
    },
    realtime: {
      ...parsed.realtime,
      heartbeatTimeoutSec: Number(
        process.env.HEARTBEAT_TIMEOUT_SEC ?? parsed.realtime.heartbeatTimeoutSec,
      ),
      screenshotTimeoutSec: Number(
        process.env.SCREENSHOT_TIMEOUT_SEC ?? parsed.realtime.screenshotTimeoutSec ?? 30,
      ),
      namespace: process.env.WS_NAMESPACE ?? parsed.realtime.namespace,
      publicWsUrl: process.env.PUBLIC_WS_URL ?? parsed.realtime.publicWsUrl,
    },
  };

  const { error, value } = configSchema.validate(overridden, {
    abortEarly: false,
    stripUnknown: true,
  });

  if (error) {
    throw new Error(`Invalid config.yaml: ${error.message}`);
  }

  process.env.SERVER_HOST = value.server.host;
  process.env.SERVER_PORT = String(value.server.port);
  process.env.WS_NAMESPACE = value.realtime.namespace;
  process.env.PUBLIC_WS_URL = value.realtime.publicWsUrl;

  return value;
};
