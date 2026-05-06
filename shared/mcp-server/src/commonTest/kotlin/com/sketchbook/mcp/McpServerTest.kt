package com.sketchbook.mcp

import com.sketchbook.core.ProjectId
import com.sketchbook.core.ProjectPath
import com.sketchbook.core.ProjectRow
import com.sketchbook.repo.ActionRecord
import com.sketchbook.repo.JournalEntry
import com.sketchbook.repo.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class McpServerTest {

    private val now = Instant.parse("2026-05-05T12:00:00Z")

    private val sampleRow = ProjectRow(
        id = ProjectId(7),
        name = "kick test",
        path = ProjectPath("Projects/2026/kick test/Project.als"),
        tempo = 124.0,
        trackCount = 12,
        lastSavedLiveVersion = "11.3.20",
        updatedAt = now,
        tags = listOf("drum-loop"),
        colorTag = 4,
    )

    private inner class FakeRepo(private val rows: List<ProjectRow>) : ProjectRepository {
        override fun observeProjects(query: String): Flow<List<ProjectRow>> {
            val filtered = if (query.isBlank()) rows else rows.filter { query in it.name }
            return flowOf(filtered)
        }
        override fun observeProject(id: ProjectId): Flow<ProjectRow?> = flowOf(rows.firstOrNull { it.id == id })
        override suspend fun move(id: ProjectId, newParentDir: String): Result<JournalEntry> = Result.success(stub())
        override suspend fun rename(id: ProjectId, newName: String): Result<JournalEntry> = Result.success(stub())
        override suspend fun archive(id: ProjectId, archived: Boolean): Result<JournalEntry> = Result.success(stub())
        override suspend fun setTags(id: ProjectId, tags: List<String>): Result<JournalEntry> = Result.success(stub())
        private fun stub() = JournalEntry(
            timestamp = now,
            projectId = ProjectId(1),
            action = ActionRecord.Archive(wasArchived = false, isArchived = true),
        )
    }

    private inner class RecordingProposalsWriter : ProposalsWriter {
        var lastActions: List<ProposedAction>? = null
        var lastRationale: String? = null
        override suspend fun write(actions: List<ProposedAction>, rationale: String?): String {
            lastActions = actions
            lastRationale = rationale
            return "2026-05-05T12-00-00_abcd1234"
        }
    }

    private fun makeServer(): Pair<McpServer, RecordingProposalsWriter> {
        val writer = RecordingProposalsWriter()
        val tools = Tools(FakeRepo(listOf(sampleRow)), writer)
        return McpServer(tools) to writer
    }

    @Test
    fun toolsListReturnsAllFourTools() = runTest {
        val (server, _) = makeServer()
        val response = assertNotNull(server.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
        val tools = Json.parseToJsonElement(response).jsonObject["result"]?.jsonObject?.get("tools")?.jsonArray
            ?: error("missing result.tools")
        val names = tools.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(listOf("search_projects", "get_project", "list_recent", "propose_batch"), names)
    }

    @Test
    fun searchProjectsCallReturnsMatches() = runTest {
        val (server, _) = makeServer()
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", "search_projects")
                    put("arguments", buildJsonObject { put("query", "kick") })
                },
            )
        }
        val response = assertNotNull(server.handle(req.toString()))
        val structured = Json.parseToJsonElement(response).jsonObject["result"]?.jsonObject
            ?.get("structuredContent")?.jsonObject
            ?: error("missing structured")
        val match = structured["matches"]!!.jsonArray.single().jsonObject
        assertEquals(7L, match["project_id"]!!.jsonPrimitive.content.toLong())
        assertEquals("kick test", match["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun proposeBatchPersistsThroughWriter() = runTest {
        val (server, writer) = makeServer()
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", "propose_batch")
                    put(
                        "arguments",
                        buildJsonObject {
                            put("rationale", "tidy up untriaged")
                            put(
                                "actions",
                                kotlinx.serialization.json.buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "SetColorTag")
                                            put(
                                                "args",
                                                buildJsonObject {
                                                    put("project_id", 7)
                                                    put("color", 4)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val response = assertNotNull(server.handle(req.toString()))
        val structured = Json.parseToJsonElement(response).jsonObject["result"]?.jsonObject
            ?.get("structuredContent")?.jsonObject
            ?: error("missing structured")
        assertEquals("2026-05-05T12-00-00_abcd1234", structured["proposal_id"]!!.jsonPrimitive.content)
        assertEquals(1, writer.lastActions?.size)
        assertEquals("SetColorTag", writer.lastActions?.first()?.type)
        assertEquals("tidy up untriaged", writer.lastRationale)
    }

    @Test
    fun unknownMethodReturnsJsonRpcError() = runTest {
        val (server, _) = makeServer()
        val response = assertNotNull(server.handle("""{"jsonrpc":"2.0","id":4,"method":"not_a_method"}"""))
        val obj = Json.parseToJsonElement(response).jsonObject
        val err = obj["error"]?.jsonObject ?: error("expected error")
        assertEquals(-32601, err["code"]?.jsonPrimitive?.content?.toInt())
        assertTrue(err["message"]!!.jsonPrimitive.content.contains("not_a_method"))
    }

    @Test
    fun notificationsProduceNoResponse() = runTest {
        val (server, _) = makeServer()
        val resp = server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(null, resp)
    }
}
