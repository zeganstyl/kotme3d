package com.kotme

import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.runBlocking
import org.ksdfv.thelema.json.IJsonObject
import org.ksdfv.thelema.json.JSON
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Class in charge of the logic of the chat server.
 * It contains handlers to events and commands to send messages to specific users in the server.
 */
class LocationServer: LocationImp() {
    val scripting = Scripting(this)

    /**
     * Atomic counter used to get unique user-names based on the maxiumum users the server had.
     */
    val usersCounter = AtomicInteger()

    /**
     * A concurrent map associating session IDs to user names.
     */
    val userDescriptions = ConcurrentHashMap<String, UserDesc>()

    /**
     * Associates a session-id to a set of websockets.
     * Since a browser is able to open several tabs and windows with the same cookies and thus the same session.
     * There might be several opened sockets for the same client.
     */
    val userSessions = ConcurrentHashMap<String, MutableList<WebSocketSession>>()

    /**
     * A list of the lastest messages sent to the server, so new members can have a bit context of what
     * other people was talking about before joining.
     */
    val lastMessages = LinkedList<String>()

    var sendAddedObjects = true

    override fun resetScene() {
        super.resetScene()

        sendAddedObjects = false
        addCrab(CrabImp(this, x = -5f, z = 10f))
        addCrab(CrabImp(this, x = 5f, z = 10f))

        userDescriptions.forEach {
            it.value.reset()
            it.value.character = addUserCharacter()
        }

        sendAddedObjects = true

        sendToClient(MessageType.ClientSetLocation, null) {
            writeJson(this)
        }

        userDescriptions.forEach {
            sendCharacterToUser(it.key, it.value)
        }
    }

    fun sendCharacterToUser(user: String, userDesc: UserDesc = userDescriptions[user]!!) {
        sendToClient(MessageType.ClientSetCharacter, user) {
            set("character", userDesc.character.id)
        }
    }

    private fun addCrab(crab: CrabImp) {
        addSceneObject(crab)
        crabs.add(crab)
    }

    override fun addSceneObject(obj: SceneObject) {
        super.addSceneObject(obj)
        if (sendAddedObjects) {
            sendToClient(MessageType.ClientAddObject, null) {
                obj.writeJson(this)
            }
        }
    }

    override fun removeSceneObject(id: Int) {
        super.removeSceneObject(id)
        sendToClient(MessageType.ClientRemoveObject, null) {
            set("id", id)
        }
    }

    override fun printBlock(block: () -> Unit) = scripting.print(block)

    /** @param user if null, message will sent to all users */
    override fun sendToClient(type: Int, user: String?, block: IJsonObject.() -> Unit) {
        runBlocking {
            val text = JSON.printObject {
                set("type", type)
                set("obj") {
                    block(this)
                }
            }

            if (user == null) {
                broadcast(text)
            } else {
                userSessions[user]?.send(Frame.Text(text))
            }
        }
    }

    fun receiveMessage(user: String, messageType: Int, json: IJsonObject) {
        when (messageType) {
            MessageType.ServerEval -> {
                scripting.eval(json.string("code"), user, userDescriptions[user]!!)
            }
            MessageType.ServerRestartLocation -> {
                resetScene()
            }
        }
    }

    /**
     * Handles that a member identified with a session id and a socket joined.
     */
    fun memberJoin(member: String, socket: WebSocketSession) {
        var user = userDescriptions[member]
        if (user == null) {
            user = UserDesc(addUserCharacter(), "user${usersCounter.incrementAndGet()}")
            userDescriptions[member] = user
        }

        // Associates this socket to the member id.
        // Since iteration is likely to happen more frequently than adding new items,
        // we use a `CopyOnWriteArrayList`.
        // We could also control how many sockets we would allow per client here before appending it.
        // But since this is a sample we are not doing it.
        val list = userSessions.computeIfAbsent(member) { CopyOnWriteArrayList() }
        list.add(socket)

        sendToClient(MessageType.ClientSetLocation, member) {
            writeJson(this)
        }

        sendCharacterToUser(member, user)
    }

    fun addUserCharacter(): JonesImp {
        val x = Random.nextFloat() * 5f
        val z = Random.nextFloat() * 5f
        val char = JonesImp(this, x = x, z = z)
        addSceneObject(char)
        return char
    }

    /**
     * Handles a [member] idenitified by its session id renaming [to] a specific name.
     */
    suspend fun memberRenamed(member: String, to: String) {
        userDescriptions[member]?.name = to
    }

    /**
     * Handles that a [member] with a specific [socket] left the server.
     */
    suspend fun memberLeft(member: String, socket: WebSocketSession) {
        // Removes the socket connection for this member
        val connections = userSessions[member]
        connections?.remove(socket)

        // If no more sockets are connected for this member, let's remove it from the server
        // and notify the rest of the users about this event.
        if (connections != null && connections.isEmpty()) {
            val user = userDescriptions[member]
            if (user != null) {
                userDescriptions.remove(member)
                removeSceneObject(user.character.id)
            }
        }
    }

    /**
     * Handles a [message] sent from a [sender] by notifying the rest of the users.
     */
    suspend fun message(sender: String, message: String) {
        // Pre-format the message to be send, to prevent doing it for all the users or connected sockets.
        val name = userDescriptions[sender] ?: sender
        val formatted = "[$name] $message"

        // Sends this pre-formatted message to all the members in the server.
        broadcast(formatted)

        // Appends the message to the list of [lastMessages] and caps that collection to 100 items to prevent
        // growing too much.
        synchronized(lastMessages) {
            lastMessages.add(formatted)
            if (lastMessages.size > 100) {
                lastMessages.removeFirst()
            }
        }
    }

    /**
     * Sends a [message] to all the members in the server, including all the connections per member.
     */
    private suspend fun broadcast(message: String) {
        userSessions.values.forEach { socket ->
            socket.send(Frame.Text(message))
        }
    }

    /**
     * Sends a [message] coming from a [sender] to all the members in the server, including all the connections per member.
     */
    private suspend fun broadcast(sender: String, message: String) {
        val name = userDescriptions[sender] ?: sender
        broadcast("[$name] $message")
    }

    /**
     * Sends a [message] to a list of [this] [WebSocketSession].
     */
    suspend fun List<WebSocketSession>.send(frame: Frame) {
        forEach {
            try {
                it.send(frame.copy())
            } catch (t: Throwable) {
                try {
                    it.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, ""))
                } catch (ignore: ClosedSendChannelException) {
                    // at some point it will get closed
                }
            }
        }
    }
}