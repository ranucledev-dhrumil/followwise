# FollowWise Frontend

Next.js 16 (App Router) + TypeScript + Tailwind CSS v4 + shadcn/ui.

## Prerequisites
- Node.js >= 22 (tested with Node 22/26)
- npm >= 10
- Running backend (`backend/`) on `http://localhost:8080`

## Local Setup

1. Copy `.env.example` to `.env.local`:
   ```bash
   cp .env.example .env.local
   ```
2. Configure `.env.local`:
   ```env
   BACKEND_API_URL=http://localhost:8080
   NEXT_PUBLIC_APP_NAME=FollowWise
   ```
3. Backend configuration (in repo root `.env` or environment):
   ```env
   APP_BASE_URL=http://localhost:3000
   MAIL_TRANSPORT=log
   SCHEDULER_ENABLED=true
   ```
   *Note:* Chrome and Firefox accept Secure cookies on `http://localhost`. For Safari set `AUTH_COOKIE_SECURE=false` locally only.

4. Start backend and worker (local profile). Verification and reset email links appear in the backend log within about 60 seconds.

5. Install dependencies and start development server:
   ```bash
   npm install
   npm run dev
   ```
   Access at `http://localhost:3000`.

## Scripts
- `npm run dev`: Start Next.js development server
- `npm run build`: Production build
- `npm start`: Start Next.js production server
- `npm run lint`: Run ESLint checks
- `npm run typecheck`: Run TypeScript compiler checks (`tsc --noEmit`)
- `npm test`: Run Vitest unit tests
