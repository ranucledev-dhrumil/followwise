"use client";

import React, { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { setToken } from "@/lib/api";
import { validateEmail } from "@/lib/validation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { AlertCircle } from "lucide-react";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
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
    if (!password) {
      setError("Password is required");
      return;
    }

    setLoading(true);
    try {
      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          email: email.trim().toLowerCase(),
          password,
        }),
      });

      if (!res.ok) {
        let detail = "Invalid email or password.";
        try {
          const errData = await res.json();
          if (errData?.detail) detail = errData.detail;
          else if (errData?.title) detail = errData.title;
        } catch {
          // ignore parse error
        }
        setError(detail);
        return;
      }

      const data = await res.json();
      setToken(data.accessToken);
      router.push("/app");
    } catch {
      setError("The server is waking up, please try again.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h1 className="text-xl font-semibold text-[#1B1F27] text-center mb-6">
        Sign in to FollowWise
      </h1>

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

        <div>
          <div className="flex items-center justify-between mb-1">
            <Label htmlFor="password" className="block text-sm font-medium text-[#1B1F27]">
              Password <span className="text-red-500">*</span>
            </Label>
            <Link
              href="/forgot-password"
              className="text-xs text-[#2F4BC0] hover:underline"
            >
              Forgot password?
            </Link>
          </div>
          <Input
            id="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="min-h-[44px] h-11"
          />
        </div>

        <Button
          type="submit"
          disabled={loading}
          className="w-full min-h-[44px] h-11 bg-[#2F4BC0] hover:bg-[#253ca0] text-white"
        >
          {loading ? "Signing in..." : "Sign in"}
        </Button>
      </form>

      <p className="text-center text-sm text-[#5B6472] mt-6">
        Don&apos;t have an account?{" "}
        <Link href="/register" className="text-[#2F4BC0] hover:underline font-medium">
          Sign up
        </Link>
      </p>
    </div>
  );
}
