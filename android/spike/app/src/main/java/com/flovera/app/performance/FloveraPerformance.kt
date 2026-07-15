package com.flovera.app.performance

import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

object FloveraPerformance {
  private const val TAG = "FloveraPerformance"
  private const val LONG_BACKGROUND_TASK_MS = 250L
  private val activeTasks = ConcurrentHashMap<String, ActiveTask>()
  private val nextTaskId = AtomicInteger()

  fun beginTask(category: String, name: String): Closeable {
    val taskId = "${category}-${nextTaskId.incrementAndGet()}"
    val task = ActiveTask(
      category = category,
      name = name,
      startedAtMillis = SystemClock.uptimeMillis(),
      threadName = Thread.currentThread().name,
    )
    activeTasks[taskId] = task
    return Closeable {
      activeTasks.remove(taskId)
      val durationMillis = SystemClock.uptimeMillis() - task.startedAtMillis
      if (durationMillis >= LONG_BACKGROUND_TASK_MS) {
        Log.i(
          TAG,
          "background task ${task.category}/${task.name} took ${durationMillis}ms on ${task.threadName}",
        )
      }
    }
  }

  suspend fun <T> trace(category: String, name: String, block: () -> T): T {
    beginTask(category, name).use {
      return block()
    }
  }

  suspend fun <T> traceQueued(category: String, name: String, queuedAtMillis: Long, block: () -> T): T {
    val queueWaitMillis = SystemClock.uptimeMillis() - queuedAtMillis
    if (queueWaitMillis >= LONG_BACKGROUND_TASK_MS) {
      Log.i(TAG, "background task $category/$name waited ${queueWaitMillis}ms in queue")
    }
    return trace(category, name, block)
  }

  fun activeTaskSummary(limit: Int = 4): String {
    val now = SystemClock.uptimeMillis()
    val tasks = activeTasks.values
      .sortedByDescending { now - it.startedAtMillis }
      .take(limit)
      .joinToString("; ") { task ->
        "${task.category}/${task.name}:${now - task.startedAtMillis}ms"
      }
    return tasks.ifBlank { "none" }
  }

  private data class ActiveTask(
    val category: String,
    val name: String,
    val startedAtMillis: Long,
    val threadName: String,
  )
}

object FloveraDispatchers {
  val previewDispatcher: CoroutineDispatcher = foregroundDispatcher(
    prefix = "flovera-preview",
    threads = 1,
  )
  val workspaceMutationDispatcher: CoroutineDispatcher = backgroundDispatcher(
    prefix = "flovera-workspace-mutation",
    threads = 1,
  )
  val workspaceQueryDispatcher: CoroutineDispatcher = backgroundDispatcher(
    prefix = "flovera-workspace-query",
    threads = 2,
  )
  val markdownDispatcher: CoroutineDispatcher = backgroundDispatcher(
    prefix = "flovera-markdown",
    threads = 1,
  )
  val runtimeDispatcher: CoroutineDispatcher = backgroundDispatcher(
    prefix = "flovera-runtime",
    threads = 1,
  )

  suspend fun <T> runWorkspaceMutation(name: String, block: () -> T): T {
    return withContext(workspaceMutationDispatcher) {
      FloveraPerformance.trace("workspace-mutation", name, block)
    }
  }

  suspend fun <T> runWorkspaceQuery(name: String, block: () -> T): T {
    return withContext(workspaceQueryDispatcher) {
      FloveraPerformance.trace("workspace-query", name, block)
    }
  }

  suspend fun <T> runMarkdown(name: String, block: () -> T): T {
    return withContext(markdownDispatcher) {
      FloveraPerformance.trace("markdown", name, block)
    }
  }

  suspend fun <T> runRuntime(name: String, block: () -> T): T {
    return withContext(runtimeDispatcher) {
      FloveraPerformance.trace("runtime", name, block)
    }
  }

  private fun backgroundDispatcher(prefix: String, threads: Int): CoroutineDispatcher {
    return executorDispatcher(prefix, threads, Process.THREAD_PRIORITY_BACKGROUND)
  }

  private fun foregroundDispatcher(prefix: String, threads: Int): CoroutineDispatcher {
    return executorDispatcher(prefix, threads, Process.THREAD_PRIORITY_DEFAULT)
  }

  private fun executorDispatcher(prefix: String, threads: Int, threadPriority: Int): CoroutineDispatcher {
    val nextThreadId = AtomicInteger()
    val factory = ThreadFactory { runnable ->
      Thread {
        Process.setThreadPriority(threadPriority)
        runnable.run()
      }.apply {
        name = "$prefix-${nextThreadId.incrementAndGet()}"
        isDaemon = true
      }
    }
    return Executors.newFixedThreadPool(threads, factory).asCoroutineDispatcher()
  }
}

class UiResponsivenessMonitor(
  private val slowFrameMillis: Long = 300L,
  private val frozenFrameMillis: Long = 1_000L,
  private val onFrameGap: (UiFrameGap) -> Unit = {},
) {
  private var started = false
  private var lastFrameAtMillis = 0L
  private var lastLogAtMillis = 0L

  private val callback = object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
      val now = SystemClock.uptimeMillis()
      val previous = lastFrameAtMillis
      if (previous > 0L) {
        val gap = now - previous
        if (gap >= slowFrameMillis && now - lastLogAtMillis >= slowFrameMillis) {
          lastLogAtMillis = now
          val severity = if (gap >= frozenFrameMillis) "frozen" else "slow"
          val activeTasks = FloveraPerformance.activeTaskSummary()
          Log.w(
            "FloveraUiWatchdog",
            "UI $severity frame gap ${gap}ms; activeTasks=$activeTasks",
          )
          onFrameGap(
            UiFrameGap(
              severity = severity,
              gapMillis = gap,
              activeTasks = activeTasks,
            ),
          )
        }
      }
      lastFrameAtMillis = now
      if (started) {
        Choreographer.getInstance().postFrameCallback(this)
      }
    }
  }

  fun start() {
    if (started) return
    started = true
    lastFrameAtMillis = 0L
    Choreographer.getInstance().postFrameCallback(callback)
  }

  fun stop() {
    if (!started) return
    started = false
    Choreographer.getInstance().removeFrameCallback(callback)
  }
}

data class UiFrameGap(
  val severity: String,
  val gapMillis: Long,
  val activeTasks: String,
)
