"use client";

import React, { createContext, useContext, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { getToken, refreshSession, setSignOutHandler, logout } from "@/lib/api";
import { Loader2 } from "lucide-react";

interface AuthContextType {
  token: string | null;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType>({
  token: null,
  signOut: async () => {},
});

export function useAuth() {
  return useContext(AuthContext);
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [loading, setLoading] = useState(true);
  const [activeToken, setActiveToken] = useState<string | null>(null);

  useEffect(() => {
    // Configure global sign-out redirection
    setSignOutHandler(() => {
      setActiveToken(null);
      router.replace("/login");
    });

    const initAuth = async () => {
      const existingToken = getToken();
      if (existingToken) {
        setActiveToken(existingToken);
        setLoading(false);
        return;
      }

      try {
        const token = await refreshSession();
        setActiveToken(token);
        setLoading(false);
      } catch {
        router.replace("/login");
      }
    };

    initAuth();
  }, [router]);

  const handleSignOut = async () => {
    await logout();
  };

  if (loading) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-[#F6F7F9]">
        <div className="text-center space-y-3">
          <Loader2 className="h-8 w-8 text-[#2F4BC0] animate-spin mx-auto" />
          <p className="text-sm font-medium text-[#1B1F27]">Loading FollowWise...</p>
        </div>
      </div>
    );
  }

  return (
    <AuthContext.Provider value={{ token: activeToken, signOut: handleSignOut }}>
      {children}
    </AuthContext.Provider>
  );
}
