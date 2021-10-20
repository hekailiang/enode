package org.enodeframework.queue.command

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalCause
import com.google.common.cache.RemovalNotification
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import org.enodeframework.commanding.CommandResult
import org.enodeframework.commanding.CommandReturnType
import org.enodeframework.commanding.CommandStatus
import org.enodeframework.commanding.ICommand
import org.enodeframework.common.exception.DuplicateCommandRegisterException
import org.enodeframework.common.extensions.SystemClock
import org.enodeframework.common.scheduling.IScheduleService
import org.enodeframework.common.scheduling.Worker
import org.enodeframework.common.serializing.ISerializeService
import org.enodeframework.common.utils.ReplyUtil
import org.enodeframework.queue.domainevent.DomainEventHandledMessage
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * @author anruence@gmail.com
 */
class DefaultCommandResultProcessor constructor(
    private val scheduleService: IScheduleService,
    private val serializeService: ISerializeService,
    private val port: Int,
    private val completionSourceTimeout: Int
) : AbstractVerticle(), ICommandResultProcessor {
    private val scanExpireCommandTaskName: String =
        "CleanTimeoutCommandTask_" + SystemClock.now() + Random().nextInt(10000)
    private val commandTaskDict: Cache<String, CommandTaskCompletionSource>
    private val commandExecutedMessageLocalQueue: BlockingQueue<CommandResult>
    private val domainEventHandledMessageLocalQueue: BlockingQueue<DomainEventHandledMessage>
    private val commandExecutedMessageWorker: Worker
    private val domainEventHandledMessageWorker: Worker
    private lateinit var bindAddress: InetSocketAddress
    private lateinit var tcpEventBusBridge: TcpEventBusBridge
    private var started = false

    private fun startServer(port: Int) {
        bindAddress = InetSocketAddress(InetAddress.getLocalHost(), port)
        val address = ReplyUtil.toUri(bindAddress)
        val eb = vertx.eventBus()
        eb.consumer(address) { msg: Message<JsonObject> ->
            processRequestInternal(msg.body())
        }
        val bridgeOptions = BridgeOptions()
        bridgeOptions.addInboundPermitted(PermittedOptions().setAddress(address))
        bridgeOptions.addOutboundPermitted(PermittedOptions().setAddress(address))
        tcpEventBusBridge =
            TcpEventBusBridge.create(vertx, bridgeOptions).listen(port) { res: AsyncResult<TcpEventBusBridge> ->
                if (!res.succeeded()) {
                    logger.error("vertx netServer start failed. port: {}", port, res.cause())
                }
            }
    }

    override fun registerProcessingCommand(
        command: ICommand,
        commandReturnType: CommandReturnType,
        taskCompletionSource: CompletableFuture<CommandResult>
    ) {
        if (commandTaskDict.asMap().putIfAbsent(
                command.id, CommandTaskCompletionSource(
                    command.aggregateRootId,
                    commandReturnType,
                    taskCompletionSource
                )
            ) != null
        ) {
            throw DuplicateCommandRegisterException(
                String.format(
                    "Duplicate processing command registration, type:%s, id:%s",
                    command.javaClass.name,
                    command.id
                )
            )
        }
    }

    override fun start() {
        if (started) {
            return
        }
        startServer(port)
        commandExecutedMessageWorker.start()
        domainEventHandledMessageWorker.start()
        scheduleService.startTask(
            scanExpireCommandTaskName,
            { commandTaskDict.cleanUp() },
            completionSourceTimeout,
            completionSourceTimeout
        )
        started = true
    }

    override fun stop() {
        scheduleService.stopTask(scanExpireCommandTaskName)
        commandExecutedMessageWorker.stop()
        domainEventHandledMessageWorker.stop()
        tcpEventBusBridge.close()
    }

    override fun getBindAddress(): InetSocketAddress {
        return bindAddress
    }

    private fun processRequestInternal(reply: JsonObject) {
        val code = reply.getInteger("code", 0)
        if (code == CommandReturnType.CommandExecuted.value) {
            val result = reply.getJsonObject("commandResult")
            commandExecutedMessageLocalQueue.add(result.mapTo(CommandResult::class.java))
        } else if (code == CommandReturnType.EventHandled.value) {
            val message = reply.getJsonObject("eventHandledMessage")
            domainEventHandledMessageLocalQueue.add(message.mapTo(DomainEventHandledMessage::class.java))
        }
    }

    /**
     * https://stackoverflow.com/questions/10626720/guava-cachebuilder-removal-listener
     * Caches built with CacheBuilder do not perform cleanup and evict values "automatically," or instantly
     * after a value expires, or anything of the sort. Instead, it performs small amounts of maintenance
     * during write operations, or during occasional read operations if writes are rare.
     *
     *
     * The reason for this is as follows: if we wanted to perform Cache maintenance continuously, we would need
     * to create a thread, and its operations would be competing with user operations for shared locks.
     * Additionally, some environments restrict the creation of threads, which would make CacheBuilder unusable in that environment.
     */
    private fun processExecutedCommandMessage(commandResult: CommandResult) {
        val commandTaskCompletionSource = commandTaskDict.asMap()[commandResult.commandId]
        if (commandTaskCompletionSource == null) {
            if (logger.isDebugEnabled) {
                logger.debug(
                    "Command result return, {}, but commandTaskCompletionSource maybe timeout expired.",
                    serializeService.serialize(
                        commandResult
                    )
                )
            }
            return
        }
        if (commandTaskCompletionSource.commandReturnType == CommandReturnType.CommandExecuted) {
            commandTaskDict.asMap().remove(commandResult.commandId)
            if (commandTaskCompletionSource.taskCompletionSource.complete(commandResult)) {
                if (logger.isDebugEnabled) {
                    logger.debug("Command result return CommandExecuted, {}", serializeService.serialize(commandResult))
                }
            }
        } else if (commandTaskCompletionSource.commandReturnType == CommandReturnType.EventHandled) {
            if (CommandStatus.Failed == commandResult.status || CommandStatus.NothingChanged == commandResult.status) {
                commandTaskDict.asMap().remove(commandResult.commandId)
                if (commandTaskCompletionSource.taskCompletionSource.complete(commandResult)) {
                    if (logger.isDebugEnabled) {
                        logger.debug(
                            "Command result return EventHandled, {}",
                            serializeService.serialize(commandResult)
                        )
                    }
                }
            }
        }
    }

    private fun processTimeoutCommand(commandId: String, commandTaskCompletionSource: CommandTaskCompletionSource?) {
        if (commandTaskCompletionSource != null) {
            logger.error("Wait command notify timeout, commandId: {}", commandId)
            val commandResult = CommandResult(
                CommandStatus.Failed,
                commandId,
                commandTaskCompletionSource.aggregateRootId,
                "Wait command notify timeout.",
                String::class.java.name
            )
            // 任务超时失败
            commandTaskCompletionSource.taskCompletionSource.complete(commandResult)
        }
    }

    override fun processFailedSendingCommand(command: ICommand) {
        val commandTaskCompletionSource = commandTaskDict.asMap().remove(command.id)
        if (commandTaskCompletionSource != null) {
            val commandResult = CommandResult(
                CommandStatus.Failed,
                command.id,
                command.aggregateRootId,
                "Failed to send the command.",
                String::class.java.name
            )
            // 发送失败消息
            commandTaskCompletionSource.taskCompletionSource.complete(commandResult)
        }
    }

    private fun processDomainEventHandledMessage(message: DomainEventHandledMessage) {
        val commandTaskCompletionSource = commandTaskDict.asMap()[message.commandId]
        if (commandTaskCompletionSource != null) {
            if (CommandReturnType.EventHandled != commandTaskCompletionSource.commandReturnType) {
                logger.warn("event arrived early than command: {}", serializeService.serialize(message))
                return
            }
            commandTaskDict.asMap().remove(message.commandId)
            val commandResult = CommandResult(
                CommandStatus.Success,
                message.commandId,
                message.aggregateRootId,
                message.commandResult,
                ""
            )
            commandTaskCompletionSource.taskCompletionSource.complete(commandResult)
            if (logger.isDebugEnabled) {
                logger.debug("DomainEvent result return, {}", serializeService.serialize(message))
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultCommandResultProcessor::class.java)
    }

    init {
        commandTaskDict = CacheBuilder.newBuilder()
            .removalListener { notification: RemovalNotification<String, CommandTaskCompletionSource> ->
                if (notification.cause == RemovalCause.EXPIRED) {
                    processTimeoutCommand(notification.key, notification.value)
                }
            }.expireAfterWrite(completionSourceTimeout.toLong(), TimeUnit.MILLISECONDS).build()
        commandExecutedMessageLocalQueue = LinkedBlockingQueue()
        domainEventHandledMessageLocalQueue = LinkedBlockingQueue()
        commandExecutedMessageWorker = Worker("ProcessExecutedCommandMessage") {
            processExecutedCommandMessage(
                commandExecutedMessageLocalQueue.take()
            )
        }
        domainEventHandledMessageWorker = Worker("ProcessDomainEventHandledMessage") {
            processDomainEventHandledMessage(
                domainEventHandledMessageLocalQueue.take()
            )
        }
    }
}