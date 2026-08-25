"use client";

import { useCallback, useEffect, useRef, useState } from "react";

// Spec §9: "polls /api/v1/stats and /api/v1/jobs on an interval. No WebSockets -- polling is
// honest for this use case and simpler to defend." CLAUDE.md: "No state management library.
// useState plus a polling hook is sufficient." This is that hook -- every view uses it instead
// of each hand-rolling its own setInterval + fetch + cleanup.
export function usePolling<T>(fetcher: () => Promise<T>, intervalMs: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const fetcherRef = useRef(fetcher);
  useEffect(() => {
    fetcherRef.current = fetcher;
  }, [fetcher]);

  const refresh = useCallback(async () => {
    try {
      const result = await fetcherRef.current();
      setData(result);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err : new Error(String(err)));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, intervalMs);
    return () => clearInterval(id);
  }, [refresh, intervalMs]);

  return { data, error, loading, refresh };
}
