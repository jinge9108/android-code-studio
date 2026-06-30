package com.tom.rv2ide.artificial.services

import android.app.Activity
import android.net.LocalServerSocket
import android.net.LocalSocket
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.lookup.Lookup
import kotlinx.coroutines.*
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * Socket server for local agent integration.
 * Listens on the abstract UNIX domain socket "com.tom.rv2ide.agent.socket".
 */
class AgentSocketServer {

    private val log = LoggerFactory.getLogger(AgentSocketServer::class.java)
    private var serverSocket: LocalServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (serverJob != null) return
        serverJob = scope.launch {
            try {
                // Creates a UNIX domain socket in the abstract namespace
                serverSocket = LocalServerSocket("com.tom.rv2ide.agent.socket")
                log.info("Agent socket server successfully started on: com.tom.rv2ide.agent.socket")
                
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                log.error("Error in AgentSocketServer socket loop", e)
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            log.error("Failed to close socket server", e)
        }
        serverSocket = null
    }

    private fun handleClient(socket: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = PrintWriter(socket.outputStream, true)
            
            while (true) {
                val line = reader.readLine() ?: break
                if (line.trim().isEmpty()) continue
                
                val response = try {
                    val request = JSONObject(line)
                    runBlocking { handleRequest(request) }
                } catch (e: Exception) {
                    JSONObject().apply {
                        put("status", "error")
                        put("message", "Malformed JSON request: ${e.message}")
                    }
                }
                writer.println(response.toString())
            }
        } catch (e: Exception) {
            log.error("Exception handling client connection", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignored
            }
        }
    }

    private suspend fun handleRequest(request: JSONObject): JSONObject {
        val action = request.optString("action")
        val response = JSONObject().apply { put("action", action) }

        return try {
            when (action) {
                "open_file" -> {
                    val path = request.getString("path")
                    val line = request.optInt("line", -1)
                    val file = File(path)
                    
                    if (!file.exists()) {
                        response.put("status", "error")
                        response.put("message", "File does not exist: $path")
                    } else {
                        val opened = withContext(Dispatchers.Main) {
                            val activity = IDEApplication.instance.getCurrentActivity()
                            if (activity is EditorHandlerActivity) {
                                val selection = if (line > 0) Range(line - 1, 0, line - 1, 0) else null
                                activity.openFile(file, selection)
                                true
                            } else {
                                false
                            }
                        }
                        if (opened) {
                            response.put("status", "success")
                        } else {
                            response.put("status", "error")
                            response.put("message", "No active editor activity found")
                        }
                    }
                }
                "get_active_file" -> {
                    val activePath = withContext(Dispatchers.Main) {
                        val activity = IDEApplication.instance.getCurrentActivity()
                        if (activity is EditorHandlerActivity) {
                            activity.getCurrentEditor()?.editor?.file?.absolutePath
                        } else {
                            null
                        }
                    }
                    response.put("status", "success")
                    response.put("path", activePath ?: JSONObject.NULL)
                }
                "trigger_build" -> {
                    val tasks = request.optJSONArray("tasks")
                    val taskList = mutableListOf<String>()
                    if (tasks != null) {
                        for (i in 0 until tasks.length()) {
                            taskList.add(tasks.getString(i))
                        }
                    }
                    if (taskList.isEmpty()) {
                        taskList.add("assembleDebug")
                    }
                    
                    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
                    if (buildService != null) {
                        buildService.executeTasks(*taskList.toTypedArray())
                        response.put("status", "success")
                        response.put("message", "Build task started: $taskList")
                    } else {
                        response.put("status", "error")
                        response.put("message", "BuildService not available")
                    }
                }
                "cancel_build" -> {
                    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
                    if (buildService != null) {
                        buildService.cancelCurrentBuild()
                        response.put("status", "success")
                    } else {
                        response.put("status", "error")
                        response.put("message", "BuildService not available")
                    }
                }
                "is_building" -> {
                    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
                    val isBuilding = buildService?.isBuildInProgress ?: false
                    response.put("status", "success")
                    response.put("is_building", isBuilding)
                }
                else -> {
                    response.put("status", "error")
                    response.put("message", "Unknown action: $action")
                }
            }
            response
        } catch (e: Exception) {
            response.put("status", "error")
            response.put("message", "Internal server error: ${e.message}")
            response
        }
    }
}
