<template>
  <div class="page-shell">
    <aside class="device-panel">
      <div class="panel-title">{{ t('app.panelTitle') }}</div>
      <p class="panel-subtitle">{{ t('app.panelSubtitle') }}</p>
      <button class="refresh-btn" @click="refreshDevices" :disabled="deviceLoading">
        {{ deviceLoading ? t('app.refreshing') : t('app.refresh') }}
      </button>

      <div class="device-list">
        <button
          v-for="device in devices"
          :key="device.deviceId"
          class="device-item"
          :class="{ selected: device.deviceId === selectedDeviceId, online: device.online }"
          @click="selectDevice(device.deviceId)"
        >
          <div class="device-head">
            <strong>{{ device.deviceName }}</strong>
            <span class="status-dot" />
          </div>
          <div class="device-meta">{{ device.manufacturer }} {{ device.model }}</div>
          <div class="device-meta">{{ device.currentApp?.appName || '-' }}</div>
        </button>
      </div>
    </aside>

    <main class="dashboard-main">
      <div class="dashboard-toolbar">
        <div class="locale-switcher">
          <button
            type="button"
            :class="{ active: locale === 'zh-CN' }"
            @click="onChangeLocale('zh-CN')"
          >
            {{ t('app.languageZh') }}
          </button>
          <span>|</span>
          <button
            type="button"
            :class="{ active: locale === 'en' }"
            @click="onChangeLocale('en')"
          >
            {{ t('app.languageEn') }}
          </button>
        </div>
      </div>

      <section v-if="selectedDevice" class="overview-section">
        <header class="section-header">
          <div>
            <h1>{{ selectedDevice.deviceName }}</h1>
            <p>{{ selectedDevice.manufacturer }} {{ selectedDevice.model }} · Android {{ selectedDevice.androidVersion }}</p>
          </div>
          <div class="state-pill" :class="{ online: selectedDevice.online }">
            {{ selectedDevice.online ? t('app.statusOnline') : t('app.statusOffline') }}
          </div>
        </header>

        <div class="overview-grid">
          <article class="card current-app-card">
            <h2>{{ t('currentApp.title') }}</h2>
            <div v-if="todayStats?.screenLocked" class="empty">
              {{ t('currentApp.phoneOff') }}
            </div>
            <div v-else-if="todayStats?.currentApp" class="current-app">
              <img
                v-if="todayStats.currentApp.iconBase64"
                class="app-icon"
                :src="`data:image/png;base64,${todayStats.currentApp.iconBase64}`"
                :alt="t('currentApp.iconAlt')"
              />
              <div v-else class="app-icon placeholder">{{ t('currentApp.placeholder') }}</div>
              <div class="app-info">
                <div class="app-name">{{ todayStats.currentApp.appName || todayStats.currentApp.packageName }}</div>
                <div class="app-package">{{ todayStats.currentApp.packageName }}</div>
              </div>
            </div>
            <div v-else class="empty">{{ t('currentApp.empty') }}</div>
          </article>

          <article class="card stat-card">
            <h2>{{ t('summary.title') }}</h2>
            <div class="metric-row">
              <span>{{ t('summary.unlockCount') }}</span>
              <strong>{{ todayStats?.unlockCount ?? 0 }}</strong>
            </div>
            <div class="metric-row">
              <span>{{ t('summary.notificationCount') }}</span>
              <strong>{{ todayStats?.totalNotificationCount ?? 0 }}</strong>
            </div>
            <div class="metric-row">
              <span>{{ t('summary.snapshotTime') }}</span>
              <strong>{{ formatDateTime(todayStats?.snapshotTs) }}</strong>
            </div>
          </article>

          <article class="card screenshot-card">
            <h2>{{ t('screenshot.title') }}</h2>
            <button class="screenshot-btn" @click="onRequestScreenshot" :disabled="screenshotLoading">
              {{ screenshotLoading ? t('screenshot.requesting') : t('screenshot.request') }}
            </button>
            <p v-if="screenshotHint" class="hint">{{ screenshotHint }}</p>
          </article>
        </div>
      </section>

      <section v-if="todayStats" class="card table-card">
        <div class="section-header compact">
          <h2>{{ t('usage.todayTitle') }}</h2>
          <span>{{ todayStats.statDate }}</span>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{{ t('usage.tableApp') }}</th>
                <th>{{ t('usage.tablePackage') }}</th>
                <th>{{ t('usage.tableUsage') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in visibleTodayApps" :key="item.packageName">
                <td class="app-cell">
                  <img
                    v-if="item.iconBase64"
                    class="table-icon"
                    :src="`data:image/png;base64,${item.iconBase64}`"
                    :alt="t('currentApp.iconAlt')"
                  />
                  <span>{{ item.appName }}</span>
                </td>
                <td>{{ item.packageName }}</td>
                <td>{{ formatDuration(item.usageMs) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="historyStats" class="card history-card">
        <div class="section-header compact">
          <h2>{{ t('usage.historyTitle') }}</h2>
          <span>{{ t('usage.days', { days: historyStats.days }) }}</span>
        </div>
        <div ref="chartRef" class="chart" />

        <div class="history-list">
          <article v-for="day in visibleHistoryPoints" :key="day.statDate" class="history-day">
            <header>
              <strong>{{ day.statDate }}</strong>
              <span>{{ t('usage.usage') }} {{ formatDuration(day.totalUsageMs) }}</span>
              <span>{{ t('usage.notif') }} {{ day.totalNotificationCount }}</span>
              <span>{{ t('usage.unlock') }} {{ day.unlockCount }}</span>
            </header>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{{ t('usage.tableApp') }}</th>
                    <th>{{ t('usage.tableUsage') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="app in day.apps" :key="`${day.statDate}-${app.packageName}`">
                    <td>{{ app.appName }}</td>
                    <td>{{ formatDuration(app.usageMs) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </article>
        </div>
      </section>

      <section v-if="errorMessage" class="error-banner">
        {{ errorMessage }}
      </section>
    </main>

    <div v-if="showPasswordModal" class="modal-mask" @click.self="showPasswordModal = false">
      <div class="modal-card">
        <h3>{{ t('screenshot.password.title') }}</h3>
        <input
          v-model="passwordInput"
          type="password"
          :placeholder="t('screenshot.password.placeholder')"
          @keyup.enter="submitPassword"
        />
        <div class="modal-actions">
          <button @click="showPasswordModal = false">{{ t('screenshot.password.cancel') }}</button>
          <button class="primary" @click="submitPassword">{{ t('screenshot.password.confirm') }}</button>
        </div>
      </div>
    </div>

    <div v-if="showScreenshotProgressModal" class="modal-mask">
      <div class="progress-modal-card">
        <h3>{{ t('screenshot.requesting') }}</h3>
        <p v-if="screenshotProgressRequestId" class="viewer-meta">
          {{ t('screenshot.requestSent', { requestId: screenshotProgressRequestId }) }}
        </p>
        <div class="progress-track">
          <div class="progress-fill" :style="{ width: `${screenshotProgress}%` }" />
        </div>
        <p class="progress-value">{{ screenshotProgress }}%</p>
        <div class="progress-log-wrap">
          <p
            v-for="(line, index) in screenshotProgressLogs"
            :key="`${screenshotProgressRequestId}-${index}`"
            class="progress-log-line"
          >
            {{ line }}
          </p>
        </div>
      </div>
    </div>

    <div v-if="showScreenshotViewerModal" class="modal-mask" @click.self="showScreenshotViewerModal = false">
      <div class="viewer-modal-card">
        <h3>{{ t('screenshot.viewer.title') }}</h3>
        <p v-if="screenshotMeta" class="viewer-meta">
          {{ t('screenshot.viewer.requestId', { requestId: screenshotMeta.requestId }) }}
        </p>
        <p v-if="screenshotMeta" class="viewer-meta">
          {{ t('screenshot.viewer.capturedAt', { time: formatDateTime(screenshotMeta.timestamp) }) }}
        </p>
        <div class="viewer-image-wrap">
          <img
            v-if="screenshotImageDataUrl"
            class="viewer-image"
            :src="screenshotImageDataUrl"
            :alt="t('screenshot.viewer.imageAlt')"
          />
        </div>
        <div class="viewer-actions">
          <button class="primary" @click="saveScreenshotImage">{{ t('screenshot.viewer.save') }}</button>
          <button @click="showScreenshotViewerModal = false">{{ t('screenshot.viewer.close') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import dayjs from 'dayjs';
import * as echarts from 'echarts';
import { io, type Socket } from 'socket.io-client';
import { useI18n } from 'vue-i18n';
import { apiClient, wsBaseUrl } from './api';
import { setAppLocale, type AppLocale } from './i18n';
import type { DeviceSummary, HistoryStats, TodayStats } from './types';

interface DeviceListResponse {
  devices: DeviceSummary[];
  serverTs: number;
}

interface ScreenshotMeta {
  requestId: string;
  timestamp: number;
}

type UsageAppItem = TodayStats['apps'][number];
type HistoryDisplayPoint = HistoryStats['points'][number];

const { t, locale } = useI18n();

const devices = ref<DeviceSummary[]>([]);
const selectedDeviceId = ref<string>('');
const todayStats = ref<TodayStats | null>(null);
const historyStats = ref<HistoryStats | null>(null);
const errorMessage = ref<string>('');
const deviceLoading = ref<boolean>(false);
const screenshotLoading = ref<boolean>(false);
const screenshotImageDataUrl = ref<string>('');
const screenshotHint = ref<string>('');
const showPasswordModal = ref<boolean>(false);
const showScreenshotProgressModal = ref<boolean>(false);
const showScreenshotViewerModal = ref<boolean>(false);
const screenshotMeta = ref<ScreenshotMeta | null>(null);
const passwordInput = ref<string>('');
const screenshotProgress = ref<number>(0);
const screenshotProgressLogs = ref<string[]>([]);
const screenshotProgressRequestId = ref<string>('');
const screenshotProgressStartedAt = ref<number>(0);
const screenshotProgressDurationMs = ref<number>(0);
const activeScreenshotRequestId = ref<string>('');
const activeScreenshotDeviceId = ref<string>('');

const screenshotToken = ref<string>('');
const screenshotTokenExpireAt = ref<number>(0);

const chartRef = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;
let socket: Socket | null = null;
let screenshotProgressTimer: number | null = null;

const selectedDevice = computed(() =>
  devices.value.find((item) => item.deviceId === selectedDeviceId.value),
);

const visibleTodayApps = computed<UsageAppItem[]>(() => {
  if (!todayStats.value) {
    return [];
  }
  return filterUsageApps(todayStats.value.apps);
});

const visibleHistoryPoints = computed<HistoryDisplayPoint[]>(() => {
  if (!historyStats.value) {
    return [];
  }

  return historyStats.value.points.map((point) => {
    const apps = filterUsageApps(point.apps);
    return {
      ...point,
      apps,
      totalUsageMs: apps.reduce((total, app) => total + app.usageMs, 0),
    };
  });
});

function onChangeLocale(next: AppLocale) {
  if (locale.value === next) {
    return;
  }
  setAppLocale(next);
}

function pushScreenshotProgressLog(message: string) {
  const line = `[${dayjs().format('HH:mm:ss')}] ${message}`;
  screenshotProgressLogs.value = [...screenshotProgressLogs.value, line].slice(-8);
}

function stopScreenshotProgressTracking() {
  if (screenshotProgressTimer !== null) {
    window.clearInterval(screenshotProgressTimer);
    screenshotProgressTimer = null;
  }
}

function closeScreenshotProgressModal() {
  stopScreenshotProgressTracking();
  showScreenshotProgressModal.value = false;
  screenshotProgress.value = 0;
  screenshotProgressRequestId.value = '';
  screenshotProgressStartedAt.value = 0;
  screenshotProgressDurationMs.value = 0;
  screenshotProgressLogs.value = [];
}

function clearActiveScreenshotRequest() {
  activeScreenshotRequestId.value = '';
  activeScreenshotDeviceId.value = '';
  screenshotLoading.value = false;
}

function startScreenshotProgress(requestId: string, timeoutSec: number) {
  stopScreenshotProgressTracking();
  showScreenshotProgressModal.value = true;
  screenshotProgress.value = 0;
  screenshotProgressRequestId.value = requestId;
  screenshotProgressStartedAt.value = Date.now();
  screenshotProgressDurationMs.value = Math.max(timeoutSec, 5) * 1000;
  screenshotProgressLogs.value = [];

  pushScreenshotProgressLog(t('screenshot.waiting'));
  screenshotProgressTimer = window.setInterval(() => {
    if (!showScreenshotProgressModal.value || screenshotProgressDurationMs.value <= 0) {
      return;
    }

    const elapsedMs = Date.now() - screenshotProgressStartedAt.value;
    const percent = Math.floor((elapsedMs / screenshotProgressDurationMs.value) * 100);
    screenshotProgress.value = Math.min(99, Math.max(0, percent));
  }, 300);
}

async function refreshDevices() {
  deviceLoading.value = true;
  errorMessage.value = '';

  try {
    const { data } = await apiClient.get<DeviceListResponse>('/dashboard/devices');
    devices.value = data.devices;

    if (!selectedDeviceId.value && data.devices.length > 0) {
      selectedDeviceId.value = data.devices[0]!.deviceId;
    }

    if (selectedDeviceId.value) {
      await Promise.all([
        fetchToday(selectedDeviceId.value),
        fetchHistory(selectedDeviceId.value),
      ]);
    }
  } catch (error) {
    errorMessage.value = t('error.loadDevices', { msg: toMessage(error) });
  } finally {
    deviceLoading.value = false;
  }
}

async function selectDevice(deviceId: string) {
  clearActiveScreenshotRequest();
  selectedDeviceId.value = deviceId;
  screenshotImageDataUrl.value = '';
  screenshotHint.value = '';
  closeScreenshotProgressModal();
  showScreenshotViewerModal.value = false;
  screenshotMeta.value = null;
  await Promise.all([fetchToday(deviceId), fetchHistory(deviceId)]);
}

async function fetchToday(deviceId: string) {
  try {
    const { data } = await apiClient.get<TodayStats>(`/dashboard/devices/${deviceId}/today`);
    todayStats.value = data;
  } catch (error) {
    errorMessage.value = t('error.loadToday', { msg: toMessage(error) });
  }
}

async function fetchHistory(deviceId: string) {
  try {
    const { data } = await apiClient.get<HistoryStats>(
      `/dashboard/devices/${deviceId}/history?days=7`,
    );
    historyStats.value = data;
  } catch (error) {
    errorMessage.value = t('error.loadHistory', { msg: toMessage(error) });
  }
}

async function onRequestScreenshot() {
  if (!selectedDeviceId.value) {
    return;
  }

  if (!hasValidScreenshotToken()) {
    showPasswordModal.value = true;
    return;
  }

  await requestScreenshot(selectedDeviceId.value);
}

async function submitPassword() {
  if (!passwordInput.value.trim()) {
    return;
  }

  try {
    const { data } = await apiClient.post<{ screenshotToken: string; expiresInMinutes: number }>(
      '/screenshots/auth',
      {
        password: passwordInput.value,
      },
    );

    screenshotToken.value = data.screenshotToken;
    screenshotTokenExpireAt.value = Date.now() + data.expiresInMinutes * 60 * 1000;
    passwordInput.value = '';
    showPasswordModal.value = false;

    if (selectedDeviceId.value) {
      await requestScreenshot(selectedDeviceId.value);
    }
  } catch (error) {
    screenshotHint.value = t('screenshot.authFailed', { msg: toMessage(error) });
  }
}

async function requestScreenshot(deviceId: string) {
  screenshotLoading.value = true;
  screenshotHint.value = t('screenshot.waiting');
  closeScreenshotProgressModal();

  try {
    const { data } = await apiClient.post<{ requestId: string; timeoutSec: number }>(
      '/screenshots/request',
      { deviceId },
      {
        headers: {
          Authorization: `Bearer ${screenshotToken.value}`,
        },
      },
    );

    activeScreenshotRequestId.value = data.requestId;
    activeScreenshotDeviceId.value = deviceId;
    const timeoutSec = Number.isFinite(data.timeoutSec) ? Math.max(5, data.timeoutSec) : 30;
    startScreenshotProgress(data.requestId, timeoutSec);
    pushScreenshotProgressLog(t('screenshot.requestSent', { requestId: data.requestId }));
    screenshotHint.value = t('screenshot.requestSent', { requestId: data.requestId });
  } catch (error) {
    closeScreenshotProgressModal();
    clearActiveScreenshotRequest();
    screenshotHint.value = t('screenshot.requestFailed', { msg: toMessage(error) });
  }
}

function hasValidScreenshotToken() {
  return Boolean(screenshotToken.value) && Date.now() < screenshotTokenExpireAt.value;
}

function mapScreenshotReason(reason: string): string {
  const normalized = reason.trim().toUpperCase();
  if (normalized === 'DEVICE_OFFLINE') {
    return t('screenshot.reason.deviceOffline');
  }
  if (normalized === 'SCREENSHOT_TIMEOUT') {
    return t('screenshot.reason.timeout');
  }
  return reason;
}

async function saveScreenshotImage() {
  if (!screenshotImageDataUrl.value) {
    return;
  }

  try {
    const response = await fetch(screenshotImageDataUrl.value);
    const blob = await response.blob();
    const ts = screenshotMeta.value?.timestamp ?? Date.now();
    const deviceId = selectedDeviceId.value || 'unknown';
    const fileName = `screenshot-${deviceId}-${dayjs(ts).format('YYYYMMDD-HHmmss')}.png`;

    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(objectUrl);
  } catch (error) {
    screenshotHint.value = t('screenshot.viewer.saveFailed', { msg: toMessage(error) });
  }
}

function bindSocket() {
  socket = io(wsBaseUrl, {
    auth: {
      clientType: 'web',
    },
    transports: ['websocket'],
  });

  socket.on('device.online', (payload: { deviceId: string }) => {
    const device = devices.value.find((d) => d.deviceId === payload.deviceId);
    if (device) {
      device.online = true;
    }
    if (todayStats.value?.deviceId === payload.deviceId) {
      todayStats.value.online = true;
    }
  });

  socket.on('device.offline', (payload: { deviceId: string }) => {
    const device = devices.value.find((d) => d.deviceId === payload.deviceId);
    if (device) {
      device.online = false;
    }
    if (todayStats.value?.deviceId === payload.deviceId) {
      todayStats.value.online = false;
    }
  });

  socket.on(
    'foreground.updated',
    (payload: {
      deviceId: string;
      packageName: string;
      appName: string;
      iconBase64?: string | null;
      todayUsageMsAtSwitch: number;
      ts: number;
    }) => {
      const device = devices.value.find((d) => d.deviceId === payload.deviceId);
      if (device) {
        const previousIconBase64 = device.currentApp?.iconBase64 ?? null;
        const nextIconBase64 = payload.iconBase64 ?? previousIconBase64;
        device.currentApp = {
          packageName: payload.packageName,
          appName: payload.appName,
          iconHash: null,
          iconBase64: nextIconBase64,
          foregroundStartedAt: payload.ts,
          todayUsageMs: payload.todayUsageMsAtSwitch,
        };
        device.screenLocked = false;
        device.online = true;
      }

      if (todayStats.value?.deviceId === payload.deviceId) {
        const previousIconBase64 = todayStats.value.currentApp?.iconBase64 ?? null;
        const nextIconBase64 = payload.iconBase64 ?? previousIconBase64;
        todayStats.value.currentApp = {
          packageName: payload.packageName,
          appName: payload.appName,
          iconHash: null,
          iconBase64: nextIconBase64,
          foregroundStartedAt: payload.ts,
          todayUsageMs: payload.todayUsageMsAtSwitch,
        };
        todayStats.value.screenLocked = false;
        todayStats.value.online = true;
      }
    },
  );

  socket.on(
    'screen.state.updated',
    (payload: { deviceId: string; isScreenLocked: boolean; ts: number }) => {
      const device = devices.value.find((d) => d.deviceId === payload.deviceId);
      if (device) {
        device.screenLocked = payload.isScreenLocked;
      }

      if (todayStats.value?.deviceId === payload.deviceId) {
        todayStats.value.screenLocked = payload.isScreenLocked;
      }
    },
  );

  socket.on(
    'stats.updated',
    (payload: {
      deviceId: string;
      ts: number;
      totalNotificationCount: number;
      unlockCount: number;
      apps: Array<{ packageName: string; usageMsToday: number }>;
    }) => {
      if (!todayStats.value || todayStats.value.deviceId !== payload.deviceId) {
        return;
      }

      todayStats.value.totalNotificationCount = payload.totalNotificationCount;
      todayStats.value.unlockCount = payload.unlockCount;
      todayStats.value.snapshotTs = payload.ts;

      if (todayStats.value.currentApp) {
        const current = payload.apps.find(
          (app) => app.packageName === todayStats.value?.currentApp?.packageName,
        );
        if (current) {
          todayStats.value.currentApp.todayUsageMs = current.usageMsToday;
        }
      }

      fetchToday(payload.deviceId).catch(() => undefined);
      fetchHistory(payload.deviceId).catch(() => undefined);
    },
  );

  socket.on(
    'app.catalog.synced',
    (payload: { deviceId: string; ts: number; count: number }) => {
      if (!selectedDeviceId.value || payload.deviceId !== selectedDeviceId.value) {
        return;
      }

      fetchToday(payload.deviceId).catch(() => undefined);
      fetchHistory(payload.deviceId).catch(() => undefined);
    },
  );

  socket.on(
    'screenshot.ready',
    (payload: { deviceId: string; imageDataUrl: string; requestId: string }) => {
      const hasActiveRequest = Boolean(activeScreenshotRequestId.value);
      if (hasActiveRequest && payload.requestId !== activeScreenshotRequestId.value) {
        return;
      }

      if (!hasActiveRequest && payload.deviceId !== selectedDeviceId.value) {
        return;
      }

      screenshotProgress.value = 100;
      pushScreenshotProgressLog(t('screenshot.ready', { requestId: payload.requestId }));
      closeScreenshotProgressModal();
      clearActiveScreenshotRequest();

      if (payload.deviceId === selectedDeviceId.value) {
        screenshotImageDataUrl.value = payload.imageDataUrl;
        screenshotMeta.value = {
          requestId: payload.requestId,
          timestamp: Date.now(),
        };
        showScreenshotViewerModal.value = true;
      }
      screenshotHint.value = t('screenshot.ready', { requestId: payload.requestId });
    },
  );

  socket.on('screenshot.error', (payload: { requestId?: string; deviceId: string; reason: string }) => {
    const hasActiveRequest = Boolean(activeScreenshotRequestId.value);
    if (
      hasActiveRequest &&
      payload.requestId &&
      payload.requestId !== activeScreenshotRequestId.value
    ) {
      return;
    }

    if (
      hasActiveRequest &&
      activeScreenshotDeviceId.value &&
      payload.deviceId !== activeScreenshotDeviceId.value
    ) {
      return;
    }

    if (!hasActiveRequest && payload.deviceId !== selectedDeviceId.value) {
      return;
    }

    screenshotProgress.value = 100;
    pushScreenshotProgressLog(
      t('screenshot.failed', {
        reason: mapScreenshotReason(payload.reason),
      }),
    );
    closeScreenshotProgressModal();
    clearActiveScreenshotRequest();
    screenshotHint.value = t('screenshot.failed', {
      reason: mapScreenshotReason(payload.reason),
    });
  });
}

function renderHistoryChart() {
  if (!chartRef.value || !historyStats.value) {
    return;
  }

  if (!chart) {
    chart = echarts.init(chartRef.value);
  }

  const points = visibleHistoryPoints.value;
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
    },
    legend: {
      data: [
        t('usage.chartLegendUsage'),
        t('usage.chartLegendNotifications'),
        t('usage.chartLegendUnlock'),
      ],
      textStyle: {
        color: '#27423c',
      },
    },
    xAxis: {
      type: 'category',
      data: points.map((p) => p.statDate),
    },
    yAxis: [
      {
        type: 'value',
        name: t('usage.chartYAxisUsage'),
      },
      {
        type: 'value',
        name: t('usage.chartYAxisCount'),
      },
    ],
    series: [
      {
        name: t('usage.chartLegendUsage'),
        type: 'line',
        smooth: true,
        data: points.map((p) => Number((p.totalUsageMs / 3600000).toFixed(2))),
        lineStyle: { color: '#007f73', width: 3 },
      },
      {
        name: t('usage.chartLegendNotifications'),
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: points.map((p) => p.totalNotificationCount),
        lineStyle: { color: '#f18f01', width: 2 },
      },
      {
        name: t('usage.chartLegendUnlock'),
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: points.map((p) => p.unlockCount),
        lineStyle: { color: '#c73e1d', width: 2 },
      },
    ],
  });
}

function filterUsageApps<T extends { packageName: string; appName: string; iconBase64: string | null }>(
  apps: T[],
): T[] {
  return apps.filter((app) => !isPackageOnlyRecord(app));
}

function isPackageOnlyRecord(app: {
  packageName: string;
  appName: string;
  iconBase64: string | null;
}): boolean {
  const packageName = app.packageName.trim();
  const appName = app.appName.trim();
  const hasIcon = Boolean(app.iconBase64 && app.iconBase64.trim().length > 0);
  const hasReadableName = appName.length > 0 && appName !== packageName;

  return !hasReadableName && !hasIcon;
}

function formatDuration(ms: number): string {
  if (!ms || ms < 0) {
    return '0m';
  }

  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;

  if (h > 0) {
    return `${h}h ${m}m ${s}s`;
  }
  if (m > 0) {
    return `${m}m ${s}s`;
  }

  return `${s}s`;
}

function formatDateTime(ts?: number | null) {
  if (!ts) {
    return '-';
  }
  return dayjs(ts).format('YYYY-MM-DD HH:mm:ss');
}

function toMessage(error: unknown): string {
  const maybe = error as {
    response?: { data?: { message?: string } };
    message?: string;
  };

  return maybe.response?.data?.message ?? maybe.message ?? t('error.unknown');
}

watch(
  () => historyStats.value,
  async () => {
    await nextTick();
    renderHistoryChart();
  },
);

watch(
  () => locale.value,
  async () => {
    await nextTick();
    renderHistoryChart();
  },
);

onMounted(async () => {
  await refreshDevices();
  bindSocket();

  window.addEventListener('resize', renderHistoryChart);
});

onUnmounted(() => {
  window.removeEventListener('resize', renderHistoryChart);
  stopScreenshotProgressTracking();
  clearActiveScreenshotRequest();
  socket?.disconnect();
  chart?.dispose();
  chart = null;
});
</script>
