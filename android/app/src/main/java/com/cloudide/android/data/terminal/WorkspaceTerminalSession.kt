package com.cloudide.android.data.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TerminalCommandResult(
    val output: String = "",
    val error: String? = null,
    val clearScreen: Boolean = false,
    val closeSession: Boolean = false,
)

/**
 * A stable, app-native terminal session scoped to the CloudIDE workspace.
 *
 * This intentionally does not emulate a full Linux distro. It provides a small
 * command set for navigating and editing the current project safely on Android.
 */
class WorkspaceTerminalSession(rootDir: File) {
    private val rootDir = rootDir.canonicalFile.apply { mkdirs() }
    private var currentDir: File = this.rootDir

    var isAlive: Boolean = true
        private set

    fun bannerLines(): List<String> = listOf(
        "CloudIDE Workspace Terminal",
        "Root: ${displayPath(rootDir)}",
        "Commands: help, pwd, ls, cd, tree, find, cat, stat, mkdir, touch, cp, mv, rm, echo, clear, exit",
        "Local Android shell only. node/npm/python/pip/git are not available here.",
    )

    suspend fun execute(rawCommand: String): TerminalCommandResult = withContext(Dispatchers.IO) {
        if (!isAlive) {
            return@withContext TerminalCommandResult(error = "Session is closed. Restart the terminal.")
        }

        val parsed = parse(rawCommand)
            ?: return@withContext TerminalCommandResult(error = "Could not parse command.")
        if (parsed.args.isEmpty()) {
            return@withContext TerminalCommandResult()
        }

        try {
            when (parsed.args.first().lowercase(Locale.ROOT)) {
                "help" -> helpResult()
                "pwd" -> TerminalCommandResult(output = displayPath(currentDir))
                "ls" -> lsResult(parsed.args.drop(1))
                "cd" -> cdResult(parsed.args.drop(1))
                "tree" -> treeResult(parsed.args.drop(1))
                "find" -> findResult(parsed.args.drop(1))
                "cat" -> catResult(parsed.args.drop(1))
                "stat" -> statResult(parsed.args.drop(1))
                "mkdir" -> mkdirResult(parsed.args.drop(1))
                "touch" -> touchResult(parsed.args.drop(1))
                "cp" -> copyResult(parsed.args.drop(1))
                "mv" -> moveResult(parsed.args.drop(1))
                "rm" -> removeResult(parsed.args.drop(1))
                "echo" -> echoResult(parsed)
                "clear" -> TerminalCommandResult(clearScreen = true)
                "exit", "quit" -> {
                    isAlive = false
                    TerminalCommandResult(output = "Session closed.", closeSession = true)
                }
                "node", "npm", "npx", "python", "python3", "pip", "pip3", "git",
                "apk", "proot", "bash", "sh" -> unsupportedRuntimeResult(parsed.args.first())
                else -> TerminalCommandResult(
                    error = "Unknown command: ${parsed.args.first()}. Run `help` for supported commands."
                )
            }
        } catch (e: Exception) {
            TerminalCommandResult(error = e.message ?: "Command failed.")
        }
    }

    fun destroy() {
        isAlive = false
    }

    private fun helpResult(): TerminalCommandResult = TerminalCommandResult(
        output = """
            CloudIDE workspace commands
            help               Show this message
            pwd                Show current directory
            ls [-a] [path]     List files
            cd [path]          Change directory inside the project
            tree [path]        Show a recursive file tree
            find <name> [dir]  Find files or folders by name
            cat <file>         Print a text file
            stat <path>        Show file details
            mkdir <path>       Create a directory
            touch <path>       Create an empty file
            cp <src> <dest>    Copy a file or directory
            mv <src> <dest>    Move or rename a file or directory
            rm [-r] <path>     Remove a file or directory
            echo ... [> file]  Print text or write it to a file
            clear              Clear the terminal output
            exit               Close the current session
        """.trimIndent()
    )

    private fun lsResult(args: List<String>): TerminalCommandResult {
        val showHidden = args.contains("-a")
        val targetArg = args.firstOrNull { !it.startsWith("-") }
        val target = resolve(targetArg ?: ".")
        if (!target.exists()) {
            return TerminalCommandResult(error = "No such file or directory: ${targetArg ?: "."}")
        }
        if (target.isFile) {
            return TerminalCommandResult(output = target.name)
        }

        val children = target.listFiles().orEmpty()
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))

        if (children.isEmpty()) {
            return TerminalCommandResult(output = "(empty)")
        }

        val output = children.joinToString("\n") { child ->
            if (child.isDirectory) "${child.name}/" else child.name
        }
        return TerminalCommandResult(output = output)
    }

    private fun cdResult(args: List<String>): TerminalCommandResult {
        val destination = resolve(args.firstOrNull() ?: "/")
        if (!destination.exists() || !destination.isDirectory) {
            return TerminalCommandResult(error = "Directory not found: ${args.firstOrNull() ?: "/"}")
        }
        currentDir = destination
        return TerminalCommandResult(output = displayPath(currentDir))
    }

    private fun treeResult(args: List<String>): TerminalCommandResult {
        val target = resolve(args.firstOrNull() ?: ".")
        if (!target.exists()) {
            return TerminalCommandResult(error = "Path not found: ${args.firstOrNull() ?: "."}")
        }
        if (target.isFile) {
            return TerminalCommandResult(output = target.name)
        }

        val lines = mutableListOf(displayPath(target))
        renderTree(target, prefix = "", lines = lines, remaining = 250)
        return TerminalCommandResult(output = lines.joinToString("\n"))
    }

    private fun findResult(args: List<String>): TerminalCommandResult {
        if (args.isEmpty()) {
            return TerminalCommandResult(error = "Usage: find <name> [dir]")
        }
        val needle = args.first().lowercase(Locale.ROOT)
        val start = resolve(args.getOrNull(1) ?: ".")
        if (!start.exists() || !start.isDirectory) {
            return TerminalCommandResult(error = "Directory not found: ${args.getOrNull(1) ?: "."}")
        }

        val matches = mutableListOf<String>()
        start.walkTopDown().forEach { file ->
            if (file == start) return@forEach
            if (file.name.lowercase(Locale.ROOT).contains(needle)) {
                matches += displayPath(file)
            }
        }

        return if (matches.isEmpty()) {
            TerminalCommandResult(output = "No matches.")
        } else {
            TerminalCommandResult(output = matches.joinToString("\n"))
        }
    }

    private fun catResult(args: List<String>): TerminalCommandResult {
        if (args.isEmpty()) return TerminalCommandResult(error = "Usage: cat <file>")
        val file = resolve(args.first())
        if (!file.exists() || !file.isFile) {
            return TerminalCommandResult(error = "File not found: ${args.first()}")
        }
        return TerminalCommandResult(output = file.readText(Charsets.UTF_8))
    }

    private fun statResult(args: List<String>): TerminalCommandResult {
        if (args.isEmpty()) return TerminalCommandResult(error = "Usage: stat <path>")
        val file = resolve(args.first())
        if (!file.exists()) return TerminalCommandResult(error = "Path not found: ${args.first()}")

        val modified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
        val type = if (file.isDirectory) "directory" else "file"
        val size = if (file.isDirectory) file.listFiles()?.size?.toString() ?: "0" else file.length().toString()
        return TerminalCommandResult(
            output = """
                path: ${displayPath(file)}
                type: $type
                size: $size
                modified: $modified
            """.trimIndent()
        )
    }

    private fun mkdirResult(args: List<String>): TerminalCommandResult {
        if (args.isEmpty()) return TerminalCommandResult(error = "Usage: mkdir <path>")
        args.forEach { path ->
            val dir = resolve(path)
            if (!dir.exists() && !dir.mkdirs()) {
                return TerminalCommandResult(error = "Failed to create directory: $path")
            }
        }
        return TerminalCommandResult()
    }

    private fun touchResult(args: List<String>): TerminalCommandResult {
        if (args.isEmpty()) return TerminalCommandResult(error = "Usage: touch <path>")
        args.forEach { path ->
            val file = resolve(path)
            file.parentFile?.mkdirs()
            if (!file.exists() && !file.createNewFile()) {
                return TerminalCommandResult(error = "Failed to create file: $path")
            }
            file.setLastModified(System.currentTimeMillis())
        }
        return TerminalCommandResult()
    }

    private fun copyResult(args: List<String>): TerminalCommandResult {
        if (args.size != 2) return TerminalCommandResult(error = "Usage: cp <src> <dest>")
        val source = resolve(args[0])
        if (!source.exists()) return TerminalCommandResult(error = "Source not found: ${args[0]}")

        val destination = resolve(args[1])
        destination.parentFile?.mkdirs()
        if (source.isDirectory) {
            source.copyRecursively(destination, overwrite = true)
        } else {
            source.copyTo(destination, overwrite = true)
        }
        return TerminalCommandResult()
    }

    private fun moveResult(args: List<String>): TerminalCommandResult {
        if (args.size != 2) return TerminalCommandResult(error = "Usage: mv <src> <dest>")
        val source = resolve(args[0])
        if (!source.exists()) return TerminalCommandResult(error = "Source not found: ${args[0]}")

        val destination = resolve(args[1])
        destination.parentFile?.mkdirs()
        val moved = source.renameTo(destination)
        if (!moved) {
            if (source.isDirectory) {
                source.copyRecursively(destination, overwrite = true)
                source.deleteRecursively()
            } else {
                source.copyTo(destination, overwrite = true)
                source.delete()
            }
        }
        return TerminalCommandResult()
    }

    private fun removeResult(args: List<String>): TerminalCommandResult {
        if (args.isEmpty()) return TerminalCommandResult(error = "Usage: rm [-r] <path>")
        val recursive = args.contains("-r") || args.contains("-rf") || args.contains("-fr")
        val targets = args.filterNot { it.startsWith("-") }
        if (targets.isEmpty()) return TerminalCommandResult(error = "Usage: rm [-r] <path>")

        for (path in targets) {
            val target = resolve(path)
            if (!target.exists()) {
                return TerminalCommandResult(error = "Path not found: $path")
            }
            if (target.isDirectory && !recursive) {
                return TerminalCommandResult(error = "Use `rm -r` to remove directories.")
            }
            val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (!deleted) {
                return TerminalCommandResult(error = "Failed to remove: $path")
            }
        }
        return TerminalCommandResult()
    }

    private fun echoResult(parsed: ParsedCommand): TerminalCommandResult {
        val text = parsed.args.drop(1).joinToString(" ")
        val redirectPath = parsed.redirectPath
        if (redirectPath == null) {
            return TerminalCommandResult(output = text)
        }

        val file = resolve(redirectPath)
        file.parentFile?.mkdirs()
        if (parsed.appendRedirect) {
            file.appendText(text + "\n", Charsets.UTF_8)
        } else {
            file.writeText(text + "\n", Charsets.UTF_8)
        }
        return TerminalCommandResult()
    }

    private fun unsupportedRuntimeResult(command: String): TerminalCommandResult = TerminalCommandResult(
        error = "`$command` is not available in the Android workspace terminal. " +
            "This terminal manages project files only. Use the desktop app or add a remote runner for Node/Python."
    )

    private fun resolve(input: String): File {
        val normalized = input.replace('\\', '/')
        val candidate = if (normalized.startsWith("/")) {
            File(rootDir, normalized.removePrefix("/"))
        } else {
            File(currentDir, normalized)
        }.canonicalFile

        val rootPath = rootDir.path
        val candidatePath = candidate.path
        if (candidatePath != rootPath && !candidatePath.startsWith(rootPath + File.separator)) {
            throw IllegalArgumentException("Path escapes the workspace: $input")
        }
        return candidate
    }

    private fun displayPath(file: File): String {
        val relative = rootDir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        return if (relative.isEmpty()) "/" else "/$relative"
    }

    private fun renderTree(
        directory: File,
        prefix: String,
        lines: MutableList<String>,
        remaining: Int,
    ): Int {
        var budget = remaining
        val children = directory.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))

        for ((index, child) in children.withIndex()) {
            if (budget <= 0) {
                lines += "$prefix..."
                return 0
            }
            val isLast = index == children.lastIndex
            val branch = if (isLast) "`-- " else "|-- "
            lines += prefix + branch + if (child.isDirectory) "${child.name}/" else child.name
            budget--
            if (child.isDirectory) {
                val nextPrefix = prefix + if (isLast) "    " else "|   "
                budget = renderTree(child, nextPrefix, lines, budget)
            }
        }
        return budget
    }

    private fun parse(raw: String): ParsedCommand? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        while (i < raw.length) {
            val ch = raw[i]
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> current.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> flush()
                ch == '>' -> {
                    flush()
                    if (i + 1 < raw.length && raw[i + 1] == '>') {
                        tokens += ">>"
                        i++
                    } else {
                        tokens += ">"
                    }
                }
                else -> current.append(ch)
            }
            i++
        }
        flush()

        if (tokens.isEmpty()) return ParsedCommand(emptyList())
        val redirectIndex = tokens.indexOfFirst { it == ">" || it == ">>" }
        if (redirectIndex == -1) return ParsedCommand(tokens)
        if (redirectIndex == tokens.lastIndex) return null

        return ParsedCommand(
            args = tokens.subList(0, redirectIndex),
            redirectPath = tokens[redirectIndex + 1],
            appendRedirect = tokens[redirectIndex] == ">>",
        )
    }

    private data class ParsedCommand(
        val args: List<String>,
        val redirectPath: String? = null,
        val appendRedirect: Boolean = false,
    )
}
