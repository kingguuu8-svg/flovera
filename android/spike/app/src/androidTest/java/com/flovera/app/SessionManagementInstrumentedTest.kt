package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagementInstrumentedTest {
  @Test
  fun storeCanRenameDuplicateArchiveAndRestoreSessions() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val suffix = System.currentTimeMillis()
    val source = store.create("Source $suffix")
    val withMessage = store.appendMessage(source, SessionMessage(role = "user", content = "hello"))

    val copy = store.duplicate(withMessage.id)
    assertNotNull(copy)
    assertNotEquals(withMessage.id, copy?.id)
    assertEquals(withMessage.messages.size, copy?.messages?.size)

    val renamed = store.rename(withMessage.id, "Renamed $suffix")
    assertEquals("Renamed $suffix", renamed?.title)

    val other = store.create("Other $suffix")
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
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val suffix = System.currentTimeMillis()
    val olderPinned = store.create("Older pinned $suffix")
    val newerPinned = store.create("Newer pinned $suffix")
    val newestUnpinned = store.create("Newest unpinned $suffix")
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
  fun controllerArchivesActiveSessionAndSwitchesToUsableSession() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val controller = AgentController(context)
    controller.newSession()
    val first = controller.state.value.session
    assertNotNull(first)

    controller.renameSession(first!!.id, "Managed ${System.currentTimeMillis()}")
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

  @Test
  fun storeCanTruncateConversationHistory() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
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
  fun controllerRevertExcludesSelectedMessage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val controller = AgentController(context)
    controller.newSession()
    val session = controller.state.value.session
    assertNotNull(session)

    val store = AgentSessionStore(context)
    val one = store.appendMessage(session!!, SessionMessage(role = "user", content = "one"))
    val two = store.appendMessage(one, SessionMessage(role = "assistant", content = "two"))
    store.appendMessage(two, SessionMessage(role = "user", content = "three"))

    controller.openSession(session.id)
    controller.revertSessionToMessage(1)

    val reverted = controller.state.value.session
    assertEquals(1, reverted?.messages?.size)
    assertEquals("one", reverted?.messages?.single()?.content)
  }

  @Test
  fun sessionControllerFallsBackWhenSavedSessionIsArchived() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val controller = SessionController(store)
    val suffix = System.currentTimeMillis()
    val archived = store.create("Archived active $suffix")
    store.archive(archived.id)

    val selected = controller.initialSession(archived.id)

    assertNotEquals(archived.id, selected.id)
    assertEquals(null, selected.archivedAtMillis)
  }

  @Test
  fun sessionControllerNamesSessionFromFirstPrompt() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val controller = SessionController(store)
    val session = store.create("Session ${System.currentTimeMillis()}")

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
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val controller = SessionController(store)
    val session = store.create("Controller revert ${System.currentTimeMillis()}")
    val one = controller.appendMessage(session, SessionMessage(role = "user", content = "one"))
    val two = controller.appendMessage(one, SessionMessage(role = "assistant", content = "two"))
    controller.appendMessage(two, SessionMessage(role = "user", content = "three"))

    val reverted = controller.revertToBeforeMessage(session.id, 1)

    assertEquals(1, reverted?.messages?.size)
    assertEquals("one", reverted?.messages?.single()?.content)
  }
}
