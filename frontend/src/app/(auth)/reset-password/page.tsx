"use client";

import React, { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { validatePassword } from "@/lib/validation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { AlertCircle, CheckCircle2 } from "lucide-react";

function ResetPasswordContent() {
  const searchParams = useSearchParams();
  const [token] = useState<string | null>(() => searchParams.get("token"));
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    // Remove token from URL immediately, keep in component state only
    if (typeof window !== "undefined") {
      window.history.replaceState(null, "", window.location.pathname);
    }
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!token) {
      setError("Reset token is missing or has already been used.");
      return;
    }

    const passwordErr = validatePassword(password);
    if (passwordErr) {
      setError(passwordErr);
      return;
    }

    if (password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }

    setLoading(true);
    try {
      const res = await fetch("/api/auth/reset-password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          token,
          newPassword: password,
        }),
      });

      if (!res.ok) {
        let detail = "Password reset failed. The link may have expired.";
        try {
          const errData = await res.json();
          if (errData?.detail) detail = errData.detail;
          else if (errData?.title) detail = errData.title;
        } catch {
          // ignore
        }
        setError(detail);
        return;
      }

      setSuccess(true);
    } catch {
      setError("The server is waking up, please try again.");
    } finally {
      setLoading(false);
    }
  };

  if (success) {
    return (
      <div className="space-y-4 text-center">
        <div className="flex items-center justify-center text-[#1E7A46] mb-2">
          <CheckCircle2 className="h-10 w-10 mr-2" />
          <span className="text-lg font-medium">Password reset successfully</span>
        </div>
        <p className="text-sm text-[#5B6472]">
          Your password has been changed. You can now sign in with your new password.
        </p>
        <div className="pt-4">
          <Link
            href="/login"
            className="inline-flex items-center justify-center min-h-[44px] h-11 px-6 rounded-md bg-[#2F4BC0] text-white hover:bg-[#253ca0] font-medium"
          >
            Sign in
          </Link>
        </div>
      </div>
    );
  }

  if (!token) {
    return (
      <div className="space-y-4 text-center">
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertDescription>
            Invalid or missing reset token. The link may have expired or was already used.
          </AlertDescription>
        </Alert>
        <div className="pt-2">
          <Link
            href="/forgot-password"
            className="text-[#2F4BC0] hover:underline font-medium text-sm"
          >
            Request a new reset link
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div>
      <h1 className="text-xl font-semibold text-[#1B1F27] text-center mb-6">
        Create new password
      </h1>

      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertCircle className="h-4 w-4" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <Label htmlFor="password" className="block text-sm font-medium text-[#1B1F27] mb-1">
            New password <span className="text-red-500">*</span>
          </Label>
          <Input
            id="password"
            type="password"
            autoComplete="new-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="min-h-[44px] h-11"
          />
          <p className="text-xs text-[#5B6472] mt-1">
            10 to 72 characters (UTF-8 bytes)
          </p>
        </div>

        <div>
          <Label htmlFor="confirmPassword" className="block text-sm font-medium text-[#1B1F27] mb-1">
            Confirm new password <span className="text-red-500">*</span>
          </Label>
          <Input
            id="confirmPassword"
            type="password"
            autoComplete="new-password"
            required
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            className="min-h-[44px] h-11"
          />
        </div>

        <Button
          type="submit"
          disabled={loading}
          className="w-full min-h-[44px] h-11 bg-[#2F4BC0] hover:bg-[#253ca0] text-white"
        >
          {loading ? "Resetting password..." : "Reset password"}
        </Button>
      </form>
    </div>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense
      fallback={
        <div className="text-center py-6 text-sm text-[#5B6472]">
          Loading...
        </div>
      }
    >
      <ResetPasswordContent />
    </Suspense>
  );
}
