import React from "react";
import Link from "next/link";

export default function AuthLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen flex flex-col justify-center items-center bg-[#F6F7F9] px-4 py-8">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <Link
            href="/"
            className="text-2xl font-semibold text-[#2F4BC0] tracking-tight hover:opacity-90 inline-block"
          >
            FollowWise
          </Link>
        </div>
        <div className="bg-white rounded-lg border border-[#E2E5EA] shadow-sm p-6 sm:p-8">
          {children}
        </div>
      </div>
    </div>
  );
}
