"use client";

import { useState } from "react";
import { api, ApiRequestError } from "@/lib/api";

const JOB_TYPES = ["http-callback", "email-simulation", "report-generation"] as const;

const PAYLOAD_PLACEHOLDERS: Record<(typeof JOB_TYPES)[number], string> = {
  "http-callback": '{\n  "url": "https://example.com/webhook",\n  "body": {}\n}',
  "email-simulation": '{\n  "sleepMillis": 200,\n  "failureProbability": 0\n}',
  "report-generation": '{\n  "durationMillis": 3000\n}',
};

export function SubmitJobForm({ onSubmitted }: { onSubmitted: () => void }) {
  const [jobType, setJobType] = useState<(typeof JOB_TYPES)[number]>("email-simulation");
  const [payloadText, setPayloadText] = useState(PAYLOAD_PLACEHOLDERS["email-simulation"]);
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const [priority, setPriority] = useState("0");
  const [maxAttempts, setMaxAttempts] = useState("5");
  const [dependsOnText, setDependsOnText] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);

  function handleJobTypeChange(next: (typeof JOB_TYPES)[number]) {
    setJobType(next);
    setPayloadText(PAYLOAD_PLACEHOLDERS[next]);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setResult(null);
    try {
      const payload = JSON.parse(payloadText);
      const dependsOn = dependsOnText
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean)
        .map(Number);

      const job = await api.submitJob({
        jobType,
        payload,
        idempotencyKey: idempotencyKey || crypto.randomUUID(),
        priority: Number(priority),
        maxAttempts: Number(maxAttempts),
        dependsOn: dependsOn.length > 0 ? dependsOn : undefined,
      });
      setResult({ ok: true, message: `Submitted job #${job.id} (${job.state})` });
      setIdempotencyKey("");
      onSubmitted();
    } catch (err) {
      if (err instanceof SyntaxError) {
        setResult({ ok: false, message: "Payload is not valid JSON" });
      } else if (err instanceof ApiRequestError) {
        setResult({ ok: false, message: err.message });
      } else {
        setResult({ ok: false, message: String(err) });
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="max-w-xl space-y-4">
      <div>
        <label className="block text-sm font-medium text-neutral-300">Job type</label>
        <select
          value={jobType}
          onChange={(e) => handleJobTypeChange(e.target.value as (typeof JOB_TYPES)[number])}
          className="mt-1 w-full rounded border border-neutral-700 bg-neutral-900 px-3 py-2 text-sm text-neutral-100"
        >
          {JOB_TYPES.map((type) => (
            <option key={type} value={type}>
              {type}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="block text-sm font-medium text-neutral-300">Payload (JSON)</label>
        <textarea
          value={payloadText}
          onChange={(e) => setPayloadText(e.target.value)}
          rows={6}
          className="mt-1 w-full rounded border border-neutral-700 bg-neutral-900 px-3 py-2 font-mono text-sm text-neutral-100"
        />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-neutral-300">Priority</label>
          <input
            type="number"
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
            className="mt-1 w-full rounded border border-neutral-700 bg-neutral-900 px-3 py-2 text-sm text-neutral-100"
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-neutral-300">Max attempts</label>
          <input
            type="number"
            min={1}
            value={maxAttempts}
            onChange={(e) => setMaxAttempts(e.target.value)}
            className="mt-1 w-full rounded border border-neutral-700 bg-neutral-900 px-3 py-2 text-sm text-neutral-100"
          />
        </div>
      </div>

      <div>
        <label className="block text-sm font-medium text-neutral-300">Idempotency key</label>
        <input
          type="text"
          value={idempotencyKey}
          onChange={(e) => setIdempotencyKey(e.target.value)}
          placeholder="leave blank to generate one"
          className="mt-1 w-full rounded border border-neutral-700 bg-neutral-900 px-3 py-2 text-sm text-neutral-100"
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-neutral-300">Depends on (job IDs, comma-separated)</label>
        <input
          type="text"
          value={dependsOnText}
          onChange={(e) => setDependsOnText(e.target.value)}
          placeholder="e.g. 12, 13"
          className="mt-1 w-full rounded border border-neutral-700 bg-neutral-900 px-3 py-2 text-sm text-neutral-100"
        />
      </div>

      <button
        type="submit"
        disabled={submitting}
        className="rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500 disabled:opacity-50"
      >
        {submitting ? "Submitting..." : "Submit job"}
      </button>

      {result && (
        <p className={`text-sm ${result.ok ? "text-green-400" : "text-red-400"}`}>{result.message}</p>
      )}
    </form>
  );
}
