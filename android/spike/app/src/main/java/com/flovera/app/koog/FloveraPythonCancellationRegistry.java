package com.flovera.app.koog;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-thread cancellation flags for the bounded Chaquopy runtime.
 *
 * Python calls are serialized by Chaquopy, so cancellation must not require
 * another Python call while a run is already executing.
 */
public final class FloveraPythonCancellationRegistry {
  private static final ConcurrentHashMap<String, Boolean> CANCELLED = new ConcurrentHashMap<>();

  private FloveraPythonCancellationRegistry() {
  }

  public static void cancel(String runId) {
    if (runId != null && !runId.isEmpty()) {
      CANCELLED.put(runId, Boolean.TRUE);
    }
  }

  public static boolean isCancelled(String runId) {
    return runId != null && CANCELLED.containsKey(runId);
  }

  public static void clear(String runId) {
    if (runId != null && !runId.isEmpty()) {
      CANCELLED.remove(runId);
    }
  }
}
