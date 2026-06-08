package com.flovera.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloveraSkillRegistryTest {
  @Test
  fun legacyOfficeSkillsMigrateToOneVisibleSuite() {
    val manifest = FloveraSkillManifest(
      skills = listOf(
        FloveraSkillRegistration(id = "custom-skill", enabled = true),
        FloveraSkillRegistration(id = "flovera-office-ooxml", enabled = false),
        FloveraSkillRegistration(id = "flovera-docx-engine", enabled = false),
        FloveraSkillRegistration(id = "flovera-pptx-engine", enabled = true),
        FloveraSkillRegistration(id = "flovera-pdf-engine", enabled = false),
      ),
    )

    val merged = FloveraSkillRegistry.mergedDefaultManifest(manifest)

    assertTrue(merged.skills.any { it.id == "custom-skill" })
    assertFalse(merged.skills.any { it.id in LEGACY_OFFICE_IDS })
    assertEquals(1, merged.skills.count { it.id == "flovera-office-suite" })
    assertTrue(merged.skills.single { it.id == "flovera-office-suite" }.enabled)
  }

  @Test
  fun officeSuiteDescribesAllFourFormatSpecificPipelines() {
    val body = FloveraSkillRegistry.defaultSkillBody("flovera-office-suite")

    assertTrue(body.contains("openpyxl"))
    assertTrue(body.contains("python-docx"))
    assertTrue(body.contains("python-pptx"))
    assertTrue(body.contains("fpdf2"))
    assertTrue(body.contains("flovera\", \"office\", \"validate"))
    assertTrue(body.contains("artifact_inspect"))
  }

  private companion object {
    val LEGACY_OFFICE_IDS = setOf(
      "flovera-office-ooxml",
      "flovera-docx-engine",
      "flovera-pptx-engine",
      "flovera-pdf-engine",
      "flovera-xlsx-engine",
    )
  }
}
