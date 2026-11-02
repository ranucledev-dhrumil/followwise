import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  apiFetch,
  refreshSession,
  setToken,
  getToken,
  clearToken,
  setSignOutHandler,
  _setRetryDelays,
} from "./api";

describe("api client", () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    clearToken();
    _setRetryDelays([0, 0, 0]); // Zero delay for tests
    vi.restoreAllMocks();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    _setRetryDelays([2000, 4000, 8000]);
  });

  it("three concurrent 401s cause exactly one refresh request", async () => {
    let refreshCalls = 0;
    let endpointCalls = 0;

    global.fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();

      if (url.includes("/api/auth/refresh")) {
        refreshCalls++;
        return new Response(JSON.stringify({ accessToken: "new-token-123" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }

      if (url.includes("/api/workspaces")) {
        endpointCalls++;
        // First 3 calls (before refresh) return 401, subsequent retries return 200
        if (refreshCalls === 0) {
          return new Response("Unauthorized", { status: 401 });
        }
        return new Response(JSON.stringify([{ id: "ws-1", name: "Workspace 1" }]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }

      return new Response("Not found", { status: 404 });
    });

    const [res1, res2, res3] = await Promise.all([
      apiFetch("/api/workspaces"),
      apiFetch("/api/workspaces"),
      apiFetch("/api/workspaces"),
    ]);

    expect(refreshCalls).toBe(1);
    expect(endpointCalls).toBe(6); // 3 initial failures + 3 retries
    expect(res1.status).toBe(200);
    expect(res2.status).toBe(200);
    expect(res3.status).toBe(200);
    expect(getToken()).toBe("new-token-123");
  });

  it("failed refresh signs out once", async () => {
    const signOut = vi.fn();
    setSignOutHandler(signOut);

    global.fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();

      if (url.includes("/api/auth/refresh")) {
        return new Response(JSON.stringify({ detail: "Session expired" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        });
      }

      return new Response("Unauthorized", { status: 401 });
    });

    const res = await apiFetch("/api/workspaces");
    expect(res.status).toBe(401);
    expect(signOut).toHaveBeenCalledTimes(1);
    expect(getToken()).toBeNull();
  });

  it("success retries the original request once", async () => {
    let workspaceRequests = 0;

    global.fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();

      if (url.includes("/api/auth/refresh")) {
        return new Response(JSON.stringify({ accessToken: "fresh-jwt" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }

      if (url.includes("/api/workspaces")) {
        workspaceRequests++;
        const authHeader = new Headers(init?.headers).get("Authorization");
        if (workspaceRequests === 1) {
          return new Response("Unauthorized", { status: 401 });
        }
        expect(authHeader).toBe("Bearer fresh-jwt");
        return new Response(JSON.stringify({ success: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }

      return new Response("Not found", { status: 404 });
    });

    const res = await apiFetch("/api/workspaces");
    expect(res.status).toBe(200);
    expect(workspaceRequests).toBe(2);
  });

  it("/api/auth/* 401 does not trigger refresh", async () => {
    let refreshCalls = 0;

    global.fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();

      if (url.includes("/api/auth/refresh")) {
        refreshCalls++;
        return new Response(JSON.stringify({ accessToken: "should-not-reach" }), { status: 200 });
      }

      if (url.includes("/api/auth/login")) {
        return new Response(JSON.stringify({ detail: "Bad credentials" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        });
      }

      return new Response("OK", { status: 200 });
    });

    const res = await apiFetch("/api/auth/login", { method: "POST" });
    expect(res.status).toBe(401);
    expect(refreshCalls).toBe(0);
  });

  it("GET retries on 503 but POST does not", async () => {
    let getAttempts = 0;
    let postAttempts = 0;

    global.fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const method = (init?.method || "GET").toUpperCase();

      if (method === "GET") {
        getAttempts++;
        return new Response("Service Unavailable", { status: 503 });
      } else {
        postAttempts++;
        return new Response("Service Unavailable", { status: 503 });
      }
    });

    // GET should try 1 initial + 3 retries = 4 times
    const getRes = await apiFetch("/api/workspaces", { method: "GET" });
    expect(getRes.status).toBe(503);
    expect(getAttempts).toBe(4);

    // POST should try only 1 time
    const postRes = await apiFetch("/api/auth/resend-verification", { method: "POST" });
    expect(postRes.status).toBe(503);
    expect(postAttempts).toBe(1);
  });

  it("token is not written to any storage (spy on localStorage and sessionStorage)", async () => {
    const localSpy = vi.spyOn(Storage.prototype, "setItem");
    const sessionSpy = vi.spyOn(sessionStorage, "setItem");

    global.fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();

      if (url.includes("/api/auth/refresh")) {
        return new Response(JSON.stringify({ accessToken: "secret-token-123" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      return new Response("OK", { status: 200 });
    });

    setToken("my-initial-token");
    expect(getToken()).toBe("my-initial-token");

    await refreshSession();
    expect(getToken()).toBe("secret-token-123");

    await apiFetch("/api/workspaces");

    clearToken();
    expect(getToken()).toBeNull();

    expect(localSpy).not.toHaveBeenCalled();
    expect(sessionSpy).not.toHaveBeenCalled();
  });
});
