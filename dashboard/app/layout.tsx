import type { Metadata } from "next";
import { Plus_Jakarta_Sans, JetBrains_Mono } from "next/font/google";
import "./globals.css";

// Plus Jakarta Sans reads as more deliberate/modern than the default system stack this app was
// shipping with (globals.css previously hardcoded body to Arial, silently discarding whatever
// font was configured here). JetBrains Mono replaces it for job IDs, payload JSON, and worker IDs.
const sans = Plus_Jakarta_Sans({
  variable: "--font-app-sans",
  subsets: ["latin"],
  display: "swap",
});

const mono = JetBrains_Mono({
  variable: "--font-app-mono",
  subsets: ["latin"],
  display: "swap",
});

export const metadata: Metadata = {
  title: "Distributed Job Scheduler",
  description: "Dashboard for submitting and monitoring jobs against scheduler-service.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${sans.variable} ${mono.variable} h-full antialiased dark`}>
      <body className="min-h-full flex flex-col bg-neutral-950 text-neutral-100 font-sans">{children}</body>
    </html>
  );
}
