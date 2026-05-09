package com.example.ailinuxvmspike

import androidx.test.platform.app.InstrumentationRegistry
import com.example.ailinuxvmspike.session.AgentSessionStore
import com.example.ailinuxvmspike.session.SessionMessage
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

    val archived = store.archive(withMessage.id)
    assertNotNull(archived?.archivedAtMillis)
    assertTrue(store.list().none { it.id == withMessage.id })
    assertTrue(store.listArchived().any { it.id == withMessage.id })

    val restored = store.restore(withMessage.id)
    assertEquals(null, restored?.archivedAtMillis)
    assertTrue(store.list().any { it.id == withMessage.id })
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
