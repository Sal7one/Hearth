package com.sal7one.hearth.desktop.runtime

import org.junit.Assume
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows grants symbolic-link creation only to elevated or Developer Mode accounts.
 * A host that denies it reports the test as skipped; any other failure still fails.
 */
internal fun createSymbolicLinkOrSkip(link: Path, target: Path): Path = try {
    Files.createSymbolicLink(link, target)
} catch (error: FileSystemException) {
    Assume.assumeFalse(
        "This Windows account may not create symbolic links: ${error.reason}",
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    )
    throw error
}
