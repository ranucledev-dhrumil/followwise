"use client";

import React, { useEffect, useState, useCallback, useRef } from "react";
import { apiFetch } from "@/lib/api";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  AlertTriangle,
  CheckCircle2,
  Building2,
  Shield,
  Loader2,
  RefreshCw,
} from "lucide-react";

interface WorkspaceDto {
  id: string;
  name: string;
  role?: string;
}

interface UserProfileDto {
  id: string;
  email: string;
  emailVerified: boolean;
  workspaces: Array<{
    workspaceId: string;
    name: string;
    role: string;
  }>;
}

export default function AppPage() {
  const [user, setUser] = useState<UserProfileDto | null>(null);
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [wakingUp, setWakingUp] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);

  // Email verification resend state
  const [resending, setResending] = useState(false);
  const [resendSuccess, setResendSuccess] = useState(false);
  const [resendError, setResendError] = useState<string | null>(null);
  const [retryAfterSeconds, setRetryAfterSeconds] = useState<number | null>(null);

  const countdownTimerRef = useRef<NodeJS.Timeout | null>(null);

  const fetchData = useCallback(async () => {
    const wakingUpTimer = setTimeout(() => {
      setWakingUp(true);
    }, 3000);

    try {
      const [meRes, wsRes] = await Promise.all([
        apiFetch("/api/me"),
        apiFetch("/api/workspaces"),
      ]);

      clearTimeout(wakingUpTimer);
      setWakingUp(false);

      if (!meRes.ok) {
        throw new Error(`Failed to load profile (${meRes.status})`);
      }
      if (!wsRes.ok) {
        throw new Error(`Failed to load workspaces (${wsRes.status})`);
      }

      const meData = (await meRes.json()) as UserProfileDto;
      const wsData = (await wsRes.json()) as WorkspaceDto[];

      setUser(meData);
      setWorkspaces(wsData);
      setFetchError(null);
    } catch (err) {
      clearTimeout(wakingUpTimer);
      setWakingUp(false);
      setFetchError(
        err instanceof Error
          ? err.message
          : "The server is waking up. Please try again."
      );
    } finally {
      setLoading(false);
    }
  }, []);

  const handleRetry = () => {
    setLoading(true);
    setFetchError(null);
    fetchData();
  };

  useEffect(() => {
    let ignore = false;
    async function startFetching() {
      if (!ignore) {
        await fetchData();
      }
    }
    startFetching();
    return () => {
      ignore = true;
    };
  }, [fetchData]);

  // Handle countdown timer for 429 Retry-After
  useEffect(() => {
    if (retryAfterSeconds !== null && retryAfterSeconds > 0) {
      countdownTimerRef.current = setTimeout(() => {
        setRetryAfterSeconds((prev) => (prev !== null && prev > 1 ? prev - 1 : null));
      }, 1000);
    }
    return () => {
      if (countdownTimerRef.current) {
        clearTimeout(countdownTimerRef.current);
      }
    };
  }, [retryAfterSeconds]);

  const handleResendVerification = async () => {
    setResending(true);
    setResendSuccess(false);
    setResendError(null);

    try {
      const res = await apiFetch("/api/auth/resend-verification", {
        method: "POST",
      });

      if (res.status === 204) {
        setResendSuccess(true);
        return;
      }

      if (res.status === 429) {
        const retryHeader = res.headers.get("Retry-After");
        const seconds = retryHeader ? parseInt(retryHeader, 10) : 60;
        setRetryAfterSeconds(isNaN(seconds) ? 60 : seconds);
        setResendError(
          `Too many resend attempts. Please wait ${isNaN(seconds) ? 60 : seconds} seconds before trying again.`
        );
        return;
      }

      let detail = "Failed to resend verification email.";
      try {
        const errData = await res.json();
        if (errData?.detail) detail = errData.detail;
      } catch {
        // ignore
      }
      setResendError(detail);
    } catch {
      setResendError("The server is waking up, please try again.");
    } finally {
      setResending(false);
    }
  };

  if (loading) {
    return (
      <div className="space-y-6">
        {wakingUp && (
          <Alert className="bg-amber-50 border-amber-200">
            <AlertTriangle className="h-4 w-4 text-amber-700" />
            <AlertTitle className="text-amber-900 font-medium">
              Waking up, one moment
            </AlertTitle>
            <AlertDescription className="text-amber-800 text-sm">
              The application server is starting up. Your data will appear shortly.
            </AlertDescription>
          </Alert>
        )}
        <div className="flex flex-col items-center justify-center py-16 space-y-3">
          <Loader2 className="h-8 w-8 text-[#2F4BC0] animate-spin" />
          <p className="text-sm text-[#5B6472]">Loading dashboard...</p>
        </div>
      </div>
    );
  }

  if (fetchError) {
    return (
      <div className="space-y-6">
        <Alert variant="destructive">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>Unable to load dashboard</AlertTitle>
          <AlertDescription>{fetchError}</AlertDescription>
        </Alert>
        <Button
          onClick={handleRetry}
          className="min-h-[44px] h-11 bg-[#2F4BC0] hover:bg-[#253ca0] text-white"
        >
          <RefreshCw className="h-4 w-4 mr-2" />
          Try again
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Email Verification Banner */}
      {user && !user.emailVerified && (
        <Alert className="bg-amber-50 border-amber-200">
          <AlertTriangle className="h-5 w-5 text-amber-700 shrink-0" />
          <div className="flex-1">
            <AlertTitle className="text-amber-900 font-semibold text-base mb-1">
              Verify your email address
            </AlertTitle>
            <AlertDescription className="text-amber-800 text-sm">
              We sent a verification link to{" "}
              <strong className="font-semibold">{user.email}</strong>. Please check your inbox
              and click the link to confirm your account.
            </AlertDescription>

            {resendSuccess && (
              <div className="flex items-center text-[#1E7A46] text-sm mt-2 font-medium">
                <CheckCircle2 className="h-4 w-4 mr-1.5 shrink-0" />
                A new verification email has been sent. Check your inbox!
              </div>
            )}

            {resendError && (
              <p className="text-[#B42318] text-sm mt-2 font-medium">
                {resendError}
              </p>
            )}

            <div className="mt-3">
              <Button
                variant="outline"
                size="sm"
                onClick={handleResendVerification}
                disabled={resending || retryAfterSeconds !== null}
                className="min-h-[44px] h-11 px-4 bg-white border-amber-300 text-amber-900 hover:bg-amber-100"
              >
                {resending ? (
                  <>
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                    Sending...
                  </>
                ) : retryAfterSeconds !== null ? (
                  `Resend in ${retryAfterSeconds}s`
                ) : (
                  "Resend verification email"
                )}
              </Button>
            </div>
          </div>
        </Alert>
      )}

      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-[#1B1F27] tracking-tight">
          Workspaces
        </h1>
        <p className="text-sm text-[#5B6472] mt-1">
          Select or view your active business workspaces.
        </p>
      </div>

      {/* Workspaces List */}
      {workspaces.length === 0 ? (
        <div className="bg-white rounded-lg border border-[#E2E5EA] p-8 text-center space-y-3">
          <Building2 className="h-10 w-10 text-[#5B6472] mx-auto opacity-50" />
          <h2 className="text-base font-medium text-[#1B1F27]">No workspaces found</h2>
          <p className="text-sm text-[#5B6472] max-w-sm mx-auto">
            You are not currently a member of any workspace.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {workspaces.map((ws) => (
            <div
              key={ws.id}
              className="bg-white rounded-lg border border-[#E2E5EA] p-5 shadow-xs hover:border-[#2F4BC0]/40 transition-colors"
            >
              <div className="flex items-start justify-between">
                <div className="flex items-center space-x-3">
                  <div className="h-10 w-10 rounded-md bg-[#F6F7F9] border border-[#E2E5EA] flex items-center justify-center text-[#2F4BC0]">
                    <Building2 className="h-5 w-5" />
                  </div>
                  <div>
                    <h3 className="font-semibold text-base text-[#1B1F27]">{ws.name}</h3>
                    <p className="text-xs text-[#5B6472] font-mono mt-0.5">ID: {ws.id}</p>
                  </div>
                </div>
                {ws.role && (
                  <Badge
                    variant="outline"
                    className="flex items-center gap-1 text-xs border-[#E2E5EA] bg-[#F6F7F9] text-[#1B1F27]"
                  >
                    <Shield className="h-3 w-3 text-[#2F4BC0]" />
                    <span>{ws.role}</span>
                  </Badge>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
