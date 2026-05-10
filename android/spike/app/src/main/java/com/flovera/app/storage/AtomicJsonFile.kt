package com.flovera.app.storage

import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets

internal fun readUtf8Text(file: File): String {
  return AtomicFile(file).openRead().reader(StandardCharsets.UTF_8).use { it.readText() }
}

internal fun writeUtf8TextAtomically(file: File, text: String) {
  file.parentFile?.mkdirs()
  val atomicFile = AtomicFile(file)
  val stream = atomicFile.startWrite()
  try {
    stream.write(text.toByteArray(StandardCharsets.UTF_8))
    atomicFile.finishWrite(stream)
  } catch (throwable: Throwable) {
    atomicFile.failWrite(stream)
    throw throwable
  }
}
