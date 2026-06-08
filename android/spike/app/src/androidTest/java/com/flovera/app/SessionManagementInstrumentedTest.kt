package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.SettingsStore
import com.flovera.app.session.AgentRunTimelineEvent
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.ConversationTranscriptEvent
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.PromptContextLedger
import com.flovera.app.session.RuntimeSessionHistory
import com.flovera.app.session.SESSION_ROLE_COMPRESSION
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import com.flovera.app.session.ToolEvent
import com.flovera.app.session.ToolContextRetentionPolicy
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagementInstrumentedTest {
  @Test
  fun storeCanRenameDuplicateArchiveAndRestoreSessions() {
    val store = isolatedSessionStore("rename-archive").store
    val suffix = System.currentTimeMillis()
    val source = store.create("Source $suffix")
    val withMessage = store.appendMessage(source, SessionMessage(role = "user", content = "hello"))

    val copy = store.duplicate(withMessage.id)
    assertNotNull(copy)
    assertNotEquals(withMessage.id, copy?.id)
    assertEquals(withMessage.messages.size, copy?.messages?.size)

    val renamed = store.rename(withMessage.id, "Renamed $suffix")
    assertEquals("Renamed $suffix", renamed?.title)

    val other = store.appendMessage(store.create("Other $suffix"), SessionMessage(role = "user", content = "other"))
    val pinned = store.setPinned(withMessage.id, true)
    assertNotNull(pinned?.pinnedAtMillis)
    val relevant = store.list().filter { it.id == withMessage.id || it.id == other.id }
    assertEquals(withMessage.id, relevant.first().id)

    val archived = store.archive(withMessage.id)
    assertNotNull(archived?.archivedAtMillis)
    assertEquals(null, archived?.pinnedAtMillis)
    assertTrue(store.list().none { it.id == withMessage.id })
    assertTrue(store.list().any { it.id == other.id })
    assertTrue(store.listArchived().any { it.id == withMessage.id })

    val restored = store.restore(withMessage.id)
    assertEquals(null, restored?.archivedAtMillis)
    assertTrue(store.list().any { it.id == withMessage.id })
  }

  @Test
  fun storeSortsPinnedSessionsFirstThenByLatestUpdate() {
    val store = isolatedSessionStore("sort").store
    val suffix = System.currentTimeMillis()
    val olderPinned = store.appendMessage(store.create("Older pinned $suffix"), SessionMessage(role = "user", content = "old"))
    val newerPinned = store.appendMessage(store.create("Newer pinned $suffix"), SessionMessage(role = "user", content = "new"))
    val newestUnpinned = store.appendMessage(store.create("Newest unpinned $suffix"), SessionMessage(role = "user", content = "newest"))
    val ids = setOf(olderPinned.id, newerPinned.id, newestUnpinned.id)

    store.save(olderPinned.copy(updatedAtMillis = 1_000L, pinnedAtMillis = 3_000L))
    store.save(newerPinned.copy(updatedAtMillis = 2_000L, pinnedAtMillis = 1_000L))
    store.save(newestUnpinned.copy(updatedAtMillis = 3_000L, pinnedAtMillis = null))

    val orderedIds = store.list()
      .filter { it.id in ids }
      .map { it.id }

    assertEquals(listOf(newerPinned.id, olderPinned.id, newestUnpinned.id), orderedIds)
  }

  @Test
  fun sessionStoreWritesJsonWithoutLeavingAtomicTempFiles() {
    val harness = isolatedSessionStore("atomic")
    val store = harness.store
    val session = store.create("Atomic ${System.currentTimeMillis()}")
    val updated = store.appendMessage(session, SessionMessage(role = "user", content = "persist me"))
    val sessionFile = File(harness.sessionsRoot, "${session.id}.json")

    assertEquals(updated.messages, store.load(session.id)?.messages)
    assertTrue(sessionFile.isFile)
    assertFalse(File(sessionFile.absolutePath + ".new").exists())
    assertFalse(File(sessionFile.absolutePath + ".bak").exists())
  }

  @Test
  fun sessionStorePersistsContextUsageRecords() {
    val store = isolatedSessionStore("context-record").store
    val session = store.create("Context ${System.currentTimeMillis()}")

    val updated = store.appendContextRecord(
      session,
      ContextUsageRecord(
        id = "record-1",
        source = "agent_run",
        messageCount = 3,
        inputChars = 12,
        historyChars = 34,
        rulesChars = 56,
        workspaceListingChars = 78,
        approximateTokens = 45,
        compressed = false,
        summary = "No compression was applied.",
      ),
    )

    val loaded = store.load(updated.id)
    assertEquals(1, loaded?.contextRecords?.size)
    assertEquals(45, loaded?.contextRecords?.single()?.approximateTokens)
  }

  @Test
  fun sessionStoreAppendsCompressionDividerMessage() {
    val store = isolatedSessionStore("compression-divider").store
    val session = store.create("Compression ${System.currentTimeMillis()}")
    val record = ContextUsageRecord(
      id = "record-compress",
      source = "agent_run",
      provider = "deepseek",
      model = "deepseek-v4-pro",
      messageCount = 8,
      inputChars = 12,
      historyChars = 34,
      rulesChars = 56,
      workspaceListingChars = 78,
      approximateTokens = 900_000,
      contextBudgetStatus = "compression_recommended",
      compressed = true,
      summary = "handoff",
    )

    val updated = store.appendCompressionDivider(session, record, "Keep project facts and pending tasks.")
    val divider = store.load(updated.id)?.messages?.single()

    assertEquals(SESSION_ROLE_COMPRESSION, divider?.role)
    assertTrue(divider?.content?.contains("Context compressed") == true)
    assertTrue(divider?.content?.contains("record-compress") == true)
    assertTrue(divider?.content?.contains("Keep project facts") == true)
  }

  @Test
  fun sessionStoreCanGenerateLocalHandoffSummaryForCompressionDivider() {
    val store = isolatedSessionStore("handoff-summary").store
    val session = store.create("Calendar workspace")
    val one = store.appendMessage(
      session,
      SessionMessage(role = "user", content = "Create a weekly calendar HTML page."),
    )
    val two = store.appendMessage(
      one,
      SessionMessage(
        role = "assistant",
        content = "Created calendar.html and updated the preview.",
        toolEvents = listOf(
          ToolEvent(name = "write_file", args = "calendar.html", result = "wrote calendar.html"),
        ),
      ),
    )
    val record = ContextUsageRecord(
      id = "handoff-record",
      source = "agent_run",
      provider = "deepseek",
      model = "deepseek-v4-pro",
      messageCount = 2,
      inputChars = 10,
      historyChars = 20,
      rulesChars = 30,
      workspaceListingChars = 40,
      approximateTokens = 900_000,
      contextBudgetStatus = "compression_recommended",
      compressed = true,
      summary = "handoff",
    )

    val updated = store.appendCompressionDivider(two, record)
    val content = store.load(updated.id)?.messages?.last()?.content.orEmpty()

    assertTrue(content.contains("# Handoff Summary"))
    assertTrue(content.contains("Create a weekly calendar HTML page."))
    assertTrue(content.contains("Created calendar.html"))
    assertTrue(content.contains("write_file"))
    assertTrue(content.contains("handoff-record"))
  }

  @Test
  fun controllerArchivesActiveSessionAndSwitchesToUsableSession() {
    withIsolatedController("archive-active") { controller, store ->
      val first = store.appendMessage(
        store.create("Managed source ${System.currentTimeMillis()}"),
        SessionMessage(role = "user", content = "persisted"),
      )
      controller.openSession(first.id)

      controller.renameSession(first.id, "Managed ${System.currentTimeMillis()}")
      assertTrue(controller.state.value.session?.title?.startsWith("Managed ") == true)
      controller.setSessionPinned(first.id, true)
      assertNotNull(controller.state.value.session?.pinnedAtMillis)

      controller.duplicateSession(first.id)
      val duplicate = controller.state.value.session
      assertNotNull(duplicate)
      assertNotEquals(first.id, duplicate?.id)

      controller.archiveSession(duplicate!!.id)
      val afterArchive = controller.state.value
      assertNotEquals(duplicate.id, afterArchive.session?.id)
      assertTrue(afterArchive.archivedSessions.any { it.id == duplicate.id })
    }
  }

  @Test
  fun storeCanTruncateConversationHistory() {
    val store = isolatedSessionStore("truncate").store
    val session = store.create("Truncate ${System.currentTimeMillis()}")
    val one = store.appendMessage(session, SessionMessage(role = "user", content = "one"))
    val two = store.appendMessage(one, SessionMessage(role = "assistant", content = "two"))
    val three = store.appendMessage(two, SessionMessage(role = "user", content = "three"))

    val truncated = store.truncateMessages(three.id, 2)

    assertEquals(2, truncated?.messages?.size)
    assertEquals("two", truncated?.messages?.last()?.content)
    assertEquals(2, store.load(three.id)?.messages?.size)
    assertEquals(2, store.load(three.id)?.promptContextBlocks?.size)
  }

  @Test
  fun emptySessionsAreDeletedAndDraftSessionsAreNotListed() {
    val store = isolatedSessionStore("empty").store
    val controller = SessionController(store)
    val empty = store.create("Empty ${System.currentTimeMillis()}")
    val draft = controller.createSession()

    assertTrue(store.list().none { it.id == empty.id })
    assertNull(store.load(empty.id))
    assertTrue(store.list().none { it.id == draft.id })
  }

  @Test
  fun controllerRevertExcludesSelectedMessage() {
    withIsolatedController("revert-excludes") { controller, store ->
      controller.newSession()
      val session = controller.state.value.session
      assertNotNull(session)

      val one = store.appendMessage(session!!, SessionMessage(role = "user", content = "one"))
      val two = store.appendMessage(one, SessionMessage(role = "assistant", content = "two"))
      store.appendMessage(two, SessionMessage(role = "user", content = "three"))

      controller.openSession(session.id)
      controller.revertSessionToMessage(2)

      val reverted = controller.state.value.session
      assertEquals(2, reverted?.messages?.size)
      assertEquals("two", reverted?.messages?.last()?.content)
      assertEquals("three", controller.state.value.input)
    }
  }

  @Test
  fun controllerRejectsRevertFromAssistantMessage() {
    withIsolatedController("reject-assistant-revert") { controller, store ->
      controller.newSession()
      val session = controller.state.value.session
      assertNotNull(session)

      val one = store.appendMessage(session!!, SessionMessage(role = "user", content = "one"))
      val two = store.appendMessage(one, SessionMessage(role = "assistant", content = "two"))
      store.appendMessage(two, SessionMessage(role = "user", content = "three"))

      controller.openSession(session.id)
      controller.updateInput("draft")
      controller.revertSessionToMessage(1)

      val unchanged = controller.state.value.session
      assertEquals(3, unchanged?.messages?.size)
      assertEquals("draft", controller.state.value.input)
    }
  }

  @Test
  fun controllerRevertsFirstUserMessageIntoDraftInput() {
    withIsolatedController("revert-first") { controller, store ->
      controller.newSession()
      val session = controller.state.value.session
      assertNotNull(session)

      val one = store.appendMessage(session!!, SessionMessage(role = "user", content = "rewrite me"))
      store.appendMessage(one, SessionMessage(role = "assistant", content = "answer"))

      controller.openSession(session.id)
      controller.revertSessionToMessage(0)

      val state = controller.state.value
      assertEquals("rewrite me", state.input)
      assertTrue(state.session?.messages?.isEmpty() == true)
      assertNull(store.load(session.id))
      assertFalse(state.sessions.any { it.id == session.id })
    }
  }

  @Test
  fun sessionControllerFallsBackWhenSavedSessionIsArchived() {
    val store = isolatedSessionStore("fallback").store
    val controller = SessionController(store)
    val suffix = System.currentTimeMillis()
    val archived = store.appendMessage(store.create("Archived active $suffix"), SessionMessage(role = "user", content = "archived"))
    store.appendMessage(store.create("Fallback active $suffix"), SessionMessage(role = "user", content = "fallback"))
    store.archive(archived.id)

    val selected = controller.initialSession(archived.id)

    assertNotEquals(archived.id, selected?.id)
    assertEquals(null, selected?.archivedAtMillis)
    assertTrue(selected?.messages?.isNotEmpty() == true)
  }

  @Test
  fun sessionControllerNamesSessionFromFirstPrompt() {
    val store = isolatedSessionStore("first-prompt-title").store
    val controller = SessionController(store)
    val session = store.draft("Session ${System.currentTimeMillis()}")

    val withFirstPrompt = controller.appendUserPrompt(
      session,
      "  请做一个非常长的个性化日历工具并展示今天的安排  ",
    )
    val afterSecondPrompt = controller.appendUserPrompt(
      withFirstPrompt,
      "second prompt should not rename",
    )

    assertTrue(withFirstPrompt.title.length <= 30)
    assertEquals("请做一个非常长的个性化日历工具并展示今天的安排", withFirstPrompt.title)
    assertEquals(withFirstPrompt.title, afterSecondPrompt.title)
  }

  @Test
  fun sessionControllerRevertsBeforeSelectedMessage() {
    val store = isolatedSessionStore("controller-revert").store
    val controller = SessionController(store)
    val session = store.create("Controller revert ${System.currentTimeMillis()}")
    val one = controller.appendMessage(session, SessionMessage(role = "user", content = "one"))
    val two = controller.appendMessage(one, SessionMessage(role = "assistant", content = "two"))
    controller.appendMessage(two, SessionMessage(role = "user", content = "three"))

    val reverted = controller.revertToBeforeMessage(session.id, 1)

    assertEquals(1, reverted?.messages?.size)
    assertEquals("one", reverted?.messages?.single()?.content)
  }

  @Test
  fun appendMessagePreservesMessagesAddedAfterCallerSnapshot() {
    val store = isolatedSessionStore("append-latest").store
    val session = store.create("Append latest ${System.currentTimeMillis()}")
    val first = store.appendMessage(session, SessionMessage(role = "user", content = "first"))
    store.appendMessage(first, SessionMessage(role = "user", content = "inserted while run was active"))

    val updated = store.appendMessage(first, SessionMessage(role = "assistant", content = "final answer"))

    assertEquals(
      listOf("first", "inserted while run was active", "final answer"),
      updated.messages.map { it.content },
    )
    assertEquals(
      listOf("first", "inserted while run was active", "final answer"),
      store.load(session.id)?.messages?.map { it.content },
    )
  }

  @Test
  fun appendMessageAddsPromptContextBlocksWithoutRewritingPreviousBlocks() {
    val store = isolatedSessionStore("prompt-ledger-append").store
    val session = store.create("Prompt ledger ${System.currentTimeMillis()}")
    val first = store.appendMessage(session, SessionMessage(role = "user", content = "inspect README"))
    val assistant = store.appendMessage(
      first,
      SessionMessage(
        role = "assistant",
        content = "I read the file.",
        toolEvents = listOf(
          ToolEvent(
            name = "read_file",
            args = "path=README.md",
            result = "README full output for the next request",
            resultKind = "file_read",
            retentionPriority = ToolContextRetentionPolicy.RETENTION_RECENT_FULL,
            retentionReason = "recent file_read output may be needed by the next model request",
          ),
        ),
      ),
    )
    val beforeBlocks = assistant.promptContextBlocks.map { it.id to it.content }

    val current = store.appendMessage(assistant, SessionMessage(role = "user", content = "what did you read"))

    assertEquals(beforeBlocks, current.promptContextBlocks.take(beforeBlocks.size).map { it.id to it.content })
    assertEquals(
      listOf(
        PromptContextLedger.KIND_USER_MESSAGE,
        PromptContextLedger.KIND_PRIMARY_RESPONSE,
        PromptContextLedger.KIND_DETAIL_CONTEXT_RAW,
        PromptContextLedger.KIND_DETAIL_CONTEXT_SUMMARY,
        PromptContextLedger.KIND_USER_MESSAGE,
      ),
      current.promptContextBlocks.map { it.kind },
    )
    val history = RuntimeSessionHistory.promptText(current, currentInput = "what did you read")
    assertTrue(history.contains("user: inspect README"))
    assertTrue(history.contains("assistant: I read the file."))
    assertTrue(history.contains("detail_context: tools:"))
    assertTrue(history.contains("tool=read_file"))
    assertTrue(history.contains("README full output for the next request"))
    assertFalse(history.contains("user: what did you read"))
  }

  @Test
  fun appendMessageBackfillsPromptContextBlocksForLegacySessions() {
    val store = isolatedSessionStore("prompt-ledger-backfill").store
    val session = store.create("Legacy prompt ledger ${System.currentTimeMillis()}")
    store.save(
      session.copy(
        messages = listOf(
          SessionMessage(role = "user", content = "legacy request"),
          SessionMessage(role = "assistant", content = "legacy answer"),
        ),
        promptContextBlocks = emptyList(),
      ),
    )

    val updated = store.appendMessage(session, SessionMessage(role = "user", content = "continue"))
    val loaded = store.load(updated.id)

    assertEquals(listOf(0, 1, 2), loaded?.promptContextBlocks?.map { it.sourceMessageIndex })
    val history = RuntimeSessionHistory.promptText(updated, currentInput = "continue")
    assertTrue(history.contains("user: legacy request"))
    assertTrue(history.contains("assistant: legacy answer"))
    assertFalse(history.contains("user: continue"))
  }

  @Test
  fun runtimeHistoryPreservesUserAndAssistantAcrossCompressionDivider() {
    val store = isolatedSessionStore("runtime-history-compressed").store
    val session = store.create("Runtime history ${System.currentTimeMillis()}")
    val oldUser = store.appendMessage(session, SessionMessage(role = "user", content = "old user request"))
    val oldAssistant = store.appendMessage(oldUser, SessionMessage(role = "assistant", content = "old assistant result"))
    val activeUser = store.appendMessage(oldAssistant, SessionMessage(role = "user", content = "active user request"))
    val activeAssistant = store.appendMessage(activeUser, SessionMessage(role = "assistant", content = "active assistant result"))
    val compressed = store.appendCompressionDivider(
      activeAssistant,
      contextRecord("compression-record"),
      "Keep only the project target and pending task.",
    )
    val current = store.appendMessage(compressed, SessionMessage(role = "user", content = "continue the task"))

    val history = RuntimeSessionHistory.promptText(current, currentInput = "continue the task")

    assertTrue(history.contains("user: old user request"))
    assertTrue(history.contains("assistant: old assistant result"))
    assertTrue(history.contains("user: active user request"))
    assertTrue(history.contains("assistant: active assistant result"))
    assertFalse(history.contains("handoff_summary:"))
    assertFalse(history.contains("Keep only the project target and pending task."))
    assertFalse(history.contains("user: continue the task"))
  }

  @Test
  fun runtimeHistoryWithoutCompressionKeepsRecentMessagesAndSkipsCurrentInputDuplicate() {
    val store = isolatedSessionStore("runtime-history-plain").store
    val session = store.create("Runtime history plain ${System.currentTimeMillis()}")
    val one = store.appendMessage(session, SessionMessage(role = "user", content = "one"))
    val two = store.appendMessage(one, SessionMessage(role = "assistant", content = "two"))
    val three = store.appendMessage(two, SessionMessage(role = "user", content = "three"))

    val entries = RuntimeSessionHistory.entries(three, currentInput = "three", maxMessages = 12)

    assertEquals(listOf("one", "two"), entries.map { it.content })
  }

  @Test
  fun runtimeHistorySkipsStatusOnlyMessages() {
    val store = isolatedSessionStore("runtime-history-status").store
    val session = store.create("Runtime history status ${System.currentTimeMillis()}")
    val one = store.appendMessage(session, SessionMessage(role = "user", content = "task"))
    val status = store.appendMessage(one, SessionMessage(role = "assistant", content = ""))
    val two = store.appendMessage(status, SessionMessage(role = "assistant", content = "answer"))

    val entries = RuntimeSessionHistory.entries(two)

    assertEquals(listOf("task", "answer"), entries.map { it.content })
  }

  @Test
  fun runtimeHistoryKeepsToolOnlyAssistantMessageAfterInterruptedRun() {
    val store = isolatedSessionStore("runtime-history-interrupted-tools").store
    val session = store.create("Runtime history interrupted ${System.currentTimeMillis()}")
    val first = store.appendMessage(session, SessionMessage(role = "user", content = "connect with ssh"))
    val interrupted = store.appendMessage(
      first,
      SessionMessage(
        role = "assistant",
        content = "",
        toolEvents = listOf(
          ToolEvent(
            name = "read_file",
            args = "path=ssh_connect.groovy",
            result = "import com.jcraft.jsch.*\nprintln 'loaded ssh script'",
            resultKind = "file_read",
            retentionPriority = ToolContextRetentionPolicy.RETENTION_RECENT_FULL,
            retentionReason = "recent file_read output may be needed by the next model request",
          ),
        ),
        runEvents = listOf(
          AgentRunTimelineEvent(
            type = "run_interrupted",
            title = "Run interrupted",
            detail = "The active agent run was cancelled by the user; partial transcript and tool history were saved.",
            status = "interrupted",
          ),
        ),
        transcriptEvents = listOf(
          ConversationTranscriptEvent(
            type = "assistant_text",
            role = "assistant",
            content = "JSch loaded; authentication failed for root. Trying other usernames.",
          ),
          ConversationTranscriptEvent(
            type = "tool_call",
            title = "Tool: read_file",
            detail = "Read ssh_connect.groovy",
            status = "completed",
          ),
          ConversationTranscriptEvent(
            type = "run_interrupted",
            title = "Run interrupted",
            detail = "The active agent run was cancelled by the user; partial transcript and tool history were saved.",
            status = "interrupted",
          ),
        ),
      ),
    )
    val current = store.appendMessage(interrupted, SessionMessage(role = "user", content = "what did you read"))

    val history = RuntimeSessionHistory.promptText(current, currentInput = "what did you read")

    assertTrue(history.contains("user: connect with ssh"))
    assertTrue(history.contains("run_context: events: run_interrupted:Run interrupted"))
    assertTrue(history.contains("JSch loaded; authentication failed for root"))
    assertTrue(history.contains("run_interrupted:Run interrupted"))
    assertTrue(history.contains("detail_context: transcript:"))
    assertTrue(history.contains("tool=read_file"))
    assertTrue(history.contains("path=ssh_connect.groovy"))
    assertTrue(history.contains("loaded ssh script"))
    assertFalse(history.contains("user: what did you read"))
  }

  @Test
  fun runtimeHistoryKeepsChronologicalTranscriptContextBetweenToolCalls() {
    val store = isolatedSessionStore("runtime-history-transcript-ledger").store
    val session = store.create("Runtime transcript ledger ${System.currentTimeMillis()}")
    val first = store.appendMessage(session, SessionMessage(role = "user", content = "inspect and explain"))
    val assistant = store.appendMessage(
      first,
      SessionMessage(
        role = "assistant",
        content = "",
        transcriptEvents = listOf(
          ConversationTranscriptEvent(
            type = "tool_call",
            title = "Tool: read_file",
            detail = "Read one.txt",
            status = "completed",
            timestampMillis = 1L,
          ),
          ConversationTranscriptEvent(
            type = "assistant_text",
            role = "assistant",
            content = "First explanation after read.",
            timestampMillis = 2L,
          ),
          ConversationTranscriptEvent(
            type = "tool_call",
            title = "Tool: workspace_search",
            detail = "Searched project",
            status = "completed",
            timestampMillis = 3L,
          ),
          ConversationTranscriptEvent(
            type = "assistant_text",
            role = "assistant",
            content = "Second explanation after search.",
            timestampMillis = 4L,
          ),
        ),
      ),
    )
    val current = store.appendMessage(assistant, SessionMessage(role = "user", content = "what happened"))

    val transcriptContext = RuntimeSessionHistory.entries(current, currentInput = "what happened")
      .single { it.role == "detail_context" }

    assertTrue(transcriptContext.content.contains("tool_call:Tool: read_file:Read one.txt"))
    assertTrue(transcriptContext.content.contains("assistant_text:assistant:First explanation after read."))
    assertTrue(transcriptContext.content.contains("tool_call:Tool: workspace_search:Searched project"))
    assertTrue(transcriptContext.content.contains("assistant_text:assistant:Second explanation after search."))
    assertTrue(
      transcriptContext.content.indexOf("Read one.txt") <
        transcriptContext.content.indexOf("First explanation after read."),
    )
    assertTrue(
      transcriptContext.content.indexOf("First explanation after read.") <
        transcriptContext.content.indexOf("Searched project"),
    )
  }

  @Test
  fun runtimeHistoryAddsPolicyBasedToolContextSlices() {
    val store = isolatedSessionStore("runtime-history-tools").store
    val session = store.create("Runtime history tools ${System.currentTimeMillis()}")
    val first = store.appendMessage(session, SessionMessage(role = "user", content = "make the app"))
    val withFailedTool = store.appendMessage(
      first,
      SessionMessage(
        role = "assistant",
        content = "The first command failed.",
        toolEvents = listOf(
          ToolEvent(
            name = "workspace_command_run",
            args = "argv=[python, broken.py]",
            result = "Traceback: missing dependency",
            success = false,
            resultKind = "command",
            retentionPriority = ToolContextRetentionPolicy.RETENTION_ACTIVE_CRITICAL,
            retentionReason = "failed tool output must remain available for retry and diagnosis",
          ),
          ToolEvent(
            name = "read_skill",
            args = "skill=android-operation",
            result = "Skill says: keep semantic tree and OCR evidence.",
            success = true,
            resultKind = "skill_read",
            retentionPriority = ToolContextRetentionPolicy.RETENTION_STRUCTURED_MEMORY,
            retentionReason = "skill reads should remain available across the next prompt turn",
          ),
        ),
      ),
    )
    val withWrite = store.appendMessage(
      withFailedTool,
      SessionMessage(
        role = "assistant",
        content = "Wrote the fixed file.",
        toolEvents = listOf(
          ToolEvent(
            name = "write_file",
            args = "path=index.html",
            result = "wrote index.html with mobile layout",
            resultKind = "file_write",
            retentionPriority = ToolContextRetentionPolicy.RETENTION_STRUCTURED_MEMORY,
            retentionReason = "successful file_write records changed artifacts or validation facts",
          ),
        ),
      ),
    )
    val current = store.appendMessage(withWrite, SessionMessage(role = "user", content = "continue"))

    val history = RuntimeSessionHistory.promptText(current, currentInput = "continue")

    assertTrue(history.contains("run_context: promoted_tools:"))
    assertTrue(history.contains("tool=workspace_command_run"))
    assertTrue(history.contains("Traceback: missing dependency"))
    assertTrue(history.contains("tool=read_skill"))
    assertTrue(history.contains("keep semantic tree and OCR evidence"))
    assertTrue(history.contains("detail_context: tools:"))
    assertTrue(history.contains("tool=write_file"))
    assertTrue(history.contains("wrote index.html"))
    assertFalse(history.contains("user: continue"))
  }

  @Test
  fun runtimeHistorySummarizesOldDetailContextWithoutTruncatingMessages() {
    val store = isolatedSessionStore("runtime-history-tool-ledger").store
    val session = store.create("Runtime history tool ledger ${System.currentTimeMillis()}")
    val one = store.appendMessage(session, SessionMessage(role = "user", content = "inspect"))
    val oldTool = store.appendMessage(
      one,
      SessionMessage(
        role = "assistant",
        content = "Read a long file.",
        toolEvents = listOf(
          ToolEvent(
            name = "read_file",
            args = "path=large.txt",
            result = "Important fact from ancient file. ${"x".repeat(1_000)}",
            resultKind = "file_read",
            retentionPriority = ToolContextRetentionPolicy.RETENTION_RECENT_FULL,
            retentionReason = "recent file_read output may be needed by the next model request",
          ),
        ),
      ),
    )
    val two = store.appendMessage(oldTool, SessionMessage(role = "user", content = "follow up one"))
    val three = store.appendMessage(two, SessionMessage(role = "assistant", content = "Later answer one."))
    val four = store.appendMessage(three, SessionMessage(role = "user", content = "follow up two"))
    val five = store.appendMessage(four, SessionMessage(role = "assistant", content = "Later answer two."))
    val six = store.appendMessage(five, SessionMessage(role = "user", content = "follow up three"))
    val seven = store.appendMessage(six, SessionMessage(role = "assistant", content = "Later answer three."))
    val current = store.appendMessage(seven, SessionMessage(role = "user", content = "continue"))

    val entries = RuntimeSessionHistory.entries(current, currentInput = "continue", maxMessages = 1)
    val history = entries.joinToString("\n") { "${it.role}: ${it.content}" }

    val detailSummary = entries.single { it.role == "detail_context_summary" }
    assertTrue(history.contains("user: inspect"))
    assertTrue(history.contains("assistant: Read a long file."))
    assertTrue(detailSummary.content.contains("artifacts: large.txt"))
    assertTrue(detailSummary.content.contains("facts: Important fact from ancient file."))
    assertTrue(detailSummary.content.contains("resume_handles: read_file: path=large.txt"))
    assertFalse(history.contains("x".repeat(500)))
    assertFalse(history.contains("user: continue"))
  }

  @Test
  fun runtimeHistoryDoesNotTruncateUserOrAssistantFinalMessages() {
    val store = isolatedSessionStore("runtime-history-no-message-truncate").store
    val session = store.create("Runtime history no truncate ${System.currentTimeMillis()}")
    val longUser = "user-start " + "u".repeat(4_000) + " user-end"
    val longAssistant = "assistant-start " + "a".repeat(4_000) + " assistant-end"
    val one = store.appendMessage(session, SessionMessage(role = "user", content = longUser))
    val two = store.appendMessage(one, SessionMessage(role = "assistant", content = longAssistant))
    val three = store.appendMessage(two, SessionMessage(role = "user", content = "next"))
    val four = store.appendMessage(three, SessionMessage(role = "assistant", content = "short answer"))
    val current = store.appendMessage(four, SessionMessage(role = "user", content = "continue"))

    val history = RuntimeSessionHistory.promptText(current, currentInput = "continue", maxMessages = 1)

    assertTrue(history.contains(longUser))
    assertTrue(history.contains(longAssistant))
    assertTrue(history.contains("assistant: short answer"))
    assertFalse(history.contains("user: continue"))
  }

  @Test
  fun runtimeHistoryDropsOldRunContextAndUsesAssistantSummaryAfterCompressionRestart() {
    val store = isolatedSessionStore("runtime-history-compression-restart").store
    val session = store.create("Runtime compression restart ${System.currentTimeMillis()}")
    val ancientUser = store.appendMessage(session, SessionMessage(role = "user", content = "ancient request"))
    val ancientAssistant = store.appendMessage(
      ancientUser,
      SessionMessage(
        role = "assistant",
        content = "ancient assistant answer",
        toolEvents = listOf(
          ToolEvent(
            name = "read_file",
            args = "path=ancient.txt",
            result = "ancient raw tool output",
            resultKind = "file_read",
            retentionPriority = ToolContextRetentionPolicy.RETENTION_RECENT_FULL,
            retentionReason = "old detail should be droppable after compression restart",
          ),
        ),
        runEvents = listOf(
          AgentRunTimelineEvent(
            type = "context_checkpoint",
            title = "Ancient checkpoint",
            detail = "ancient checkpoint detail",
            status = "completed",
          ),
        ),
      ),
    )
    val recentUser = store.appendMessage(ancientAssistant, SessionMessage(role = "user", content = "recent request"))
    val recentAssistant = store.appendMessage(
      recentUser,
      SessionMessage(
        role = "assistant",
        content = "recent answer",
        toolEvents = listOf(
          ToolEvent(
            name = "read_file",
            args = "path=recent.txt",
            result = "recent raw tool output",
            resultKind = "file_read",
            retentionPriority = ToolContextRetentionPolicy.RETENTION_RECENT_FULL,
            retentionReason = "near compression restart should keep full detail",
          ),
        ),
      ),
    )
    val fillerOne = store.appendMessage(recentAssistant, SessionMessage(role = "user", content = "filler one"))
    val fillerAnswerOne = store.appendMessage(fillerOne, SessionMessage(role = "assistant", content = "filler answer one"))
    val fillerTwo = store.appendMessage(fillerAnswerOne, SessionMessage(role = "user", content = "filler two"))
    val fillerAnswerTwo = store.appendMessage(fillerTwo, SessionMessage(role = "assistant", content = "filler answer two"))
    val activeUser = store.appendMessage(fillerAnswerTwo, SessionMessage(role = "user", content = "overflow request"))
    val activeAssistant = store.appendMessage(activeUser, SessionMessage(role = "assistant", content = "overflow failed"))
    val compressed = store.appendCompressionDivider(
      activeAssistant,
      contextRecord("compression-record"),
      "compression handoff should stay out of runtime history",
    )
    val current = store.appendMessage(compressed, SessionMessage(role = "user", content = "retry"))
    val withSummary = store.appendPromptContextBlocks(
      current,
      listOf(
        PromptContextLedger.buildAssistantSummaryBlock(
          sourceMessageIndex = 1,
          runIndex = 0,
          role = "assistant",
          summary = "summary of the oldest assistant final",
          sourceTimestampMillis = current.messages[1].timestampMillis,
        ),
      ),
    )

    val history = RuntimeSessionHistory.promptText(withSummary, currentInput = "retry")

    assertTrue(history.contains("user: ancient request"))
    assertTrue(history.contains("assistant: summary of the oldest assistant final"))
    assertFalse(history.contains("assistant: ancient assistant answer"))
    assertFalse(history.contains("ancient raw tool output"))
    assertFalse(history.contains("ancient checkpoint detail"))
    assertTrue(history.contains("user: recent request"))
    assertTrue(history.contains("assistant: recent answer"))
    assertTrue(history.contains("recent raw tool output"))
    assertTrue(history.contains("user: overflow request"))
    assertTrue(history.contains("assistant: overflow failed"))
    assertFalse(history.contains("compression handoff should stay out of runtime history"))
    assertFalse(history.contains("user: retry"))
  }

  private fun isolatedSessionStore(name: String): SessionStoreHarness {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "session-tests/$name-${UUID.randomUUID()}")
    val sessionsRoot = File(root, "sessions")
    return SessionStoreHarness(
      store = AgentSessionStore(context, sessionsRoot),
      root = root,
      sessionsRoot = sessionsRoot,
    )
  }

  private fun withIsolatedController(name: String, block: (AgentController, AgentSessionStore) -> Unit) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "controller-tests/$name-${UUID.randomUUID()}")
    val workspaceId = "controller-$name-${UUID.randomUUID()}"
    val settingsStore = SettingsStore(context, File(root, "settings.json"))
    val sessionStore = AgentSessionStore(context, File(root, "sessions"))
    settingsStore.save(AppSettings(activeWorkspaceId = workspaceId))
    try {
      block(AgentController(context, settingsStore, sessionStore), sessionStore)
    } finally {
      root.deleteRecursively()
      File(context.filesDir, "workspaces/$workspaceId").deleteRecursively()
      File(context.filesDir, "workspace-snapshots/$workspaceId").deleteRecursively()
    }
  }

  private data class SessionStoreHarness(
    val store: AgentSessionStore,
    val root: File,
    val sessionsRoot: File,
  )

  private fun contextRecord(id: String): ContextUsageRecord {
    return ContextUsageRecord(
      id = id,
      source = "test",
      provider = "deepseek",
      model = "deepseek-v4-pro",
      messageCount = 2,
      inputChars = 10,
      historyChars = 20,
      rulesChars = 0,
      workspaceListingChars = 0,
      approximateTokens = 10,
      contextBudgetStatus = "compression_recommended",
    )
  }
}
