package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.SettingsStore
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.SESSION_ROLE_COMPRESSION
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import com.flovera.app.session.ToolEvent
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
}
