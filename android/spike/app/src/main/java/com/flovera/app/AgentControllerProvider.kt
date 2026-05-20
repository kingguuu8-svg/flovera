package com.flovera.app

import android.content.Context

object AgentControllerProvider {
  @Volatile
  private var controller: AgentController? = null

  fun get(context: Context): AgentController {
    return controller ?: synchronized(this) {
      controller ?: AgentController(context.applicationContext).also { controller = it }
    }
  }
}
