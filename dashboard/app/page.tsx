"use client";

import { useState } from "react";
import { SubmitJobForm } from "@/components/SubmitJobForm";
import { JobsView } from "@/components/JobsView";
import { WorkersView } from "@/components/WorkersView";
import { MetricsView } from "@/components/MetricsView";
import { DagView } from "@/components/DagView";

const TABS = ["Submit", "Jobs", "Workers", "Metrics", "Dependency graph"] as const;
type Tab = (typeof TABS)[number];

export default function DashboardPage() {
  const [tab, setTab] = useState<Tab>("Jobs");
  const [refreshToken, setRefreshToken] = useState(0);

  return (
    <div className="mx-auto max-w-5xl px-6 py-10">
      <header className="mb-8">
        <h1 className="text-xl font-semibold text-neutral-100">Distributed Job Scheduler</h1>
        <p className="text-sm text-neutral-500">
          Submit jobs, watch them claimed and executed, and inspect worker health -- all read directly from scheduler-service.
        </p>
      </header>

      <nav className="mb-6 flex gap-1 border-b border-neutral-800">
        {TABS.map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`border-b-2 px-4 py-2 text-sm ${
              tab === t
                ? "border-blue-500 text-neutral-100"
                : "border-transparent text-neutral-500 hover:text-neutral-300"
            }`}
          >
            {t}
          </button>
        ))}
      </nav>

      <main>
        {tab === "Submit" && <SubmitJobForm onSubmitted={() => setRefreshToken((n) => n + 1)} />}
        {tab === "Jobs" && <JobsView refreshToken={refreshToken} />}
        {tab === "Workers" && <WorkersView />}
        {tab === "Metrics" && <MetricsView />}
        {tab === "Dependency graph" && <DagView />}
      </main>
    </div>
  );
}
