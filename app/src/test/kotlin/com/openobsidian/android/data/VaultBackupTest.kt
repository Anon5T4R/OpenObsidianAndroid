package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VaultBackupTest {

    private val day = LocalDate.of(2026, 7, 21)

    @Test
    fun `the name carries the vault and the date`() {
        assertEquals("MeuVault-2026-07-21.zip", VaultBackup.suggestedName("MeuVault", day))
    }

    @Test
    fun `characters a filesystem refuses are replaced`() {
        val name = VaultBackup.suggestedName("Vault: notas/2026", day)
        assertTrue(name, !name.contains(':') && !name.contains('/'))
        assertEquals("Vault_ notas_2026-2026-07-21.zip", name)
    }

    @Test
    fun `an empty name still produces a usable file`() {
        assertEquals("vault-2026-07-21.zip", VaultBackup.suggestedName("   ", day))
    }

    @Test
    fun `the date sorts naturally`() {
        val a = VaultBackup.suggestedName("v", LocalDate.of(2026, 1, 5))
        val b = VaultBackup.suggestedName("v", LocalDate.of(2026, 11, 5))
        // ISO dates sort as text, which is the point of using them
        assertTrue(a < b)
    }
}
