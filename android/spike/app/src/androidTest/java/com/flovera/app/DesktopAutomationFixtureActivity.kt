package com.flovera.app

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DesktopAutomationFixtureActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val density = resources.displayMetrics.density
    val padding = (24 * density).toInt()
    val input = EditText(this).apply {
      hint = "Desktop input"
      contentDescription = "Desktop input"
      minHeight = (56 * density).toInt()
    }
    val result = TextView(this).apply {
      text = "Ready"
      textSize = 18f
      setPadding(0, padding, 0, padding)
    }
    val submit = Button(this).apply {
      text = "Submit"
      contentDescription = "Submit"
      minHeight = (48 * density).toInt()
      setOnClickListener {
        result.text = "Submitted: ${input.text}"
      }
    }
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding, padding, padding)
      addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      addView(submit, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      addView(result, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      repeat(18) { index ->
        addView(
          TextView(this@DesktopAutomationFixtureActivity).apply {
            text = "Filler row $index"
            textSize = 18f
            minHeight = (64 * density).toInt()
          },
          ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
      }
      addView(
        TextView(this@DesktopAutomationFixtureActivity).apply {
          text = "Lower target"
          contentDescription = "Lower target"
          textSize = 20f
          minHeight = (72 * density).toInt()
        },
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
      )
    }
    setContentView(
      ScrollView(this).apply {
        addView(
          content,
          ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
      },
    )
  }
}
