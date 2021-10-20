package org.enodeframework.queue.command

import org.enodeframework.commanding.CommandResult
import org.enodeframework.commanding.CommandReturnType
import org.enodeframework.commanding.ICommand
import org.enodeframework.commanding.ICommandService
import org.enodeframework.common.serializing.ISerializeService
import org.enodeframework.common.utils.Assert
import org.enodeframework.common.utils.ReplyUtil
import org.enodeframework.queue.ISendMessageService
import org.enodeframework.queue.QueueMessage
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * @author anruence@gmail.com
 */
class DefaultCommandService(
    private val topic: String,
    private val tag: String,
    private val commandResultProcessor: ICommandResultProcessor,
    private val sendMessageService: ISendMessageService,
    private val serializeService: ISerializeService,
) : ICommandService {
    override fun sendAsync(command: ICommand): CompletableFuture<Boolean> {
        return sendMessageService.sendMessageAsync(buildCommandMessage(command, false))
    }

    override fun send(command: ICommand): Boolean {
        return this.sendAsync(command).join()
    }

    override fun executeAsync(command: ICommand): CompletableFuture<CommandResult> {
        return executeAsync(command, CommandReturnType.CommandExecuted)
    }

    override fun executeAsync(
        command: ICommand,
        commandReturnType: CommandReturnType
    ): CompletableFuture<CommandResult> {
        val taskCompletionSource = CompletableFuture<CommandResult>()
        try {
            Assert.nonNull(commandResultProcessor, "commandResultProcessor")
            commandResultProcessor.registerProcessingCommand(command, commandReturnType, taskCompletionSource)
            val sendMessageAsync = sendMessageService.sendMessageAsync(buildCommandMessage(command, true))
            sendMessageAsync.exceptionally { ex: Throwable ->
                commandResultProcessor.processFailedSendingCommand(command)
                taskCompletionSource.completeExceptionally(ex)
                null
            }
        } catch (ex: Exception) {
            taskCompletionSource.completeExceptionally(ex)
        }
        return taskCompletionSource
    }

    override fun execute(command: ICommand): CommandResult {
        return this.executeAsync(command).join()
    }

    override fun execute(command: ICommand, commandReturnType: CommandReturnType): CommandResult {
        return this.executeAsync(command, commandReturnType).join()
    }

    protected fun buildCommandMessage(command: ICommand, needReply: Boolean): QueueMessage {
        Assert.nonNull(command.aggregateRootId, "aggregateRootId")
        Assert.nonNull(topic, "topic")
        val commandData = serializeService.serialize(command)
        val commandMessage = CommandMessage()
        if (needReply) {
            commandMessage.replyAddress = ReplyUtil.toUri(commandResultProcessor.bindAddress)
        }
        commandMessage.commandData = commandData
        commandMessage.commandType = command.javaClass.name
        val messageData = serializeService.serialize(commandMessage)
        val queueMessage = QueueMessage()
        queueMessage.topic = topic
        queueMessage.tag = tag
        queueMessage.body = messageData
        queueMessage.routeKey = command.aggregateRootId
        val key = String.format(
            "%s%s", command.id, Optional.ofNullable(command.aggregateRootId)
                .map { x: String -> "_cmd_agg_$x" }.orElse("")
        )
        queueMessage.key = key
        return queueMessage
    }
}