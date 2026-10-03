package io.github.mkdevtests.umbra.nas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nyxara never changes the NAS. These checks read the app's sources and fail
 * the build if SMB code gains a way to write, rename or delete.
 */
class ReadOnlyTest {

    private val sources = File("src/main/java").walk().filter { it.extension == "kt" }.toList()
    private val smbNas = sources.single { it.name == "SmbNas.kt" }

    @Test
    fun onlySmbNasTalksSmb() {
        val smbUsers = sources.filter { file ->
            file.readLines().any { it.startsWith("import com.hierynomus") || it.startsWith("import com.rapid7") }
        }
        assertEquals(listOf(smbNas), smbUsers)
    }

    @Test
    fun filesOpenForReadingOnly() {
        val code = smbNas.readText()
        val masks = Regex("""AccessMask\.(\w+)""").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("GENERIC_READ"), masks)
        val dispositions = Regex("""SMB2CreateDisposition\.(\w+)""").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("FILE_OPEN"), dispositions)
        assertTrue(Regex("""FilePipeAccessMask|DirectoryAccessMask""").find(code) == null)
    }

    @Test
    fun noWriteRenameOrDelete() {
        val forbidden = Regex(
            """\.(rm|rmdir|mkdir|rename|write|deleteOnClose|openOutputStream|setFileInformation|setSecurityInfo|getOutputStream)\("""
        )
        val hits = smbNas.readLines().withIndex().filter { (_, line) -> forbidden.containsMatchIn(line) }
        assertTrue("SMB write operation in SmbNas.kt: ${hits.map { "line ${it.index + 1}" }}", hits.isEmpty())
    }
}
