package com.kotme

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.sessions.*
import io.ktor.util.*
import io.ktor.websocket.*
import io.ktor.websocket.webSocket
import kotlinx.coroutines.channels.consumeEach
import org.ksdfv.thelema.app.AppListener
import org.ksdfv.thelema.json.JSON
import org.ksdfv.thelema.jvm.JvmApp
import java.time.Duration
import kotlin.script.experimental.api.*

object MainKotmeServer {
    /**
     * This class handles the logic of a [LocationServer].
     * With the standard handlers [LocationServer.memberJoin] or [LocationServer.memberLeft] and operations like
     * sending messages to everyone or to specific people connected to the server.
     */
    private val server: LocationServer = LocationServer()

    @JvmStatic
    fun main(args: Array<String>) {
        val app = JvmApp()

        val console = System.out

        app.addListener(object : AppListener {
            override fun update(delta: Float) {
                server.update(delta)
            }
        })

        Thread {
            try {
                app.startLoop()
            } catch (ex: Exception) {
                System.setOut(console)
                ex.printStackTrace()
            }
        }.start()

        server.resetScene()

        val port = System.getenv("PORT")?.toIntOrNull() ?: 8899
        embeddedServer(Netty, port = port) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(60) // Disabled (null) by default
                timeout = Duration.ofSeconds(15)
                maxFrameSize = Long.MAX_VALUE // Disabled (max value). The connection will be closed if surpassed this length.
                masking = false
            }

            // This enables the use of sessions to keep information between requests/refreshes of the browser.
            install(Sessions) {
                cookie<ChatSession>("SESSION")
            }

            // This adds an interceptor that will create a specific session in each request if no session is available already.
            intercept(ApplicationCallPipeline.Features) {
                if (call.sessions.get<ChatSession>() == null) {
                    call.sessions.set(ChatSession(generateNonce()))
                }
            }

            routing {
                get("/") {
                    call.respondRedirect("/index.html", permanent = true)
                }

                // This defines a websocket `/ws` route that allows a protocol upgrade to convert a HTTP request/response request
                // into a bidirectional packetized connection.
                webSocket("/ws") { // this: WebSocketSession ->

                    // First of all we get the session.
                    val session = call.sessions.get<ChatSession>()

                    // We check that we actually have a session. We should always have one,
                    // since we have defined an interceptor before to set one.
                    if (session == null) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session"))
                        return@webSocket
                    }

                    // We notify that a member joined by calling the server handler [memberJoin]
                    // This allows to associate the session id to a specific WebSocket connection.
                    server.memberJoin(session.id, this)

                    try {
                        // We starts receiving messages (frames).
                        // Since this is a coroutine. This coroutine is suspended until receiving frames.
                        // Once the connection is closed, this consumeEach will finish and the code will continue.
                        incoming.consumeEach { frame ->
                            // Frames can be [Text], [Binary], [Ping], [Pong], [Close].
                            // We are only interested in textual messages, so we filter it.
                            if (frame is Frame.Text) {
                                // Now it is time to process the text sent from the user.
                                // At this point we have context about this connection, the session, the text and the server.
                                // So we have everything we need.

                                val message = JSON.parseObject(frame.readText())
                                message.int("type") {
                                    server.receiveMessage(session.id, message.int("type"), message.obj("obj"))
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    } finally {
                        // Either if there was an error, of it the connection was closed gracefully.
                        // We notify the server that the member left.
                        server.memberLeft(session.id, this)
                    }
                }

                // This defines a block of static resources for the '/' path (since no path is specified and we start at '/')
                static {
                    // This marks index.html from the 'web' folder in resources as the default file to serve.
                    defaultResource("index.html", "web")
                    // This serves files from the 'web' folder in the application resources.
                    resources("web")
                }
            }
        }.start(wait = true)
    }
}