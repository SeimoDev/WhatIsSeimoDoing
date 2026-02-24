import { createI18n } from 'vue-i18n';
import en from './locales/en';
import zhCN from './locales/zh-CN';

export const LOCALE_STORAGE_KEY = 'wisd_locale';
export const SUPPORTED_LOCALES = ['en', 'zh-CN'] as const;
export type AppLocale = (typeof SUPPORTED_LOCALES)[number];

function isSupportedLocale(value: string | null): value is AppLocale {
  return value === 'en' || value === 'zh-CN';
}

function resolveInitialLocale(): AppLocale {
  if (typeof window === 'undefined') {
    return 'en';
  }

  const stored = window.localStorage.getItem(LOCALE_STORAGE_KEY);
  if (isSupportedLocale(stored)) {
    return stored;
  }

  const browserLocale = window.navigator.language.toLowerCase();
  if (browserLocale.startsWith('zh')) {
    return 'zh-CN';
  }

  return 'en';
}

export const i18n = createI18n({
  legacy: false,
  locale: resolveInitialLocale(),
  fallbackLocale: 'en',
  messages: {
    en,
    'zh-CN': zhCN,
  },
});

export function setAppLocale(locale: AppLocale): void {
  i18n.global.locale.value = locale;
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, locale);
  }
}
