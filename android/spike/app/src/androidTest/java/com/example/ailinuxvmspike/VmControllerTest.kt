package com.example.ailinuxvmspike

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VmControllerTest {
  @Test
  fun prepareAssets_writesReleasedFiles() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = VmController(context)
    try {
      controller.prepareAssets().join()
      val baseDir = context.filesDir.resolve("ai-linux-spike")
      assertTrue(baseDir.resolve("released-assets/inputs-template.txt").isFile)
      assertTrue(baseDir.resolve("released-assets/qemu-launch-template.sh").isFile)
    } finally {
      controller.close()
    }
  }
}
