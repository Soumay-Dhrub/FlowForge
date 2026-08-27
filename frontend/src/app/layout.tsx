import type { Metadata } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";
import { AuthProvider } from "@/context/AuthContext";
import QueryProvider from "@/components/providers/QueryProvider";
import "./globals.css";

const inter = Inter({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-inter",
});

/** For ids, JSON payloads and audit diffs — places where character alignment carries meaning. */
const mono = JetBrains_Mono({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-mono",
});

export const metadata: Metadata = {
  title: {
    default: "FlowForge",
    // Pages set their own titles; this keeps the product name in the tab without each page repeating it.
    template: "%s · FlowForge",
  },
  description: "Configurable workflow orchestration platform",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={`${inter.variable} ${mono.variable}`}>
      <body>
        {/*
          A skip link, first in the tab order and visible only when focused. Every authenticated page
          puts a sidebar and a header ahead of its content, which is a lot of tab stops to pass through
          on every navigation for anyone not using a mouse.
        */}
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-primary-700 focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:text-white"
        >
          Skip to content
        </a>

        {/* Query cache outside the session provider: a logout swaps the user, and the cache must
            already exist for the shell's queries to be cancelled and refetched cleanly. */}
        <QueryProvider>
          <AuthProvider>{children}</AuthProvider>
        </QueryProvider>
      </body>
    </html>
  );
}
