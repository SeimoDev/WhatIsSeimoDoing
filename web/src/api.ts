import axios from 'axios';

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
});

export const wsBaseUrl =
  import.meta.env.VITE_WS_BASE_URL ?? '/api/v1/ws';
