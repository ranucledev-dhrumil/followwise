"use client";

import React, { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { setToken } from "@/lib/api";
import { validateEmail, validatePassword } from "@/lib/validation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { AlertCircle } from "lucide-react";

export default function RegisterPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [workspaceName, setWorkspaceName] = useState("");
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

    const passwordErr = validatePassword(password);
    if (passwordErr) {
      setError(passwordErr);
      return;
    }

    setLoading(true);
    try {
      const res = await fetch("/api/auth/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          email: email.trim().toLowerCase(),
          password,
          workspaceName: workspaceName.trim() || undefined,
        }),
      });

      if (!res.ok) {
        let detail = "Registration failed. Please try again.";
        try {
          const errData = await res.json();
          if (errData?.detail) detail = errData.detail;
          else if (errData?.title) detail = errData.title;
        } catch {
          // ignore json parse error
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
        Create your account
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
          <Label htmlFor="password" className="block text-sm font-medium text-[#1B1F27] mb-1">
            Password <span className="text-red-500">*</span>
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
          <Label htmlFor="workspaceName" className="block text-sm font-medium text-[#1B1F27] mb-1">
            Workspace name (optional)
          </Label>
          <Input
            id="workspaceName"
            type="text"
            placeholder="My Business"
            value={workspaceName}
            onChange={(e) => setWorkspaceName(e.target.value)}
            className="min-h-[44px] h-11"
          />
        </div>

        <Button
          type="submit"
          disabled={loading}
          className="w-full min-h-[44px] h-11 bg-[#2F4BC0] hover:bg-[#253ca0] text-white"
        >
          {loading ? "Creating account..." : "Sign up"}
        </Button>
      </form>

      <p className="text-center text-sm text-[#5B6472] mt-6">
        Already have an account?{" "}
        <Link href="/login" className="text-[#2F4BC0] hover:underline font-medium">
          Sign in
        </Link>
      </p>
    </div>
  );
}
