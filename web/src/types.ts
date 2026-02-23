export interface DeviceSummary {
  deviceId: string;
  deviceName: string;
  manufacturer: string;
  model: string;
  androidVersion: string;
  appVersion: string;
  rootEnabled: boolean;
  online: boolean;
  lastSeenAt: number | null;
  currentApp: CurrentApp | null;
}

export interface CurrentApp {
  packageName: string;
  appName: string | null;
  iconHash: string | null;
  iconBase64: string | null;
  foregroundStartedAt: number | null;
  todayUsageMs: number;
}

export interface TodayStats {
  deviceId: string;
  statDate: string;
  online: boolean;
  currentApp: CurrentApp | null;
  totalNotificationCount: number;
  unlockCount: number;
  snapshotTs: number | null;
  apps: Array<{
    packageName: string;
    appName: string;
    iconBase64: string | null;
    usageMs: number;
  }>;
  serverTs: number;
}

export interface HistoryPoint {
  statDate: string;
  totalUsageMs: number;
  totalNotificationCount: number;
  unlockCount: number;
  apps: Array<{
    packageName: string;
    appName: string;
    iconBase64: string | null;
    usageMs: number;
  }>;
}

export interface HistoryStats {
  deviceId: string;
  days: number;
  points: HistoryPoint[];
  serverTs: number;
}
