// FollowWise API client
// Client-side only ('use client' or imported by client components)

let inMemoryToken: string | null = null;
let refreshPromise: Promise<string> | null = null;
let signOutHandler: (() => void) | null = null;

// Default retry backoff delays in ms: 2s, 4s, 8s (max 3 retries)
let retryDelays = [2000, 4000, 8000];

export function _setRetryDelays(delays: number[]): void {
  retryDelays = delays;
}

export function setSignOutHandler(handler: () => void): void {
  signOutHandler = handler;
}

export function triggerSignOut(): void {
  inMemoryToken = null;
  if (signOutHandler) {
    signOutHandler();
  }
}

export function setToken(token: string | null): void {
  inMemoryToken = token;
}

export function getToken(): string | null {
  return inMemoryToken;
}

export function clearToken(): void {
  inMemoryToken = null;
}

export interface ApiProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: Record<string, string>;
}

export class ApiError extends Error {
  status: number;
  detail?: string;
  data?: unknown;

  constructor(status: number, message: string, detail?: string, data?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail;
    this.data = data;
  }
}

async function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isRetriableStatus(status: number): boolean {
  return status === 502 || status === 503 || status === 504;
}

/**
 * Refresh session with single-flight promise.
 * Auto-retries on network error or 502/503/504 (max 3 attempts).
 */
export async function refreshSession(): Promise<string> {
  if (refreshPromise) {
    return refreshPromise;
  }

  refreshPromise = (async () => {
    let lastError: unknown = null;

    for (let attempt = 0; attempt <= retryDelays.length; attempt++) {
      try {
        const response = await fetch("/api/auth/refresh", {
          method: "POST",
          headers: {
            "X-FollowWise-Client": "web",
          },
          credentials: "same-origin",
        });

        if (response.ok) {
          const data = await response.json();
          const newToken = data.accessToken;
          setToken(newToken);
          return newToken;
        }

        if (isRetriableStatus(response.status) && attempt < retryDelays.length) {
          await sleep(retryDelays[attempt]);
          continue;
        }

        // Non-retriable failure (e.g. 401 refresh token expired/missing)
        clearToken();
        triggerSignOut();
        let detail: string | undefined;
        try {
          const errData = await response.json();
          detail = errData?.detail;
        } catch {
          // ignore json parse error
        }
        throw new ApiError(
          response.status,
          `Refresh failed with status ${response.status}`,
          detail
        );
      } catch (err: unknown) {
        if (err instanceof ApiError) {
          throw err;
        }
        lastError = err;
        if (attempt < retryDelays.length) {
          await sleep(retryDelays[attempt]);
          continue;
        }
      }
    }

    clearToken();
    triggerSignOut();
    throw lastError || new ApiError(0, "Refresh failed after retries");
  })().finally(() => {
    refreshPromise = null;
  });

  return refreshPromise;
}

/**
 * Log out and clear token.
 */
export async function logout(): Promise<void> {
  try {
    await fetch("/api/auth/logout", {
      method: "POST",
      headers: {
        "X-FollowWise-Client": "web",
      },
      credentials: "same-origin",
    });
  } finally {
    clearToken();
    triggerSignOut();
  }
}

/**
 * Main apiFetch wrapper.
 * - Adds Authorization Bearer token if present
 * - Adds X-FollowWise-Client: web for refresh/logout
 * - Auto-retries GET requests on network error or 502/503/504 (up to 3 times)
 * - Never auto-retries POST requests
 * - On 401 for non-auth endpoints: refreshes token once and retries once; signs out on second 401
 */
export async function apiFetch(
  path: string,
  init?: RequestInit
): Promise<Response> {
  const method = (init?.method || "GET").toUpperCase();
  const isAuthEndpoint =
    path.startsWith("/api/auth/") || path.startsWith("api/auth/");
  const isGet = method === "GET";

  const executeFetch = async (tokenOverride?: string | null): Promise<Response> => {
    const headers = new Headers(init?.headers || {});

    const activeToken =
      tokenOverride !== undefined ? tokenOverride : getToken();
    if (activeToken && !headers.has("Authorization")) {
      headers.set("Authorization", `Bearer ${activeToken}`);
    }

    if (
      path.includes("/api/auth/refresh") ||
      path.includes("/api/auth/logout")
    ) {
      if (!headers.has("X-FollowWise-Client")) {
        headers.set("X-FollowWise-Client", "web");
      }
    }

    const maxAttempts = isGet ? retryDelays.length : 0;
    let lastError: unknown = null;

    for (let attempt = 0; attempt <= maxAttempts; attempt++) {
      try {
        const response = await fetch(path, {
          ...init,
          headers,
          credentials: init?.credentials || "same-origin",
        });

        if (isGet && isRetriableStatus(response.status) && attempt < maxAttempts) {
          await sleep(retryDelays[attempt]);
          continue;
        }

        return response;
      } catch (err) {
        lastError = err;
        if (isGet && attempt < maxAttempts) {
          await sleep(retryDelays[attempt]);
          continue;
        }
        throw err;
      }
    }

    throw lastError || new Error("Request failed after retries");
  };

  const initialResponse = await executeFetch();

  if (initialResponse.status === 401 && !isAuthEndpoint) {
    try {
      const newToken = await refreshSession();
      const retryResponse = await executeFetch(newToken);
      if (retryResponse.status === 401) {
        clearToken();
        triggerSignOut();
      }
      return retryResponse;
    } catch {
      // Refresh failed; triggerSignOut has already been called in refreshSession
      return initialResponse;
    }
  }

  return initialResponse;
}
