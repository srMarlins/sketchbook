package com.sketchbook.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON-RPC 2.0 over stdio MCP server. Implements `initialize`, `tools/list`, and `tools/call`
 * — the surface a Claude Desktop / Claude Code client uses. Notifications (no `id` field) are
 * accepted and discarded.
 *
 * Wire-stable: each method's response shape matches the MCP spec so a client written against the
 * v0.1 Python (FastMCP-backed) server keeps working.
 *
 * Driving I/O is the caller's job; this class is pure parse-dispatch-format. Tests construct
 * server, hand it a request string, and assert on the response string.
 */
class McpServer(
    private val tools: Tools,
    private val serverName: String = "sketchbook",
    private val serverVersion: String = "0.1.0",
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Process a single JSON-RPC request line. Returns the response line, or `null` if the
     * request was a notification (no `id`).
     */
    suspend fun handle(request: String): String? {
        val req = runCatching { json.parseToJsonElement(request).jsonObject }.getOrNull()
            ?: return error(null, code = -32700, message = "parse error")
        val id = req["id"]
        val method = req["method"]?.jsonPrimitive?.contentOrNull()
            ?: return error(id, code = -32600, message = "missing method")
        val params = req["params"] as? JsonObject

        // Notifications: no id, no response.
        val isNotification = id == null

        return try {
            when (method) {
                "initialize" -> if (isNotification) null else success(id, initializeResult())
                "notifications/initialized" -> null
                "tools/list" -> if (isNotification) null else success(id, toolsListResult())
                "tools/call" -> if (isNotification) null else success(id, callToolResult(params))
                else -> if (isNotification) null else error(id, code = -32601, message = "method not found: $method")
            }
        } catch (t: Throwable) {
            if (isNotification) null else error(id, code = -32603, message = t.message ?: t::class.simpleName.orEmpty())
        }
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", "2025-03-26")
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") {
            put("name", serverName)
            put("version", serverVersion)
        }
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        put("tools", buildJsonArray {
            add(toolDef(
                name = "search_projects",
                description = "Full-text search the catalog by name/plugins/sample filenames. Returns matches ordered by last-modified desc.",
                schema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "string"); put("description", "FTS query. Empty matches everything.") }
                        putJsonObject("limit") { put("type", "integer"); put("description", "Max rows; default 50.") }
                    }
                },
            ))
            add(toolDef(
                name = "get_project",
                description = "Fetch a single project's row by primary key.",
                schema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("project_id") { put("type", "integer") }
                    }
                    put("required", buildJsonArray { add(JsonPrimitive("project_id")) })
                },
            ))
            add(toolDef(
                name = "list_recent",
                description = "Recently-modified projects, newest first.",
                schema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("limit") { put("type", "integer"); put("description", "Max rows; default 50.") }
                    }
                },
            ))
            add(toolDef(
                name = "propose_batch",
                description = "Submit a proposed batch of write actions for the user to approve. Does not execute.",
                schema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("actions") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("type") { put("type", "string") }
                                    putJsonObject("args") { put("type", "object") }
                                }
                                put("required", buildJsonArray { add(JsonPrimitive("type")); add(JsonPrimitive("args")) })
                            }
                        }
                        putJsonObject("rationale") { put("type", "string") }
                    }
                    put("required", buildJsonArray { add(JsonPrimitive("actions")) })
                },
            ))
        })
    }

    private fun toolDef(name: String, description: String, schema: JsonObject): JsonObject =
        buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", schema)
        }

    private suspend fun callToolResult(params: JsonObject?): JsonObject {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull()
            ?: throw IllegalArgumentException("tools/call requires 'name'")
        val arguments = (params["arguments"] as? JsonObject) ?: buildJsonObject { }
        val result: Any = when (name) {
            "search_projects" -> tools.searchProjects(json.decodeFromJsonElement(SearchProjectsArgs.serializer(), arguments))
            "get_project" -> tools.getProject(json.decodeFromJsonElement(GetProjectArgs.serializer(), arguments))
                ?: buildJsonObject { put("error", "not found") }
            "list_recent" -> tools.listRecent(json.decodeFromJsonElement(ListRecentArgs.serializer(), arguments))
            "propose_batch" -> tools.proposeBatch(json.decodeFromJsonElement(ProposeBatchArgs.serializer(), arguments))
            else -> throw IllegalArgumentException("unknown tool: $name")
        }
        val payload = when (result) {
            is JsonObject -> result
            is SearchResult -> json.encodeToJsonElement(SearchResult.serializer(), result).jsonObject
            is ProjectDetail -> json.encodeToJsonElement(ProjectDetail.serializer(), result).jsonObject
            is ProposeBatchResult -> json.encodeToJsonElement(ProposeBatchResult.serializer(), result).jsonObject
            else -> error("unhandled result type: ${result::class}")
        }
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", payload.toString())
                })
            })
            put("structuredContent", payload)
            put("isError", false)
        }
    }

    private fun success(id: kotlinx.serialization.json.JsonElement?, result: JsonObject): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("result", result)
        }
        return obj.toString()
    }

    private fun error(id: kotlinx.serialization.json.JsonElement?, code: Int, message: String): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonPrimitive(null as String?))
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
        return obj.toString()
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
}
