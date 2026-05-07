package com.sketchbook.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * Run an [McpServer] over `System.in` / `System.out`. One JSON-RPC message per line.
 *
 * Keeps the stdio plumbing out of the server class so it stays testable in commonTest.
 */
fun runStdio(
    server: McpServer,
    input: java.io.InputStream = System.`in`,
    output: PrintStream = System.out,
): Unit =
    runBlocking {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        while (true) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            if (line.isBlank()) continue
            val response = server.handle(line) ?: continue
            output.println(response)
            output.flush()
        }
    }
