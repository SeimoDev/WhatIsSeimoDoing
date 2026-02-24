export interface AppConfig {
  server: {
    host: string;
    port: number;
  };
  web: {
    port: number;
    allowedOrigin: string;
  };
  security: {
    jwtSecret: string;
    refreshSecret: string;
    screenshotPassword: string;
    screenshotTokenTtlMinutes: number;
    registerCode: string;
  };
  storage: {
    sqlitePath: string;
    retentionDays: number;
  };
  realtime: {
    heartbeatTimeoutSec: number;
    namespace: string;
    publicWsUrl: string;
  };
}
