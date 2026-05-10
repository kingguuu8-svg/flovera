package com.flovera.app.storage

import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal fun readUtf8Text(file: File): String {
  return AtomicFile(file).openRead().reader(StandardCharsets.UTF_8).use { it.readText() }
}

internal fun writeUtf8TextAtomically(file: File, text: String) {
  writeBytesAtomically(file, text.toByteArray(StandardCharsets.UTF_8))
}

internal fun writeBytesAtomically(file: File, bytes: ByteArray) {
  writeStreamAtomically(file, bytes.inputStream())
}

internal fun writeStreamAtomically(file: File, input: InputStream) {
  file.parentFile?.mkdirs()
  val atomicFile = AtomicFile(file)
  val stream = atomicFile.startWrite()
  try {
    input.use { it.copyTo(stream) }
    atomicFile.finishWrite(stream)
  } catch (throwable: Throwable) {
    atomicFile.failWrite(stream)
    throw throwable
  }
}
