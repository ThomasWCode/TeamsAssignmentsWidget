package com.teamsassignments.widget.provider

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/** The manifest has to agree with [AssignmentsContract], or other apps would look in the wrong place. */
class ProviderManifestTest {

    private val manifest: Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .documentElement

    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

    private fun elements(tag: String): List<Element> =
        manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    @Test
    fun `the permission is signature-level`() {
        val permission = elements("permission").single { it.android("name") == AssignmentsContract.PERMISSION }

        assertEquals("signature", permission.android("protectionLevel"))
    }

    @Test
    fun `the provider has the contract's authority and needs the permission`() {
        val provider = elements("provider").single { it.android("name") == ".provider.AssignmentsProvider" }

        assertEquals(AssignmentsContract.AUTHORITY, provider.android("authorities"))
        assertEquals("true", provider.android("exported"))
        assertEquals(AssignmentsContract.PERMISSION, provider.android("permission"))
        assertEquals("", provider.android("readPermission"), "a narrower readPermission would override it")
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
