package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.web.FloveraWebBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBridgeInstrumentedTest {
  @Test
  fun webBridgeAcceptsControlledToastEvents() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val bridge = FloveraWebBridge(context)

    assertEquals("ok", bridge.postEvent("""{"type":"toast","message":"hello"}"""))
    assertEquals("unsupported event type", bridge.postEvent("""{"type":"unknown"}"""))
    assertEquals("invalid json", bridge.postEvent("not json"))
  }

  @Test
  fun webBridgeNotificationReturnsExplicitStatus() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val bridge = FloveraWebBridge(context)

    val result = bridge.notify("""{"title":"Alarm","body":"Time to check flovera"}""")

    assertTrue(result == "ok" || result == "notification permission not granted")
  }
}
