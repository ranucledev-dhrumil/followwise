import Link from "next/link";

export default function Home() {
  return (
    <div className="min-h-screen bg-[#F6F7F9] flex flex-col justify-center items-center px-4">
      <main className="max-w-md w-full bg-white border border-[#E2E5EA] rounded-lg p-8 shadow-sm text-center space-y-6">
        <div>
          <h1 className="text-3xl font-semibold text-[#2F4BC0] tracking-tight">
            FollowWise
          </h1>
          <p className="text-sm text-[#5B6472] mt-2">
            Small business management built from fixed, tested blocks.
          </p>
        </div>

        <div className="space-y-3 pt-2">
          <Link
            href="/login"
            className="w-full inline-flex items-center justify-center min-h-[44px] h-11 px-4 rounded-md bg-[#2F4BC0] text-white hover:bg-[#253ca0] font-medium"
          >
            Sign in
          </Link>
          <Link
            href="/register"
            className="w-full inline-flex items-center justify-center min-h-[44px] h-11 px-4 rounded-md border border-[#E2E5EA] bg-white text-[#1B1F27] hover:bg-[#F6F7F9] font-medium"
          >
            Create an account
          </Link>
        </div>
      </main>
    </div>
  );
}
