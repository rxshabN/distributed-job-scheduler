"use client";

import { useEffect, useState } from "react";
import { api, ApiRequestError } from "@/lib/api";

const JOB_TYPES = ["http-callback", "email-simulation", "report-generation"] as const;

const PAYLOAD_PLACEHOLDERS: Record<(typeof JOB_TYPES)[number], string> = {
  "http-callback": '{\n  "url": "https://example.com/webhook",\n  "body": {}\n}',
  "email-simulation": '{\n  "sleepMillis": 200,\n  "failureProbability": 0\n}',
  "report-generation": '{\n  "durationMillis": 3000\n}',
};

interface FieldErrors {
  payload?: string;
  priority?: string;
  maxAttempts?: string;
  dependsOn?: string;
}

function validate(fields: {
  payloadText: string;
  priority: string;
  maxAttempts: string;
  dependsOnText: string;
}): FieldErrors {
  const errors: FieldErrors = {};

  if (!fields.payloadText.trim()) {
    errors.payload = "Payload is required.";
  } else {
    try {
      JSON.parse(fields.payloadText);
    } catch {
      errors.payload = "Payload is not valid JSON.";
    }
  }

  if (fields.priority.trim() === "" || !Number.isInteger(Number(fields.priority))) {
    errors.priority = "Priority must be a whole number.";
  }

  if (
    fields.maxAttempts.trim() === "" ||
    !Number.isInteger(Number(fields.maxAttempts)) ||
    Number(fields.maxAttempts) < 1
  ) {
    errors.maxAttempts = "Max attempts must be a whole number of at least 1.";
  }

  const dependsOnTokens = fields.dependsOnText
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (dependsOnTokens.some((token) => !/^\d+$/.test(token))) {
    errors.dependsOn = "Depends on must be a comma-separated list of job IDs.";
  }

  return errors;
}

export function SubmitJobForm({ onSubmitted }: { onSubmitted: () => void }) {
  const [jobType, setJobType] = useState<(typeof JOB_TYPES)[number]>("email-simulation");
  const [payloadText, setPayloadText] = useState("");
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const [priority, setPriority] = useState("0");
  const [maxAttempts, setMaxAttempts] = useState("5");
  const [dependsOnText, setDependsOnText] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    if (!toast) return;
    const id = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(id);
  }, [toast]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const fieldErrors = validate({ payloadText, priority, maxAttempts, dependsOnText });
    setErrors(fieldErrors);
    if (Object.keys(fieldErrors).length > 0) {
      return;
    }

    setSubmitting(true);
    setResult(null);
    try {
      const payload = JSON.parse(payloadText);
      const dependsOn = dependsOnText
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean)
        .map(Number);

      const { job, created } = await api.submitJob({
        jobType,
        payload,
        idempotencyKey: idempotencyKey || crypto.randomUUID(),
        priority: Number(priority),
        maxAttempts: Number(maxAttempts),
        dependsOn: dependsOn.length > 0 ? dependsOn : undefined,
      });

      if (created) {
        setResult({ ok: true, message: `Submitted job #${job.id} (${job.state})` });
      } else {
        setToast(`A job with this idempotency key already exists — showing job #${job.id} (${job.state}).`);
        setResult({ ok: true, message: `Job #${job.id} already exists for this idempotency key.` });
      }
      setIdempotencyKey("");
      onSubmitted();
    } catch (err) {
      if (err instanceof ApiRequestError) {
        setResult({ ok: false, message: err.message });
      } else {
        setResult({ ok: false, message: String(err) });
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {toast && (
        <div className="fixed right-6 top-6 z-50 max-w-sm rounded border border-amber-700 bg-amber-950 px-4 py-3 text-sm text-amber-100 shadow-lg">
          {toast}
        </div>
      )}

      <div>
        <label className="block text-sm font-medium text-neutral-300">Job type</label>
        <select
          value={jobType}
          onChange={(e) => setJobType(e.target.value as (typeof JOB_TYPES)[number])}
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
          placeholder={PAYLOAD_PLACEHOLDERS[jobType]}
          rows={6}
          className={`mt-1 w-full rounded border bg-neutral-900 px-3 py-2 font-mono text-sm text-neutral-100 placeholder:text-neutral-600 ${
            errors.payload ? "border-red-600" : "border-neutral-700"
          }`}
        />
        {errors.payload && <p className="mt-1 text-xs text-red-400">{errors.payload}</p>}
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-neutral-300">Priority</label>
          <input
            type="number"
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
            className={`mt-1 w-full rounded border bg-neutral-900 px-3 py-2 text-sm text-neutral-100 ${
              errors.priority ? "border-red-600" : "border-neutral-700"
            }`}
          />
          {errors.priority && <p className="mt-1 text-xs text-red-400">{errors.priority}</p>}
        </div>
        <div>
          <label className="block text-sm font-medium text-neutral-300">Max attempts</label>
          <input
            type="number"
            min={1}
            value={maxAttempts}
            onChange={(e) => setMaxAttempts(e.target.value)}
            className={`mt-1 w-full rounded border bg-neutral-900 px-3 py-2 text-sm text-neutral-100 ${
              errors.maxAttempts ? "border-red-600" : "border-neutral-700"
            }`}
          />
          {errors.maxAttempts && <p className="mt-1 text-xs text-red-400">{errors.maxAttempts}</p>}
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
          className={`mt-1 w-full rounded border bg-neutral-900 px-3 py-2 text-sm text-neutral-100 ${
            errors.dependsOn ? "border-red-600" : "border-neutral-700"
          }`}
        />
        {errors.dependsOn && <p className="mt-1 text-xs text-red-400">{errors.dependsOn}</p>}
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
