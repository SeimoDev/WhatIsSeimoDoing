<template>
  <div class="page-shell">
    <aside class="device-panel">
      <div class="panel-title">WhatIsSeimoDoing</div>
      <p class="panel-subtitle">Devices</p>
      <button class="refresh-btn" @click="refreshDevices" :disabled="deviceLoading">
        {{ deviceLoading ? 'Refreshing...' : 'Refresh' }}
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
      <section v-if="selectedDevice" class="overview-section">
        <header class="section-header">
          <div>
            <h1>{{ selectedDevice.deviceName }}</h1>
            <p>{{ selectedDevice.manufacturer }} {{ selectedDevice.model }} · Android {{ selectedDevice.androidVersion }}</p>
          </div>
          <div class="state-pill" :class="{ online: selectedDevice.online }">
            {{ selectedDevice.online ? 'ONLINE' : 'OFFLINE' }}
          </div>
        </header>

        <div class="overview-grid">
          <article class="card current-app-card">
            <h2>Current App</h2>
            <div v-if="todayStats?.currentApp" class="current-app">
              <img
                v-if="todayStats.currentApp.iconBase64"
                class="app-icon"
                :src="`data:image/png;base64,${todayStats.currentApp.iconBase64}`"
                alt="icon"
              />
              <div v-else class="app-icon placeholder">APP</div>
              <div class="app-info">
                <div class="app-name">{{ todayStats.currentApp.appName || todayStats.currentApp.packageName }}</div>
                <div class="app-package">{{ todayStats.currentApp.packageName }}</div>
                <div class="app-runtime">Today runtime: {{ formatDuration(currentRuntimeMs) }}</div>
              </div>
            </div>
            <div v-else class="empty">No foreground app data.</div>
          </article>

          <article class="card stat-card">
            <h2>Today Summary</h2>
            <div class="metric-row">
              <span>Unlock Count</span>
              <strong>{{ todayStats?.unlockCount ?? 0 }}</strong>
            </div>
            <div class="metric-row">
              <span>Notification Count</span>
              <strong>{{ todayStats?.totalNotificationCount ?? 0 }}</strong>
            </div>
            <div class="metric-row">
              <span>Snapshot Time</span>
              <strong>{{ formatDateTime(todayStats?.snapshotTs) }}</strong>
            </div>
          </article>

          <article class="card screenshot-card">
            <h2>Remote Screenshot</h2>
            <button class="screenshot-btn" @click="onRequestScreenshot" :disabled="screenshotLoading">
              {{ screenshotLoading ? 'Requesting...' : 'Request Screenshot' }}
            </button>
            <p v-if="screenshotHint" class="hint">{{ screenshotHint }}</p>
            <img
              v-if="screenshotImageDataUrl"
              class="screenshot-preview"
              :src="screenshotImageDataUrl"
              alt="screenshot"
            />
          </article>
        </div>
      </section>

      <section v-if="todayStats" class="card table-card">
        <div class="section-header compact">
          <h2>Today App Usage</h2>
          <span>{{ todayStats.statDate }}</span>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>App</th>
                <th>Package</th>
                <th>Usage</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in todayStats.apps" :key="item.packageName">
                <td class="app-cell">
                  <img
                    v-if="item.iconBase64"
                    class="table-icon"
                    :src="`data:image/png;base64,${item.iconBase64}`"
                    alt="icon"
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
          <h2>7-Day History</h2>
          <span>{{ historyStats.days }} days</span>
        </div>
        <div ref="chartRef" class="chart" />

        <div class="history-list">
          <article v-for="day in historyStats.points" :key="day.statDate" class="history-day">
            <header>
              <strong>{{ day.statDate }}</strong>
              <span>Usage {{ formatDuration(day.totalUsageMs) }}</span>
              <span>Notif {{ day.totalNotificationCount }}</span>
              <span>Unlock {{ day.unlockCount }}</span>
            </header>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>App</th>
                    <th>Usage</th>
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
        <h3>Screenshot Password</h3>
        <input
          v-model="passwordInput"
          type="password"
          placeholder="Enter password"
          @keyup.enter="submitPassword"
        />
        <div class="modal-actions">
          <button @click="showPasswordModal = false">Cancel</button>
          <button class="primary" @click="submitPassword">Confirm</button>
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
import { apiClient, wsBaseUrl } from './api';
import type { DeviceSummary, HistoryStats, TodayStats } from './types';

interface DeviceListResponse {
  devices: DeviceSummary[];
  serverTs: number;
}

interface UsageAnchor {
  packageName: string;
  baseMs: number;
  baseTs: number;
}

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
const passwordInput = ref<string>('');

const screenshotToken = ref<string>('');
const screenshotTokenExpireAt = ref<number>(0);

const chartRef = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;
let socket: Socket | null = null;
let ticker: number | null = null;
const nowTick = ref<number>(Date.now());
const usageAnchors = ref<Record<string, UsageAnchor>>({});

const selectedDevice = computed(() =>
  devices.value.find((item) => item.deviceId === selectedDeviceId.value),
);

const currentRuntimeMs = computed(() => {
  const stats = todayStats.value;
  if (!stats?.currentApp || !stats.online) {
    return stats?.currentApp?.todayUsageMs ?? 0;
  }

  const anchor = usageAnchors.value[stats.deviceId];
  if (!anchor || anchor.packageName !== stats.currentApp.packageName) {
    return stats.currentApp.todayUsageMs;
  }

  const elapsed = Math.max(0, nowTick.value - anchor.baseTs);
  return anchor.baseMs + elapsed;
});

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
    errorMessage.value = `Load devices failed: ${toMessage(error)}`;
  } finally {
    deviceLoading.value = false;
  }
}

async function selectDevice(deviceId: string) {
  selectedDeviceId.value = deviceId;
  screenshotImageDataUrl.value = '';
  screenshotHint.value = '';
  await Promise.all([fetchToday(deviceId), fetchHistory(deviceId)]);
}

async function fetchToday(deviceId: string) {
  try {
    const { data } = await apiClient.get<TodayStats>(`/dashboard/devices/${deviceId}/today`);
    todayStats.value = data;

    if (data.currentApp) {
      usageAnchors.value[data.deviceId] = {
        packageName: data.currentApp.packageName,
        baseMs: data.currentApp.todayUsageMs,
        baseTs: data.snapshotTs ?? Date.now(),
      };
    }
  } catch (error) {
    errorMessage.value = `Load today stats failed: ${toMessage(error)}`;
  }
}

async function fetchHistory(deviceId: string) {
  try {
    const { data } = await apiClient.get<HistoryStats>(
      `/dashboard/devices/${deviceId}/history?days=7`,
    );
    historyStats.value = data;
  } catch (error) {
    errorMessage.value = `Load history failed: ${toMessage(error)}`;
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
    screenshotHint.value = `Auth failed: ${toMessage(error)}`;
  }
}

async function requestScreenshot(deviceId: string) {
  screenshotLoading.value = true;
  screenshotHint.value = 'Waiting device response...';

  try {
    const { data } = await apiClient.post<{ requestId: string }>(
      '/screenshots/request',
      { deviceId },
      {
        headers: {
          Authorization: `Bearer ${screenshotToken.value}`,
        },
      },
    );

    screenshotHint.value = `Request sent (${data.requestId})`;
  } catch (error) {
    screenshotHint.value = `Request failed: ${toMessage(error)}`;
  } finally {
    screenshotLoading.value = false;
  }
}

function hasValidScreenshotToken() {
  return screenshotToken.value && Date.now() < screenshotTokenExpireAt.value;
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
        device.currentApp = {
          packageName: payload.packageName,
          appName: payload.appName,
          iconHash: null,
          iconBase64: payload.iconBase64 ?? null,
          foregroundStartedAt: payload.ts,
          todayUsageMs: payload.todayUsageMsAtSwitch,
        };
        device.online = true;
      }

      usageAnchors.value[payload.deviceId] = {
        packageName: payload.packageName,
        baseMs: payload.todayUsageMsAtSwitch,
        baseTs: payload.ts,
      };

      if (todayStats.value?.deviceId === payload.deviceId) {
        todayStats.value.currentApp = {
          packageName: payload.packageName,
          appName: payload.appName,
          iconHash: null,
          iconBase64: payload.iconBase64 ?? null,
          foregroundStartedAt: payload.ts,
          todayUsageMs: payload.todayUsageMsAtSwitch,
        };
        todayStats.value.online = true;
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
          usageAnchors.value[payload.deviceId] = {
            packageName: current.packageName,
            baseMs: current.usageMsToday,
            baseTs: payload.ts,
          };
        }
      }

      fetchToday(payload.deviceId).catch(() => undefined);
      fetchHistory(payload.deviceId).catch(() => undefined);
    },
  );

  socket.on(
    'screenshot.ready',
    (payload: { deviceId: string; imageDataUrl: string; requestId: string }) => {
      if (payload.deviceId !== selectedDeviceId.value) {
        return;
      }

      screenshotImageDataUrl.value = payload.imageDataUrl;
      screenshotHint.value = `Screenshot ready (${payload.requestId})`;
      screenshotLoading.value = false;
    },
  );

  socket.on('screenshot.error', (payload: { deviceId: string; reason: string }) => {
    if (payload.deviceId !== selectedDeviceId.value) {
      return;
    }

    screenshotHint.value = `Screenshot failed: ${payload.reason}`;
    screenshotLoading.value = false;
  });
}

function renderHistoryChart() {
  if (!chartRef.value || !historyStats.value) {
    return;
  }

  if (!chart) {
    chart = echarts.init(chartRef.value);
  }

  const points = historyStats.value.points;
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
    },
    legend: {
      data: ['Usage (h)', 'Notifications', 'Unlock'],
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
        name: 'Usage (h)',
      },
      {
        type: 'value',
        name: 'Count',
      },
    ],
    series: [
      {
        name: 'Usage (h)',
        type: 'line',
        smooth: true,
        data: points.map((p) => Number((p.totalUsageMs / 3600000).toFixed(2))),
        lineStyle: { color: '#007f73', width: 3 },
      },
      {
        name: 'Notifications',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: points.map((p) => p.totalNotificationCount),
        lineStyle: { color: '#f18f01', width: 2 },
      },
      {
        name: 'Unlock',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: points.map((p) => p.unlockCount),
        lineStyle: { color: '#c73e1d', width: 2 },
      },
    ],
  });
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

  return maybe.response?.data?.message ?? maybe.message ?? 'Unknown error';
}

watch(
  () => historyStats.value,
  async () => {
    await nextTick();
    renderHistoryChart();
  },
);

onMounted(async () => {
  await refreshDevices();
  bindSocket();

  ticker = window.setInterval(() => {
    nowTick.value = Date.now();
  }, 1000);

  window.addEventListener('resize', renderHistoryChart);
});

onUnmounted(() => {
  if (ticker) {
    window.clearInterval(ticker);
  }

  window.removeEventListener('resize', renderHistoryChart);
  socket?.disconnect();
  chart?.dispose();
  chart = null;
});
</script>


