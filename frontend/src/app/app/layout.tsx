"use client";

import React from "react";
import Link from "next/link";
import { AuthProvider, useAuth } from "./auth-provider";
import { LayoutDashboard, LogOut } from "lucide-react";
import { Button } from "@/components/ui/button";

function AppShell({ children }: { children: React.ReactNode }) {
  const { signOut } = useAuth();

  return (
    <div className="min-h-screen bg-[#F6F7F9] flex flex-col lg:flex-row">
      {/* Desktop Sidebar (lg breakpoint >= 1024px) */}
      <aside className="hidden lg:flex w-64 bg-white border-r border-[#E2E5EA] flex-col shrink-0">
        <div className="p-6 border-b border-[#E2E5EA]">
          <Link
            href="/app"
            className="text-xl font-semibold text-[#2F4BC0] tracking-tight block"
          >
            FollowWise
          </Link>
          <p className="text-xs text-[#5B6472] mt-1">Management Platform</p>
        </div>

        <nav className="flex-1 p-4 space-y-1">
          <Link
            href="/app"
            className="flex items-center gap-3 px-3 py-2.5 rounded-md text-sm font-medium bg-[#F6F7F9] text-[#2F4BC0] border border-[#E2E5EA]"
          >
            <LayoutDashboard className="h-4 w-4" />
            Dashboard
          </Link>
        </nav>

        <div className="p-4 border-t border-[#E2E5EA]">
          <Button
            variant="ghost"
            onClick={signOut}
            className="w-full justify-start text-[#5B6472] hover:text-[#B42318] hover:bg-rose-50 min-h-[44px] h-11"
          >
            <LogOut className="h-4 w-4 mr-2" />
            Sign out
          </Button>
        </div>
      </aside>

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 pb-16 lg:pb-0">
        {/* Mobile Top Header */}
        <header className="lg:hidden bg-white border-b border-[#E2E5EA] px-4 py-3 flex items-center justify-between">
          <Link
            href="/app"
            className="text-lg font-semibold text-[#2F4BC0] tracking-tight"
          >
            FollowWise
          </Link>
          <Button
            variant="ghost"
            size="sm"
            onClick={signOut}
            className="text-[#5B6472] hover:text-[#B42318] min-h-[44px] h-11"
          >
            <LogOut className="h-4 w-4 mr-1" />
            Sign out
          </Button>
        </header>

        <main className="flex-1 w-full max-w-[1120px] mx-auto p-4 sm:p-6 lg:p-8">
          {children}
        </main>
      </div>

      {/* Mobile Bottom Navigation (< 1024px) */}
      <div className="lg:hidden fixed bottom-0 left-0 right-0 bg-white border-t border-[#E2E5EA] flex justify-around items-center h-16 z-50 px-4">
        <Link
          href="/app"
          className="flex flex-col items-center justify-center flex-1 h-full text-xs font-medium text-[#2F4BC0]"
        >
          <LayoutDashboard className="h-5 w-5 mb-1" />
          Dashboard
        </Link>
        <button
          type="button"
          onClick={signOut}
          className="flex flex-col items-center justify-center flex-1 h-full text-xs font-medium text-[#5B6472] hover:text-[#B42318]"
        >
          <LogOut className="h-5 w-5 mb-1" />
          Sign out
        </button>
      </div>
    </div>
  );
}

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <AuthProvider>
      <AppShell>{children}</AppShell>
    </AuthProvider>
  );
}
