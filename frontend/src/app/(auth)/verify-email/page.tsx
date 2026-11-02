"use client";

import React, { Suspense, useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { CheckCircle2, AlertCircle, Loader2 } from "lucide-react";

function VerifyEmailContent() {
  const searchParams = useSearchParams();
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const calledRef = useRef(false);

  useEffect(() => {
    if (calledRef.current) return;
    calledRef.current = true;

    // Remove token query string immediately before network request
    if (typeof window !== "undefined") {
      window.history.replaceState(null, "", window.location.pathname);
    }

    const verify = async () => {
      const token = searchParams.get("token");
      if (!token) {
        setStatus("error");
        setErrorMsg("No verification token was provided.");
        return;
      }

      try {
        const res = await fetch("/api/auth/verify-email", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ token }),
        });

        if (!res.ok) {
          let detail = "Email verification failed or link has expired.";
          try {
            const errData = await res.json();
            if (errData?.detail) detail = errData.detail;
          } catch {
            // ignore
          }
          setStatus("error");
          setErrorMsg(detail);
          return;
        }

        setStatus("success");
      } catch {
        setStatus("error");
        setErrorMsg("Unable to connect to server. Please try again later.");
      }
    };

    verify();
  }, [searchParams]);

  return (
    <div className="text-center">
      <h1 className="text-xl font-semibold text-[#1B1F27] mb-6">
        Email verification
      </h1>

      {status === "loading" && (
        <div className="flex flex-col items-center justify-center py-6">
          <Loader2 className="h-8 w-8 text-[#2F4BC0] animate-spin mb-3" />
          <p className="text-sm text-[#5B6472]">Verifying your email address...</p>
        </div>
      )}

      {status === "success" && (
        <div className="space-y-4">
          <div className="flex items-center justify-center text-[#1E7A46] mb-2">
            <CheckCircle2 className="h-10 w-10 mr-2" />
            <span className="text-lg font-medium">Email verified successfully!</span>
          </div>
          <p className="text-sm text-[#5B6472]">
            Your email has been confirmed. You now have full access to FollowWise.
          </p>
          <div className="pt-4">
            <Link
              href="/app"
              className="inline-flex items-center justify-center min-h-[44px] h-11 px-6 rounded-md bg-[#2F4BC0] text-white hover:bg-[#253ca0] font-medium"
            >
              Continue to Dashboard
            </Link>
          </div>
        </div>
      )}

      {status === "error" && (
        <div className="space-y-4">
          <Alert variant="destructive">
            <AlertCircle className="h-4 w-4" />
            <AlertDescription>{errorMsg}</AlertDescription>
          </Alert>
          <p className="text-sm text-[#5B6472]">
            If your link has expired, you can request a new verification email from your dashboard.
          </p>
          <div className="pt-2">
            <Link
              href="/login"
              className="text-[#2F4BC0] hover:underline font-medium text-sm"
            >
              Return to Sign in
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}

export default function VerifyEmailPage() {
  return (
    <Suspense
      fallback={
        <div className="text-center py-6 text-sm text-[#5B6472]">
          Loading verification...
        </div>
      }
    >
      <VerifyEmailContent />
    </Suspense>
  );
}
