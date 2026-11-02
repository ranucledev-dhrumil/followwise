"use client";

import React, { useState } from "react";
import Link from "next/link";
import { validateEmail } from "@/lib/validation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { AlertCircle, CheckCircle2 } from "lucide-react";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const emailErr = validateEmail(email);
    if (emailErr) {
      setError(emailErr);
      return;
    }

    setLoading(true);
    try {
      await fetch("/api/auth/forgot-password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          email: email.trim().toLowerCase(),
        }),
      });

      // Always show generic success message to prevent user enumeration
      setSubmitted(true);
    } catch {
      setError("The server is waking up, please try again.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h1 className="text-xl font-semibold text-[#1B1F27] text-center mb-6">
        Reset your password
      </h1>

      {submitted ? (
        <div className="space-y-4 text-center">
          <div className="flex items-center justify-center text-[#1E7A46] mb-2">
            <CheckCircle2 className="h-10 w-10 mr-2" />
            <span className="text-lg font-medium">Check your email</span>
          </div>
          <p className="text-sm text-[#5B6472]">
            If an account exists for {email}, we have sent a password reset link. Please check your inbox and spam folder.
          </p>
          <div className="pt-4">
            <Link
              href="/login"
              className="inline-flex items-center justify-center min-h-[44px] h-11 px-6 rounded-md bg-[#2F4BC0] text-white hover:bg-[#253ca0] font-medium"
            >
              Back to Sign in
            </Link>
          </div>
        </div>
      ) : (
        <>
          <p className="text-sm text-[#5B6472] mb-6 text-center">
            Enter your email address and we will send you a link to reset your password.
          </p>

          {error && (
            <Alert variant="destructive" className="mb-4">
              <AlertCircle className="h-4 w-4" />
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <Label htmlFor="email" className="block text-sm font-medium text-[#1B1F27] mb-1">
                Email address <span className="text-red-500">*</span>
              </Label>
              <Input
                id="email"
                type="email"
                autoComplete="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="min-h-[44px] h-11"
              />
            </div>

            <Button
              type="submit"
              disabled={loading}
              className="w-full min-h-[44px] h-11 bg-[#2F4BC0] hover:bg-[#253ca0] text-white"
            >
              {loading ? "Sending link..." : "Send reset link"}
            </Button>
          </form>

          <p className="text-center text-sm text-[#5B6472] mt-6">
            Remember your password?{" "}
            <Link href="/login" className="text-[#2F4BC0] hover:underline font-medium">
              Sign in
            </Link>
          </p>
        </>
      )}
    </div>
  );
}
