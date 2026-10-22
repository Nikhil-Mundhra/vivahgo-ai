import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "VivahGo AI — Autonomous Wedding Planning Engine",
  description: "Next-generation agentic wedding planner powered by LangGraph, MCP, and high-concurrency transactional locking.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="min-h-screen bg-[#faf8f5] text-stone-900 antialiased flex flex-col">
        <header className="border-b border-stone-200/80 bg-white/80 backdrop-blur sticky top-0 z-50">
          <div className="max-w-7xl mx-auto px-4 h-16 flex items-center justify-between">
            <div className="flex items-center space-x-3">
              <span className="text-2xl font-bold bg-gradient-to-r from-rose-600 via-amber-600 to-rose-700 bg-clip-text text-transparent">
                VivahGo<span className="text-xs uppercase font-semibold tracking-wider text-rose-500 ml-1.5 px-2 py-0.5 rounded-full bg-rose-50 border border-rose-200">AI</span>
              </span>
              <span className="text-xs text-stone-500 border-l border-stone-200 pl-3 hidden sm:inline">
                Agentic Workspace for Subscribers
              </span>
            </div>
            <div className="flex items-center space-x-4 text-xs font-medium">
              <span className="inline-flex items-center px-2.5 py-1 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 mr-1.5 animate-pulse"></span>
                MCP Engine Online
              </span>
              <span className="px-3 py-1 rounded-full bg-amber-50 text-amber-800 border border-amber-200 font-semibold">
                Studio Plan
              </span>
            </div>
          </div>
        </header>
        <main className="flex-1 flex flex-col">{children}</main>
      </body>
    </html>
  );
}
