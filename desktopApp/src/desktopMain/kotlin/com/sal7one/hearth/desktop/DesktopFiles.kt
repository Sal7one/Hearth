package com.sal7one.hearth.desktop

import java.awt.Component
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

internal fun chooseFile(parent: Component?, title: String, extensions: List<String> = emptyList(), directories: Boolean = false,
    directoriesOnly: Boolean = false): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = when {
            directoriesOnly -> JFileChooser.DIRECTORIES_ONLY
            directories -> JFileChooser.FILES_AND_DIRECTORIES
            else -> JFileChooser.FILES_ONLY
        }
        if (extensions.isNotEmpty()) fileFilter = FileNameExtensionFilter(extensions.joinToString(", ") { "*.$it" }, *extensions.toTypedArray())
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

internal fun chooseSaveFile(parent: Component?, title: String, name: String): Path? {
    val chooser = JFileChooser().apply { dialogTitle = title; selectedFile = java.io.File(name) }
    if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null
    val path = chooser.selectedFile.toPath()
    if (Files.exists(path) && JOptionPane.showConfirmDialog(parent, "Replace ${path.fileName}?", "Replace file",
            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null
    return path
}

internal fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

internal fun openPath(path: Path) {
    check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { "This desktop cannot open files automatically" }
    Desktop.getDesktop().open(path.toFile())
}
