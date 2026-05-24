import { reactive } from 'vue';
import { fetchMe, loginByCode, logoutCurrentUser } from '../api/hmdp';
import type { LoginForm, UserDTO } from '../types';

const TOKEN_KEY = 'token';

interface AuthState {
  token: string;
  user: UserDTO | null;
  initialized: boolean;
  isLoggedIn: boolean;
}

let initPromise: Promise<boolean> | null = null;

export const authState = reactive<AuthState>({
  token: sessionStorage.getItem(TOKEN_KEY) || '',
  user: null,
  initialized: false,
  isLoggedIn: Boolean(sessionStorage.getItem(TOKEN_KEY)),
});

function persistToken(token: string) {
  authState.token = token;
  authState.isLoggedIn = Boolean(token);
  if (token) {
    sessionStorage.setItem(TOKEN_KEY, token);
  } else {
    sessionStorage.removeItem(TOKEN_KEY);
  }
}

export function clearAuth() {
  initPromise = null;
  authState.user = null;
  authState.initialized = true;
  persistToken('');
}

export async function initAuth(): Promise<boolean> {
  if (authState.initialized) {
    return authState.isLoggedIn;
  }
  if (initPromise) {
    return initPromise;
  }
  initPromise = (async () => {
    const token = authState.token;
    if (!token) {
      clearAuth();
      return false;
    }
    try {
      const user = await fetchMe();
      if (authState.token !== token) {
        return authState.isLoggedIn;
      }
      authState.user = user;
      authState.isLoggedIn = true;
      authState.initialized = true;
      return true;
    } catch {
      if (authState.token === token) {
        clearAuth();
      }
      return false;
    } finally {
      initPromise = null;
    }
  })();
  return initPromise;
}

export async function login(form: LoginForm) {
  const token = await loginByCode(form);
  initPromise = null;
  persistToken(token);
  try {
    authState.user = await fetchMe();
    authState.initialized = true;
  } catch (error) {
    clearAuth();
    throw error;
  }
}

export async function logout() {
  try {
    if (authState.token) {
      await logoutCurrentUser();
    }
  } finally {
    clearAuth();
  }
}
