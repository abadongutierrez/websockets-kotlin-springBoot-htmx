package com.jabaddon.examples.websocket_example

import com.fasterxml.jackson.databind.ObjectMapper
import com.samskivert.mustache.Mustache
import com.samskivert.mustache.Template
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.mustache.MustacheResourceTemplateLoader
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@SpringBootApplication
class WebsocketExampleApplication

fun main(args: Array<String>) {
	runApplication<WebsocketExampleApplication>(*args)
}

/**
 * WSConfig. This class is responsible for configuring the WebSocket and the handlers.
 */
@Configuration
@EnableWebSocket
class WSConfig(
	private  val templateRenderer: TemplateRenderer
): WebSocketConfigurer {

	override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
		registry.addHandler(wsHandler(), "/wsHandler")
	}

	@Bean
	fun wsHandler() = WSHandler(templateRenderer)
}

/**
 * TemplateRenderer. This class is responsible for rendering the templates.
 * Using Mustache as the template engine, this class loads the templates from the resources and compiles them.
 * It provides methods to render the templates with the data.
 */
@Component
class TemplateRenderer(
	templateLoader: MustacheResourceTemplateLoader,
	compiler: Mustache.Compiler
) {
	private lateinit var newJoinTemplate: Template
	private lateinit var chatMessageTemplate: Template

	init {
		val chatMessageReader = templateLoader.getTemplate("fragments/chat_message")
		val newJoinReader = templateLoader.getTemplate("fragments/new_join")
		chatMessageTemplate = compiler.compile(chatMessageReader)
		newJoinTemplate = compiler.compile(newJoinReader)
	}

	fun renderChatMessage(message: String): String {
		return chatMessageTemplate.execute(mapOf("message" to message, "color" to "blue"))
	}

	fun renderUserMessage(user: User, message: String): String {
		return chatMessageTemplate.execute(mapOf("message" to "${user.name} (${user.id}): $message", "color" to "blue"))
	}

	fun renderUserLeft(name: String): String {
		return chatMessageTemplate.execute(mapOf("message" to "User $name left", "color" to "red"))
	}

	fun renderUserJoin(name: String): String {
		return newJoinTemplate.execute(mapOf("message" to "User $name joined"))
	}

	fun renderConnectedUsers(users: MutableCollection<User>): String {
		val usersString = users.joinToString(", ") { "${it.name} (${it.id})" }
		return chatMessageTemplate.execute(mapOf("message" to "Connected users: $usersString", "color" to "green"))
	}
}

/**
 * User class. This class represents a user.
 */
class User(val id: Long, val name: String)

/**
 * WSHandler. This class is responsible for handling the WebSocket messages.
 */
class WSHandler(
	private val templateRenderer: TemplateRenderer
): TextWebSocketHandler() {
	private val logger: Logger = LoggerFactory.getLogger(WSHandler::class.java)

	private val sessionList = ConcurrentHashMap<WebSocketSession, User>()
	private var uids = AtomicLong(0)

	override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
		logger.info(message.payload)
		val json = ObjectMapper().readTree(message.payload)
		when (json.get("type").asText()) {
			"chat_message" -> {
				sessionList[session]?.let {
					val userMessage = json.get("message").asText()
					val content = templateRenderer.renderUserMessage(it, userMessage)
					broadcast(content)
				} ?: run {
					logger.info("Session does not exist")
				}
			}
			"chat_new_join" -> {
				val name = json.get("name").asText()
				val user = User(uids.getAndIncrement(), name)
				sessionList.put(session, user)
				broadcast(templateRenderer.renderUserJoin(name))
				broadcast(templateRenderer.renderConnectedUsers(sessionList.values))
				session.sendMessage(TextMessage(templateRenderer.renderChatMessage("Welcome $name")))
			}
			else -> {
				logger.info("Unknown message type")
			}
		}
	}

	override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
		sessionList[session]?.let {
			val content = templateRenderer.renderUserLeft(it.name)
			broadcastOthers(content, session)
			sessionList -= session
		}
	}

	private fun broadcast(message: String) {
		sessionList.keys.forEach {
			it.sendMessage(TextMessage(message))
		}
	}

	private fun broadcastOthers(message: String, session: WebSocketSession) {
		sessionList.keys.filterNot { it == session }.forEach {
			it.sendMessage(TextMessage(message))
		}
	}
}

/**
 * Controller. This class is responsible for handling the HTTP requests.
 */
@Controller
class Controller {
	@GetMapping("/")
	fun index() = "index"
}
