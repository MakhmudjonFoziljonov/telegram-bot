package bot

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.send.*
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.meta.bots.AbsSender
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue


private const val CALLBACK_LANGUAGE_COUNT_PREFIX = "LANG_COUNT_"
private const val CALLBACK_LANGUAGE_SELECT_PREFIX = "LANG_SELECT_"
private const val CALLBACK_LANGUAGE_CONFIRM = "LANG_CONFIRM"
private const val START = "/start"
private const val BEGIN = "/begin"
private const val END = "/end"
private const val HELP = "/help"
private const val FOLDER_NAME = "attachments"

private val waitingQueues: ConcurrentHashMap<Language, ConcurrentLinkedQueue<String>> = ConcurrentHashMap()
private val pendingPhoneChanges: ConcurrentHashMap<String, String> = ConcurrentHashMap()

private val operatorLanguageSelection = mutableMapOf<String, OperatorLanguageState>()


interface UserService {
    fun handleNewUser(chatId: String, text: String, message: Message)
    fun handleTextMessage(user: User, text: String, chatId: String, messageId: String, replyToMessageId: String?)
    fun saveUser(chatId: String, name: String)
    fun saveOperatorUserRelationIfNotExists(operatorChatId: String, userChatId: String)
    fun tryConnectNextUserToOperator(operatorChatId: String)
    fun updateUserPhoneNumber(chatId: String, phoneNumber: String)
    fun normalizePhoneNumber(phoneNumber: String): String
    fun handlePhoto(user: User, message: Message, messageId: String)
    fun handleVideo(user: User, message: Message, messageId: String)
    fun handleDocument(user: User, message: Message, messageId: String)
    fun handleVoice(user: User, message: Message)
    fun handleSticker(user: User, message: Message)
    fun handleVideoNote(user: User, message: Message)
    fun handleEditedMessage(user: User, editedMessage: Message, editedMessageId: String)
}

interface OperatorService {
    fun handleTextMessage(operator: User, text: String, messageId: String, replyToMessageId: String?)
    fun saveLanguages(chatId: String, languages: Set<Language>)
    fun handlePhoto(operator: User, message: Message, replyMessageId: String?)
    fun handleVideo(operator: User, message: Message)
    fun handleDocument(operator: User, message: Message)
    fun handleVoice(operator: User, message: Message)
    fun handleSticker(operator: User, message: Message)
    fun handleVideoNote(operator: User, message: Message)
    fun handleEditedMessage(operator: User, editedMessage: Message, editedMessageId: String)
}

interface CallBackQueryService {
    fun handleOperatorCallbackQuery(callbackQuery: CallbackQuery, mainLanguage: Language)
    fun handleUserCallbackQuery(callbackQuery: CallbackQuery)
}

interface MessageSendingService {
    fun sendMessageWithContactButton(chatId: String, text: String)
    fun sendMessage(chatId: String, text: String)
    fun sendMessageToOperator(operatorChatId: String, text: String): String
    fun sendEndedOperatorsDetail(operatorChatId: String, userChatId: String, language: Language)
    fun sendMessageWithBeginBtn(beginBtn: ReplyKeyboardMarkup, operatorId: String, text: String)
    fun sendLocalizedMessage(
        chatId: String,
        message: BotMessage,
        language: Language
    )

    fun sendLocalizedMessage(
        chatId: String,
        message: BotMessage,
        language: Language,
        replyKeyboardMarkup: ReplyKeyboardMarkup? = null
    )

    fun sendMessageEndButton(operatorChatId: String, operatorLanguage: Language)
    fun sendUsersDetail(operatorChatIdV2: String, userChatId: String, language: Language)
    fun sendOperatorsDetail(operatorChatIdV2: String, userChatId: String, language: Language)
    fun sendEndButtonToUser(userChatId: String, language: Language)
    fun sendShowLanguageCountSelection(chatId: String, language: Language)
    fun sendEndedUsersDetail(activeOperator: String, userChatId: String, language: Language)
    fun sendMessageAndReplyMessageIfExists(chatId: String, text: String, replyToMessageId: String?): String
    fun sendRemoveInlineKeyboard(chatId: Long, messageId: Int)
    fun sendContact(chatId: Long, lang: String)
    fun sendContactToRegularUser(chatId: Long, lang: String)
    fun sendContactAnswerTextToUser(lang: String): String
    fun sendProcessLanguageSelection(chatId: Long, callBackData: String)
    fun sendShowLanguageSelection(chatId: String, requiredCount: Int, language: Language)
    fun sendUpdateLanguageSelection(chatId: String, messageId: Int, requiredCount: Int, language: Language)
    fun sendContactChangeInlineKeyboard(chatId: String, text: String, operatorLanguage: String)
    fun sendPhoto(chatId: String, fileId: String?): String
    fun sendVideo(chatId: String, fileId: String?): String
    fun sendDocument(chatId: String, fileId: String?): String
    fun sendVoice(chatId: String, fileId: String?): String
    fun sendSticker(chatId: String, fileId: String?)
    fun sendVideoNote(chatId: String, fileId: String?): String
    fun sendEditedMessage(chatId: String, messageId: Int, text: String)
    fun sendFileToTelegram(file: GetFile): org.telegram.telegrambots.meta.api.objects.File

}

interface LanguageService {
    fun updateOperatorLanguage(user: User, lang: String)
    fun toLanguage(code: String): Language
}

interface UserComponentsService {
    fun contactButton(): ReplyKeyboardMarkup
    fun beginBtn(): ReplyKeyboardMarkup
    fun showLanguageCountSelection(chatId: String, language: Language): InlineKeyboardMarkup
    fun endBtn(): ReplyKeyboardMarkup
    fun userEndBtn(): ReplyKeyboardMarkup
    fun startBtn(): ReplyKeyboardMarkup
    fun changeLanguageInlineKeyboard(): InlineKeyboardMarkup
    fun shareContactBtn(): ReplyKeyboardMarkup
    fun saveLanguage(message: SendMessage, language: String)
    fun contactChangeInlineKeyboard(language: String): InlineKeyboardMarkup?
}

interface NotificationService {
    fun notifyAndUpdateSessions(operatorId: String, operatorLanguage: String)
    fun notifyOperatorOnWorkEnd(operatorChatId: String)
    fun notifyOperatorSelectLanguage(operatorChatId: String)
    fun notifyOperatorOnWorkStart(chatId: String)
    fun notifyClientOnOperatorJoin(userChatId: String)
}

interface QueueService {
    fun enqueueUser(language: Language, userChatId: String): Int
    fun dequeueUser(language: Language): String?
    fun isUserInQueue(language: Language, userChatId: String): Boolean
    fun removeFromQueue(language: Language, userChatId: String): Boolean
    fun addPendingMessage(userChatId: String, text: String)
    fun deliverPendingMessagesToOperator(operatorChatId: String, userChatId: String, messageId: String)
    fun getPendingPhoneChange(chatId: String): String?
    fun removePendingPhoneChange(chatId: String)
    fun addPendingPhoneChange(chatId: String, normalizePhoneNumber: String)
}

fun interface MessageMappingService {
    fun messageMappingSave(
        operatorChatId: String,
        userChatId: String,
        operatorMessageId: String,
        message: String,
        userMessageId: String,
        file: File?,
    )
}

@Service
class LanguageServiceImpl(
    private val userRepository: UserRepository,
) : LanguageService {

    override fun updateOperatorLanguage(user: User, lang: String) {
        user.language = toLanguage(lang)
        userRepository.save(user)
    }

    override fun toLanguage(code: String): Language =
        when (code) {
            "ENG" -> Language.ENG
            "RUS" -> Language.RUS
            else -> Language.UZB
        }
}

@Service
class CallBackQueryImpl(
    private val queueService: QueueService,
    private val userService: UserService,
    private val userRepository: UserRepository,
    private val messageSendingService: MessageSendingService,
    private val operatorService: OperatorService
) : CallBackQueryService {

    override fun handleOperatorCallbackQuery(callbackQuery: CallbackQuery, mainLanguage: Language) {
        val callBackData = callbackQuery.data
        val chatId = callbackQuery.from.id.toString()
        val messageId = callbackQuery.message?.messageId ?: return

        when {
            callBackData.startsWith(CALLBACK_LANGUAGE_COUNT_PREFIX) -> {
                val count = callBackData.removePrefix(CALLBACK_LANGUAGE_COUNT_PREFIX).toInt()
                operatorLanguageSelection[chatId] = OperatorLanguageState(requiredCount = count)
                messageSendingService.sendRemoveInlineKeyboard(chatId.toLong(), messageId)
                messageSendingService.sendShowLanguageSelection(chatId, count, mainLanguage)

            }

            callBackData.startsWith(CALLBACK_LANGUAGE_SELECT_PREFIX) -> {
                val langName = callBackData.removePrefix(CALLBACK_LANGUAGE_SELECT_PREFIX)
                val language = Language.valueOf(langName)
                val state = operatorLanguageSelection[chatId]!!

                if (state.selectedLanguages.contains(language)) {
                    state.selectedLanguages.remove(language)

                } else {
                    if (state.selectedLanguages.size < state.requiredCount) {
                        state.selectedLanguages.add(language)
                    }
                }
                messageSendingService.sendUpdateLanguageSelection(chatId, messageId, state.requiredCount, mainLanguage)
            }

            callBackData == CALLBACK_LANGUAGE_CONFIRM -> {
                val state = operatorLanguageSelection[chatId]!!

                if (state.selectedLanguages.size == state.requiredCount) {
                    operatorService.saveLanguages(chatId, state.selectedLanguages)
                    messageSendingService.sendRemoveInlineKeyboard(chatId.toLong(), messageId)
                    messageSendingService.sendMessage(
                        chatId,
                        BotMessage.OPERATOR_LANGUAGES_SAVED.getText(mainLanguage)
                    )

                    operatorLanguageSelection.remove(chatId)
                } else {
                    messageSendingService.sendMessage(
                        chatId,
                        BotMessage.OPERATOR_SELECT_MORE_LANGUAGES.getText(
                            Language.UZB,
                            "count" to state.selectedLanguages.size.toString(),
                            "total" to state.requiredCount.toString()
                        )
                    )
                }
            }

            else -> {
                messageSendingService.sendRemoveInlineKeyboard(chatId.toLong(), messageId)
                messageSendingService.sendProcessLanguageSelection(chatId.toLong(), callBackData)
            }
        }
    }

    override fun handleUserCallbackQuery(callbackQuery: CallbackQuery) {
        val callBackData = callbackQuery.data
        val chatId = callbackQuery.from.id
        val chatIdStr = callbackQuery.from.id.toString()
        val messageId = callbackQuery.message?.messageId ?: return

        when (callBackData) {
            "YES_BUTTON" -> {
                val newPhone = queueService.getPendingPhoneChange(chatIdStr)

                if (newPhone != null) {
                    userService.updateUserPhoneNumber(chatIdStr, newPhone)
                    queueService.removePendingPhoneChange(chatIdStr)
                    messageSendingService.sendRemoveInlineKeyboard(chatId, messageId)

                    val user = userRepository.findByChatIdAndDeletedFalse(chatIdStr)
                    if (user != null) {
                        messageSendingService.sendLocalizedMessage(
                            chatIdStr,
                            BotMessage.PHONE_CHANGED_SUCCESS,
                            user.language
                        )
                    }
                } else {
                    messageSendingService.sendRemoveInlineKeyboard(chatId, messageId)
                    messageSendingService.sendMessage(chatIdStr, "Xatolik yuz berdi. Qaytadan contact yuboring.")
                }
            }

            "NO_BUTTON" -> {
                queueService.removePendingPhoneChange(chatIdStr)
                messageSendingService.sendRemoveInlineKeyboard(chatId, messageId)
                val user = userRepository.findByChatIdAndDeletedFalse(chatIdStr)
                if (user != null) {
                    messageSendingService.sendLocalizedMessage(
                        chatIdStr,
                        BotMessage.PHONE_CHANGE_CANCELLED,
                        user.language
                    )
                }
            }

//            "CHANGE_UZB_BUTTON" ->{
//            messageSendingService.sendRemoveInlineKeyboard(chatId, messageId)
//                val user = userRepository.findByChatIdAndDeletedFalse(chatId.toString()) ?: throw UserNotFoundException()
//                user.language = Language.UZB
//                userRepository.save(user)
//
//                messageSendingService.sendLocalizedMessage(chatIdStr, BotMessage.CHANGE_LANGUAGE_ANSWER, Language.UZB)
//            }
//            "CHANGE_RUS_BUTTON" ->{
//            messageSendingService.sendRemoveInlineKeyboard(chatId, messageId)
//                val user = userRepository.findByChatIdAndDeletedFalse(chatId.toString()) ?: throw UserNotFoundException()
//                user.language = Language.RUS
//                userRepository.save(user)
//
//                messageSendingService.sendLocalizedMessage(chatIdStr, BotMessage.CHANGE_LANGUAGE_ANSWER, Language.RUS)
//            }
//            "CHANGE_ENG_BUTTON" ->{
//            messageSendingService.sendRemoveInlineKeyboard(chatId, messageId)
//                val user = userRepository.findByChatIdAndDeletedFalse(chatId.toString()) ?: throw UserNotFoundException()
//                user.language = Language.ENG
//                userRepository.save(user)
//
//                messageSendingService.sendLocalizedMessage(chatIdStr, BotMessage.CHANGE_LANGUAGE_ANSWER, Language.RUS)
//            }

            else -> {
                messageSendingService.sendRemoveInlineKeyboard(chatId, messageId)
                messageSendingService.sendProcessLanguageSelection(chatId, callBackData)
            }
        }
    }

}

@Service
class MessageMappingServiceImpl(
    private val messageMappingRepository: MessageMappingRepository
) : MessageMappingService {
    override fun messageMappingSave(
        operatorChatId: String,
        userChatId: String,
        operatorMessageId: String,
        message: String,
        userMessageId: String,
        file: File?
    ) {
        val messageMapping = MessageMapping(
            operatorChatId,
            userChatId,
            operatorMessageId,
            message,
            userMessageId,
            file
        )
        messageMappingRepository.save(messageMapping)
    }

}

@Service
class QueueServiceImpl(
    private val messageSendingService: MessageSendingService,
    private val pendingMessagesRepository: PendingMessagesRepository,
    private val messageMappingRepository: MessageMappingRepository
) : QueueService {

    private val log = LoggerFactory.getLogger(QueueServiceImpl::class.java)

    override fun enqueueUser(language: Language, userChatId: String): Int {
        val queue = waitingQueues.computeIfAbsent(language) { ConcurrentLinkedQueue() }
        if (!queue.contains(userChatId)) queue.offer(userChatId)
        var pos = 1
        for (id in queue) {
            if (id == userChatId) return pos
            pos++
        }
        return pos
    }

    override fun dequeueUser(language: Language): String? = waitingQueues[language]?.poll()

    override fun isUserInQueue(language: Language, userChatId: String): Boolean =
        waitingQueues[language]?.contains(userChatId) ?: false

    override fun removeFromQueue(language: Language, userChatId: String): Boolean {
        val queue = waitingQueues[language] ?: return false
        return queue.remove(userChatId)
    }

    override fun addPendingMessage(userChatId: String, text: String) {
        pendingMessagesRepository.save(
            PendingMessages(
                userChatId = userChatId,
                message = text
            )
        )
    }

    override fun deliverPendingMessagesToOperator(operatorChatId: String, userChatId: String, messageId: String) {
        val pendingMessages = pendingMessagesRepository.findByUserChatIdAndStatus(userChatId, MessageStatus.PENDING)
        if (pendingMessages.isEmpty()) return

        pendingMessages.forEach { msg ->
            try {
                val sendMessageId = messageSendingService.sendMessageToOperator(operatorChatId, msg.message)
                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId,
                        userChatId,
                        messageId,
                        msg.message,
                        sendMessageId
                    )
                )
            } catch (e: TelegramApiException) {
                log.error("Failed to deliver pending message from $userChatId to operator $operatorChatId", e)
            }
        }
        pendingMessagesRepository.updatePendingMessagesByChatId(userChatId, MessageStatus.DELIVERED)

    }

    override fun getPendingPhoneChange(chatId: String): String? {
        return pendingPhoneChanges[chatId]
    }

    override fun removePendingPhoneChange(chatId: String) {
        pendingPhoneChanges.remove(chatId)
    }

    override fun addPendingPhoneChange(chatId: String, normalizePhoneNumber: String) {
        pendingPhoneChanges[chatId] = normalizePhoneNumber
    }
}

@Service
class MessageSendingServiceImpl(
    private val userRepository: UserRepository,
    private val userComponentService: UserComponentsService,
    private val messageMapRepository: MessageMappingRepository,
    private val languageService: LanguageService,
    private val messageMappingRepository: MessageMappingRepository,
    @Lazy private val absSender: AbsSender
) : MessageSendingService {

    private val log = LoggerFactory.getLogger(MessageSendingServiceImpl::class.java)

    override fun sendMessageWithContactButton(chatId: String, text: String) {
        val message = SendMessage(chatId, text)
        message.replyMarkup = userComponentService.contactButton()

        try {
            absSender.execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error sending message with contact button to $chatId", e)
        }
    }

    override fun sendMessage(chatId: String, text: String) {
        val message = SendMessage(chatId, text)
        try {
            absSender.execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error sending message to $chatId", e)
        }
    }

    override fun sendMessageToOperator(operatorChatId: String, text: String): String {
        try {
            return absSender.execute(SendMessage(operatorChatId, text)).messageId.toString()
        } catch (e: TelegramApiException) {
            log.error("Error sending operator", e)
            return ""
        }
    }

    override fun sendEndedOperatorsDetail(operatorChatId: String, userChatId: String, language: Language) {
        val user = userRepository.findByChatIdAndDeletedFalse(operatorChatId) ?: throw OperatorNotFoundException()
        val name = user.name
        val sendMessage = SendMessage()
        sendMessage.chatId = userChatId
        sendMessage.text =
            name + " ${BotMessage.OPERATORS_NAME.getText(language)}" + " ${
                BotMessage.OPERATORS_ENDED_NAME.getText(
                    language
                )
            }"
        try {
            absSender.execute(sendMessage)
        } catch (e: TelegramApiException) {
            log.warn("Error sending message to $userChatId: $e sendEndedOperatorsDetail ")
        }
    }

    override fun sendMessageWithBeginBtn(beginBtn: ReplyKeyboardMarkup, operatorId: String, text: String) {
        val sendMessage = SendMessage()
        sendMessage.chatId = operatorId
        sendMessage.text = text
        sendMessage.replyMarkup = beginBtn
        try {
            absSender.execute(sendMessage)
        } catch (e: TelegramApiException) {
            log.error("Error sending message to $operatorId", e)
        }
    }

    override fun sendLocalizedMessage(chatId: String, message: BotMessage, language: Language) {
        try {
            absSender.execute(SendMessage(chatId, message.getText(language)))
        } catch (ex: TelegramApiException) {
            log.error("Error sending message ", ex)
        }
    }

    override fun sendLocalizedMessage(
        chatId: String,
        message: BotMessage,
        language: Language,
        replyKeyboardMarkup: ReplyKeyboardMarkup?
    ) {
        val text = message.getText(language)

        val sendMessage = SendMessage().apply {
            this.chatId = chatId
            this.text = text
            if (replyKeyboardMarkup != null) {
                this.replyMarkup = replyKeyboardMarkup
            }
        }

        try {
            absSender.execute(sendMessage)
        } catch (ex: TelegramApiException) {
            ex.printStackTrace()
        }
    }

    override fun sendMessageEndButton(operatorChatId: String, operatorLanguage: Language) {
        val endBtn = userComponentService.endBtn()
        val sendMessage = SendMessage()
        sendMessage.chatId = operatorChatId
        sendMessage.text = BotMessage.OPERATOR_TEXT_END_WORK.getText(operatorLanguage)
        sendMessage.replyMarkup = endBtn
        try {
            absSender.execute(sendMessage)
        } catch (e: TelegramApiException) {
            log.error("Error sending message to $operatorLanguage", e)
        }
    }

    override fun sendUsersDetail(operatorChatIdV2: String, userChatId: String, language: Language) {
        val user = userRepository.findByChatIdAndDeletedFalse(operatorChatIdV2) ?: throw OperatorNotFoundException()
        val name = user.name
        val sendMessage = SendMessage()
        sendMessage.chatId = operatorChatIdV2
        sendMessage.text = name + " ${BotMessage.USERS_NAME.getText(language)}"
        try {
            absSender.execute(sendMessage)
        } catch (e: TelegramApiException) {
            log.warn("Error sending message to $operatorChatIdV2: $e usersDetail ")
        }
    }

    override fun sendOperatorsDetail(operatorChatIdV2: String, userChatId: String, language: Language) {
        val user = userRepository.findByChatIdAndDeletedFalse(operatorChatIdV2) ?: throw OperatorNotFoundException()
        val name = user.name
        val sendMessage = SendMessage()
        sendMessage.chatId = userChatId
        sendMessage.text =
            name + " ${BotMessage.OPERATORS_NAME.getText(language)}" + " ${
                BotMessage.USERS_NAME.getText(
                    language
                )
            }"
        try {
            absSender.execute(sendMessage)
        } catch (e: TelegramApiException) {
            log.warn("Error sending message to $userChatId: $e sendOperatorsDetail ")
        }
    }

    override fun sendEndButtonToUser(userChatId: String, language: Language) {
        val userEndBtn = userComponentService.userEndBtn()
        val sendMessage = SendMessage()
        sendMessage.chatId = userChatId
        sendMessage.text = BotMessage.OPERATOR_JOINED.getText(language)
        sendMessage.replyMarkup = userEndBtn
        try {
            absSender.execute(sendMessage)
        } catch (e: TelegramApiException) {
            log.error("Error sending message to $userChatId: $e sendEndButtonToUser ")
        }
    }

    override fun sendShowLanguageCountSelection(
        chatId: String,
        language: Language
    ) {
        val inline = userComponentService.showLanguageCountSelection(chatId, language)

        val message = SendMessage(
            chatId,
            BotMessage.OPERATOR_SELECT_LANGUAGE_COUNT.getText(language)
        )
        message.replyMarkup = inline
        try {
            absSender.execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error sending message to $chatId: $e sendShowLanguageCountSelection ")
        }
    }

    override fun sendEndedUsersDetail(activeOperator: String, userChatId: String, language: Language) {
        val user = userRepository.findByChatIdAndDeletedFalse(activeOperator) ?: throw OperatorNotFoundException()
        val name = user.name
        val sendMessage = SendMessage()
        sendMessage.chatId = activeOperator
        sendMessage.text = name + " ${BotMessage.USERS_ENDED_NAME.getText(language)}"
        try {
            absSender.execute(sendMessage)
        } catch (e: TelegramApiException) {
            log.warn("Error sending message to $activeOperator: $e sendEndedUsersDetail ")
        }
    }

    override fun sendMessageAndReplyMessageIfExists(
        chatId: String,
        text: String,
        replyToMessageId: String?
    ): String {
        val sendMessage = SendMessage()
        sendMessage.chatId = chatId
        sendMessage.text = text

        if (replyToMessageId != null) {
            val mapping = messageMapRepository.findByUserMessageId(replyToMessageId)
            if (mapping != null) {
                sendMessage.replyToMessageId = mapping.operatorMessageId.toInt()
            }
        }
        val sentMessage = absSender.execute(sendMessage)
        return sentMessage.messageId.toString()
    }

    override fun sendRemoveInlineKeyboard(chatId: Long, messageId: Int) {
        try {
            absSender.execute(EditMessageReplyMarkup().apply {
                this.chatId = chatId.toString()
                this.messageId = messageId
                this.replyMarkup = null
            })
        } catch (e: TelegramApiException) {
            log.error("Failed to remove inline keyboard for chat: $chatId", e)
        }
    }

    override fun sendContact(chatId: Long, lang: String) {
        val user = userRepository.findByChatIdAndDeletedFalse(chatId.toString()) ?: run {
            log.error("User not found for chatId: $chatId")
            return
        }
        if (user.role == Role.OPERATOR) {
            sendLocalizedMessage(
                chatId.toString(),
                BotMessage.OPERATOR_TEXT_START_WORK,
                when (lang) {
                    "RUS" -> Language.RUS
                    "ENG" -> Language.ENG
                    else -> Language.UZB
                }
            )
            languageService.updateOperatorLanguage(user, lang)
        } else {
            sendContactToRegularUser(chatId, lang)
        }
    }

    override fun sendContactToRegularUser(chatId: Long, lang: String) {
        val keyboardMarkup = userComponentService.shareContactBtn()
        val message = SendMessage(
            chatId.toString(),
            sendContactAnswerTextToUser(lang)
        ).apply {
            replyMarkup = keyboardMarkup
        }
        userComponentService.saveLanguage(message, lang)
        absSender.execute(message)
    }

    override fun sendContactAnswerTextToUser(lang: String): String =
        BotMessage.SHARE_CONTACT.getText(lang)

    override fun sendProcessLanguageSelection(chatId: Long, callBackData: String) {
        val language = when (callBackData) {
            "UZB_BUTTON" -> "UZB"
            "RUS_BUTTON" -> "RUS"
            "ENG_BUTTON" -> "ENG"
            else -> return
        }
        sendContact(chatId, language)
    }

    override fun sendShowLanguageSelection(chatId: String, requiredCount: Int, language: Language) {
        val state = operatorLanguageSelection[chatId] ?: OperatorLanguageState()

        val message = SendMessage(
            chatId,
            BotMessage.OPERATOR_SELECT_LANGUAGES.getText(
                language,
                "count" to state.selectedLanguages.size.toString(),
                "total" to requiredCount.toString()
            )
        )
        val keyboard = InlineKeyboardMarkup()
        val rows = mutableListOf<MutableList<InlineKeyboardButton>>()

        Language.entries.forEach { lang ->
            val isSelected = state.selectedLanguages.contains(lang)
            val emoji = if (isSelected) "✅" else "⬜️"

            val button = InlineKeyboardButton().apply {
                text = "$emoji ${lang.name}"
                callbackData = "${CALLBACK_LANGUAGE_SELECT_PREFIX}${lang.name}"
            }
            rows.add(mutableListOf(button))
        }

        if (state.selectedLanguages.size == requiredCount) {
            val confirmLang = when (language) {
                Language.ENG -> Language.ENG
                Language.RUS -> Language.RUS
                else -> Language.UZB
            }
            val confirmText = BotMessage.OPERATOR_CONFIRM_LANGUAGE.getText(confirmLang)

            rows.add(mutableListOf(InlineKeyboardButton().apply {
                text = confirmText
                callbackData = CALLBACK_LANGUAGE_CONFIRM
            }))
        }

        keyboard.keyboard = rows
        message.replyMarkup = keyboard

        try {
            absSender.execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error showing language selection", e)
        }
    }

    override fun sendUpdateLanguageSelection(chatId: String, messageId: Int, requiredCount: Int, language: Language) {
        val state = operatorLanguageSelection[chatId]!!

        BotMessage.OPERATOR_SELECT_LANGUAGES.getText(
            language,
            "count" to state.selectedLanguages.size.toString(),
            "total" to requiredCount.toString()
        )

        val keyboard = InlineKeyboardMarkup()
        val rows = mutableListOf<MutableList<InlineKeyboardButton>>()

        Language.entries.forEach { lang ->
            val isSelected = state.selectedLanguages.contains(lang)
            val emoji = if (isSelected) "✅" else "⬜️"

            val button = InlineKeyboardButton().apply {
                text = "$emoji ${lang.name}"
                callbackData = "${CALLBACK_LANGUAGE_SELECT_PREFIX}${lang.name}"
            }
            rows.add(mutableListOf(button))
        }

        if (state.selectedLanguages.size == requiredCount) {
            val confirmText = BotMessage.OPERATOR_CONFIRM_LANGUAGE.getText(language)

            rows.add(mutableListOf(InlineKeyboardButton().apply {
                text = confirmText
                callbackData = CALLBACK_LANGUAGE_CONFIRM
            }))
        }

        keyboard.keyboard = rows

        try {
            val editMessage = EditMessageReplyMarkup()
            editMessage.chatId = chatId
            editMessage.messageId = messageId
            editMessage.replyMarkup = keyboard
            absSender.execute(editMessage)
        } catch (e: TelegramApiException) {
            log.error("Error updating language selection", e)
        }
    }

    override fun sendContactChangeInlineKeyboard(chatId: String, text: String, operatorLanguage: String) {
        val confirmMessage = SendMessage()
        confirmMessage.chatId = chatId
        confirmMessage.text = text
        confirmMessage.replyMarkup = userComponentService.contactChangeInlineKeyboard(operatorLanguage)

        try {
            absSender.execute(confirmMessage)
        } catch (e: TelegramApiException) {
            log.error(" Error sending confirmation to $chatId", e)
        }
    }

    override fun sendPhoto(chatId: String, fileId: String?): String {
        try {
            val sendPhoto = SendPhoto()
            sendPhoto.chatId = chatId
            sendPhoto.photo = InputFile(fileId)
            val sentMessage = absSender.execute(sendPhoto)
            return sentMessage.messageId.toString()
        } catch (e: TelegramApiException) {
            log.error("Failed to send photo to operator", e)
            return ""
        }
    }

    override fun sendVideo(chatId: String, fileId: String?): String {
        val sendVideo = SendVideo()
        sendVideo.chatId = chatId
        sendVideo.video = InputFile(fileId)
        try {
            val sentVideo = absSender.execute(sendVideo)
            return sentVideo.messageId.toString()
        } catch (e: TelegramApiException) {
            log.error("Error sending video to operator", e)
            return ""
        }
    }

    override fun sendDocument(chatId: String, fileId: String?): String {
        val sendDocument = SendDocument()
        sendDocument.chatId = chatId
        sendDocument.document = InputFile(fileId)
        try {
            val sentDocument = absSender.execute(sendDocument)
            return sentDocument.messageId.toString()
        } catch (e: TelegramApiException) {
            log.error("Error sending document", e)
            return ""
        }
    }

    override fun sendVoice(chatId: String, fileId: String?): String {
        val sendVoice = SendVoice()
        sendVoice.chatId = chatId
        sendVoice.voice = InputFile(fileId)
        try {
            val sentVoice = absSender.execute(sendVoice)
            return sentVoice.messageId.toString()
        } catch (e: TelegramApiException) {
            log.error(" Failed to send voice to user $chatId", e)
            return ""
        }
    }

    override fun sendSticker(chatId: String, fileId: String?) {
        val sendSticker = SendSticker()
        sendSticker.chatId = chatId
        sendSticker.sticker = InputFile(fileId)
        try {
            absSender.execute(sendSticker)
        } catch (e: TelegramApiException) {
            log.error(" Failed to send voice to user $chatId", e)
        }
    }

    override fun sendVideoNote(chatId: String, fileId: String?): String {
        val sendVideoNote = SendVideoNote()
        sendVideoNote.chatId = chatId
        sendVideoNote.videoNote = InputFile(fileId)
        try {
            val sentVideNote = absSender.execute(sendVideoNote)
            return sentVideNote.messageId.toString()
        } catch (e: TelegramApiException) {
            log.error(" Error sending video note", e)
            return ""
        }
    }

    override fun sendEditedMessage(chatId: String, messageId: Int, text: String) {
        val editMessageText = EditMessageText()
        editMessageText.chatId = chatId
        editMessageText.messageId = messageId
        editMessageText.text = text
        try {
            absSender.execute(editMessageText)
        } catch (e: TelegramApiException) {
            log.error(" Error sending edited message", e)
        }
    }

    override fun sendFileToTelegram(file: GetFile): org.telegram.telegrambots.meta.api.objects.File =
        absSender.execute(file)
}

@Service
class NotificationServiceImpl(
    private val messageSendingService: MessageSendingService,
    private val userRepository: UserRepository,
    private val operatorUsersRepository: OperatorUsersRepository,
    private val messageServiceImpl: MessageSendingService,
    private val userComponentService: UserComponentsService,

    ) : NotificationService {

    private val log = LoggerFactory.getLogger(NotificationServiceImpl::class.java)

    override fun notifyAndUpdateSessions(operatorId: String, operatorLanguage: String) {
        notifyOperatorOnWorkEnd(operatorId)
        userRepository.updateBusyEndByChatId(operatorId)
        userRepository.updateOperatorEndedStatus(operatorId)
        operatorUsersRepository.updateSession(operatorId, null)

        val beginBtn = userComponentService.beginBtn()
        val text = BotMessage.OPERATOR_TEXT_BEGIN_WORK_BUTTON.getText(operatorLanguage)
        messageServiceImpl.sendMessageWithBeginBtn(beginBtn, operatorId, text)

        log.info("Operator with $operatorId ended work")
    }

    override fun notifyOperatorOnWorkEnd(operatorChatId: String) {
        val user = userRepository.findByChatIdAndDeletedFalse(operatorChatId) ?: throw UserNotFoundException()
        try {
            messageSendingService.sendLocalizedMessage(
                operatorChatId,
                BotMessage.THANK_YOU,
                user.language
            )
        } catch (ex: TelegramApiException) {
            log.warn("notifyOperatorOnWorkEnd", ex)
        }
    }

    override fun notifyOperatorSelectLanguage(operatorChatId: String) {
        val user = userRepository.findByChatIdAndDeletedFalse(operatorChatId) ?: throw UserNotFoundException()
        try {
            userComponentService.showLanguageCountSelection(operatorChatId, user.language)
        } catch (ex: TelegramApiException) {
            log.warn("notifyOperatorOnWorkStart", ex)
        }
    }

    override fun notifyOperatorOnWorkStart(chatId: String) {
        val user = userRepository.findByChatIdAndDeletedFalse(chatId) ?: throw UserNotFoundException()
        try {
            messageServiceImpl.sendLocalizedMessage(
                chatId,
                BotMessage.START_WORK,
                user.language
            )
        } catch (ex: TelegramApiException) {
            log.warn("notifyOperatorOnWorkStart", ex)
        }
    }

    override fun notifyClientOnOperatorJoin(userChatId: String) {
        val user = userRepository.findByChatIdAndDeletedFalse(userChatId) ?: throw UserNotFoundException()
        try {
            messageSendingService.sendEndButtonToUser(userChatId, user.language)

        } catch (ex: TelegramApiException) {
            log.warn("notifyClientOnOperatorJoin", ex)
        }
    }
}

@Service
class OperatorServiceImpl(
    @Value("\${telegram.bot.token}")
    private val botToken: String,
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val notificationService: NotificationService,
    private val messageSendingService: MessageSendingService,
    private val messageMappingRepository: MessageMappingRepository,
    private val operatorUsersRepository: OperatorUsersRepository,
    private val messageMappingService: MessageMappingService,
    private val queueService: QueueService,
    private val fileRepository: FileRepository
) : OperatorService {

    private val log = LoggerFactory.getLogger(OperatorServiceImpl::class.java)

    override fun handleTextMessage(
        operator: User,
        text: String,
        messageId: String,
        replyToMessageId: String?
    ) {
        val operatorLanguage = operator.language.name
        val operatorLanguageEnum = operator.language
        val operatorDbId = operator.id
        val operatorChatId =
            userRepository.findExactOperatorById(operatorDbId!!) ?: throw OperatorNotFoundException()

        if (text == END) {
            val activeUsers = userRepository.findActiveUsersByOperator(operatorChatId)

            if (activeUsers.isEmpty()) {
                notificationService.notifyAndUpdateSessions(operatorChatId, operatorLanguage)
                return
            }

            activeUsers.forEach { userChatId ->
                val user = userRepository.findByChatIdAndDeletedFalse(userChatId) ?: throw UserNotFoundException()
                val userLanguage = user.language
                try {
                    messageSendingService.sendEndedOperatorsDetail(
                        operatorChatId,
                        userChatId,
                        userLanguage
                    )
                    log.info(" Message sent: operator=$operatorChatId → user=$userChatId")
                } catch (e: TelegramApiException) {
                    log.error(" Failed to send message to user $userChatId", e)
                }
            }

            notificationService.notifyAndUpdateSessions(operatorChatId, operatorLanguage)
            return
        }
        val languages = operatorUsersRepository.findLanguagesOperator(operator.id!!)

        when (text) {
            START -> {
                userRepository.updateBusyByChatId(operatorChatId)
                notificationService.notifyOperatorSelectLanguage(operatorChatId)
                return
            }

            BEGIN -> {
                messageSendingService.sendMessageEndButton(operatorChatId, operatorLanguageEnum)

                userRepository.updateOperatorEndedStatusToTrue(operatorChatId)
                val currentSessions = operatorUsersRepository.findActiveSessionsByOperator(operatorChatId)
                if (currentSessions.isNotEmpty()) {
                    messageSendingService.sendLocalizedMessage(
                        operatorChatId,
                        BotMessage.OPERATOR_WARN_MESSAGE,
                        operatorLanguageEnum
                    )
                    return
                }

                var foundUser: String? = null

                for (language in languages) {
                    val waitingUser = queueService.dequeueUser(Language.valueOf(language))
                    if (waitingUser != null) {
                        foundUser = waitingUser
                        break
                    }
                }

                if (foundUser == null) {
                    messageSendingService.sendLocalizedMessage(
                        operatorChatId,
                        BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                        Language.valueOf(operatorLanguage)
                    )
                    return
                }

                try {
                    userRepository.updateBusyByChatId(operatorChatId)
                    queueService.deliverPendingMessagesToOperator(operatorChatId, foundUser, messageId)
                    userService.saveOperatorUserRelationIfNotExists(operatorChatId, foundUser)

                    notificationService.notifyOperatorOnWorkStart(operatorChatId)

                    val user = userRepository.findByChatIdAndDeletedFalse(foundUser) ?: throw UserNotFoundException()
                    val userLanguage = user.language
                    messageSendingService.sendUsersDetail(operatorChatId, foundUser, operatorLanguageEnum)
                    messageSendingService.sendOperatorsDetail(
                        operatorChatId,
                        foundUser,
                        userLanguage
                    )

                    notificationService.notifyClientOnOperatorJoin(foundUser)

                    log.info(" Operator $operatorChatId connected with user $foundUser ")
                } catch (ex: Exception) {
                    log.error("Failed to start session for operator=$operatorChatId user=$foundUser", ex)
                    userRepository.updateBusyByChatId(operatorChatId)
                }
            }

            else -> {
                userRepository.findByChatIdAndDeletedFalse(operatorChatId)?.let { operator ->
                    if (operator.userEnded) {
                        messageSendingService.sendLocalizedMessage(
                            operatorChatId,
                            BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                            Language.valueOf(operatorLanguage)
                        )
                        return
                    }
                    val activeUsers = userRepository.findActiveUsersByOperator(operatorChatId)

                    if (activeUsers.isEmpty()) {
                        messageSendingService.sendLocalizedMessage(
                            operatorChatId,
                            BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                            Language.valueOf(operatorLanguage)
                        )
                        return
                    }
                    activeUsers.forEach { userChatId ->
                        try {
                            val userReceivedMessageId =
                                messageSendingService.sendMessageAndReplyMessageIfExists(
                                    userChatId,
                                    text,
                                    replyToMessageId
                                )

                            messageMappingService.messageMappingSave(
                                operatorChatId,
                                userChatId,
                                messageId,
                                text,
                                userReceivedMessageId,
                                null
                            )
                            log.info(" Message sent: operator=$operatorChatId → user=$userChatId")
                        } catch (e: TelegramApiException) {
                            log.error(" Failed to send message to user $userChatId", e)
                        }
                    }
                } ?: throw OperatorNotFoundException()
            }
        }
    }

    override fun saveLanguages(chatId: String, languages: Set<Language>) {
        userRepository.clearOperatorLanguages(chatId)

        languages.forEach { language ->
            userRepository.addOperatorLanguage(chatId, language.name)
        }
        log.info("Operator $chatId languages saved: $languages")
    }

    private fun getYmDString(): String {
        val year = Calendar.getInstance()[Calendar.YEAR]
        val month = Calendar.getInstance()[Calendar.MONTH] + 1
        val day = Calendar.getInstance()[Calendar.DATE]
        return "$year/$month/$day"
    }

    override fun handlePhoto(operator: User, message: Message, replyMessageId: String?) {
        val operatorId = userRepository.findExactOperatorById(operator.id!!) ?: throw OperatorNotFoundException()

        val photos = message.photo
        if (photos.isEmpty()) return

        val photo = photos.maxByOrNull { it.fileSize ?: 0 } ?: photos.last()

        val pathFolder = getYmDString()
        val key = UUID.randomUUID().toString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        var savedPath: String? = null
        try {
            val getFile = GetFile()
            getFile.fileId = photo.fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.jpg")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.jpg"

            userRepository.findByChatIdAndDeletedFalse(operatorId)?.let { op ->
                if (op.userEnded) {
                    messageSendingService.sendLocalizedMessage(
                        operatorId,
                        BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                        operator.language
                    )
                    return
                }
            }

            val fileId = photo.fileId

            val activeUsers = userRepository.findActiveUsersByOperator(operatorId)

            if (activeUsers.isEmpty()) {
                messageSendingService.sendLocalizedMessage(
                    operatorId,
                    BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                    operator.language
                )
                return
            }


            activeUsers.forEach { userChatId ->
                val savedFile = fileRepository.save(
                    File(
                        "photo_${key}.jpg",
                        fileId,
                        "jpg",
                        photo.fileSize,
                        savedPath
                    )
                )

                val messageId = messageSendingService.sendPhoto(userChatId, fileId)

                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId = operatorId,
                        userChatId = userChatId,
                        operatorMessageId = message.messageId.toString(),
                        message = "photo",
                        userMessageId = messageId,
                        file = savedFile
                    )
                )
            }
        } catch (e: TelegramApiException) {
            log.warn("hanlde Photo Operator ${e.message}")
        }
    }

    override fun handleVideo(operator: User, message: Message) {
        val operatorId = userRepository.findExactOperatorById(operator.id!!) ?: throw OperatorNotFoundException()

        val video = message.video ?: return
        val fileId = video.fileId

        val pathFolder = getYmDString()
        val key = UUID.randomUUID().toString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        var savedPath: String? = null
        val fileName = video.fileName ?: "video";

        try {
            val getFile = GetFile()
            getFile.fileId = fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.${getExtension(fileName)}")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.${getExtension(fileName)}"

            userRepository.findByChatIdAndDeletedFalse(operatorId)?.let { op ->
                if (op.userEnded) {
                    messageSendingService.sendLocalizedMessage(
                        operatorId,
                        BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                        operator.language
                    )
                    return
                }
            }

            val activeUsers = userRepository.findActiveUsersByOperator(operatorId)

            if (activeUsers.isEmpty()) {
                messageSendingService.sendLocalizedMessage(
                    operatorId,
                    BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                    operator.language
                )
                return
            }

            activeUsers.forEach { userChatId ->
                val savedFile = fileRepository.save(
                    File(
                        fileName,
                        fileId,
                        getExtension(fileName),
                        video.duration,
                        savedPath
                    )
                )
                val messageId = messageSendingService.sendVideo(userChatId, fileId)

                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId = operatorId,
                        userChatId = userChatId,
                        operatorMessageId = message.messageId.toString(),
                        message = "video",
                        userMessageId = messageId,
                        file = savedFile
                    )
                )
            }
        } catch (e: TelegramApiException) {
            log.warn("Hanlde video Operator ${e.message}")
        }
    }

    override fun handleDocument(operator: User, message: Message) {
        val operatorId = userRepository.findExactOperatorById(operator.id!!) ?: throw OperatorNotFoundException()
        val document = message.document ?: return
        val fileId = document.fileId

        val pathFolder = getYmDString()
        val key = UUID.randomUUID().toString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        val fileName = document.fileName ?: "document";

        var savedPath: String? = null
        try {
            val getFile = GetFile()
            getFile.fileId = fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.${getExtension(fileName)}")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.${getExtension(fileName)}"

            userRepository.findByChatIdAndDeletedFalse(operatorId)?.let { op ->
                if (op.userEnded) {
                    messageSendingService.sendLocalizedMessage(
                        operatorId,
                        BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                        operator.language
                    )
                    return
                }
            }

            val activeUsers = userRepository.findActiveUsersByOperator(operatorId)

            if (activeUsers.isEmpty()) {
                messageSendingService.sendLocalizedMessage(
                    operatorId,
                    BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                    operator.language
                )
                return
            }

            activeUsers.forEach { userChatId ->
                val savedFile = fileRepository.save(
                    File(
                        fileName,
                        fileId,
                        getExtension(fileName),
                        document.fileSize,
                        savedPath
                    )
                )
                val messageId = messageSendingService.sendDocument(userChatId, fileId)

                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId = operatorId,
                        userChatId = userChatId,
                        operatorMessageId = message.messageId.toString(),
                        message = "document",
                        userMessageId = messageId,
                        file = savedFile
                    )
                )
            }
        } catch (e: TelegramApiException) {
            log.error("handle operator document")
        }
    }

    private fun getExtension(fileName: String): String {
        val separator = "."
        val lastIndex = fileName.lastIndexOf(separator)
        return fileName.substring(lastIndex + 1)
    }

    override fun handleVoice(operator: User, message: Message) {
        val operatorChatId = userRepository.findExactOperatorById(operator.id!!) ?: throw OperatorNotFoundException()

        val voice = message.voice ?: return
        val fileId = voice.fileId

        val pathFolder = getYmDString()
        val key = UUID.randomUUID().toString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        val fileName = "voice"

        var savedPath: String? = null
        try {
            val getFile = GetFile()
            getFile.fileId = fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.voice")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.voice"


            if (operator.userEnded) {
                messageSendingService.sendLocalizedMessage(
                    operatorChatId,
                    BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                    operator.language
                )
                return
            }

            val activeUsers = userRepository.findActiveUsersByOperator(operatorChatId)

            if (activeUsers.isEmpty()) {
                messageSendingService.sendLocalizedMessage(
                    operatorChatId,
                    BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                    operator.language
                )
                return
            }

            activeUsers.forEach { userChatId ->
                val savedFile = fileRepository.save(
                    File(
                        fileName,
                        fileId,
                        getExtension(fileName),
                        voice.fileSize,
                        savedPath
                    )
                )
                val messageId = messageSendingService.sendVoice(userChatId, fileId)
                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId = operatorChatId,
                        userChatId = userChatId,
                        operatorMessageId = message.messageId.toString(),
                        message = "voice",
                        userMessageId = messageId,
                        file = savedFile
                    )
                )
            }
        } catch (e: TelegramApiException) {
            log.error("handle operator voice message")
        }
    }

    override fun handleSticker(operator: User, message: Message) {
        val operatorChatId = userRepository.findExactOperatorById(operator.id!!) ?: throw OperatorNotFoundException()

        if (operator.userEnded) {
            messageSendingService.sendLocalizedMessage(
                operatorChatId,
                BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                operator.language
            )
            return
        }

        val sticker = message.sticker ?: return

        val fileId = sticker.fileId

        val activeUsers = userRepository.findActiveUsersByOperator(operatorChatId)

        if (activeUsers.isEmpty()) {
            messageSendingService.sendLocalizedMessage(
                operatorChatId,
                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                operator.language
            )
            return
        }

        activeUsers.forEach { userChatId ->
            messageSendingService.sendSticker(userChatId, fileId)
        }
    }

    override fun handleVideoNote(operator: User, message: Message) {
        val operatorChatId = userRepository.findExactOperatorById(operator.id!!) ?: throw OperatorNotFoundException()

        val videoNote = message.videoNote ?: return
        val fileId = videoNote.fileId

        val pathFolder = getYmDString()
        val key = UUID.randomUUID().toString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        val fileName = "video note"

        var savedPath: String? = null
        try {
            val getFile = GetFile()
            getFile.fileId = fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.video_note")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.video_note"

            if (operator.userEnded) {
                messageSendingService.sendLocalizedMessage(
                    operatorChatId,
                    BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                    operator.language
                )
                return
            }

            val activeUsers = userRepository.findActiveUsersByOperator(operatorChatId)

            if (activeUsers.isEmpty()) {
                messageSendingService.sendLocalizedMessage(
                    operatorChatId,
                    BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                    operator.language
                )
                return
            }

            activeUsers.forEach { userChatId ->
                val savedFile = fileRepository.save(
                    File(
                        fileName,
                        fileId,
                        getExtension(fileName),
                        videoNote.fileSize,
                        savedPath
                    )
                )
                val messageId = messageSendingService.sendVideoNote(userChatId, fileId)

                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId = operatorChatId,
                        userChatId = userChatId,
                        operatorMessageId = message.messageId.toString(),
                        message = "voice",
                        userMessageId = messageId,
                        file = savedFile
                    )
                )
            }
        } catch (e: TelegramApiException) {
            log.error("handle operator video note")
        }
    }

    override fun handleEditedMessage(operator: User, editedMessage: Message, editedMessageId: String) {
        if (!editedMessage.hasText()) return

        val mapping = messageMappingRepository.findByOperatorMessageId(editedMessageId) ?: return

        val userChatId = mapping.userChatId
        val userMessageId = mapping.userMessageId
        messageMappingRepository.save(
            MessageMapping(
                operator.chatId,
                userChatId,
                editedMessageId,
                editedMessage.text,
                userMessageId
            )
        )
        messageSendingService.sendEditedMessage(userChatId, userMessageId.toInt(), editedMessage.text)
    }
}


@Service
class UserComponentServiceImpl(
    private val userRepository: UserRepository,
) : UserComponentsService {

    override fun contactButton(): ReplyKeyboardMarkup {
        val keyboardMarkup = ReplyKeyboardMarkup()
        keyboardMarkup.resizeKeyboard = true
        keyboardMarkup.oneTimeKeyboard = true

        val button = KeyboardButton().apply {
            text = "Share Contact"
            requestContact = true
        }

        val row = KeyboardRow()
        row.add(button)
        keyboardMarkup.keyboard = listOf(row)
        return keyboardMarkup
    }

    override fun beginBtn(): ReplyKeyboardMarkup {
        val keyboardMarkup = ReplyKeyboardMarkup()
        keyboardMarkup.resizeKeyboard = true

        val button = KeyboardButton().apply {
            text = BEGIN
        }
        val row = KeyboardRow()
        row.add(button)
        keyboardMarkup.keyboard = listOf(row)
        return keyboardMarkup
    }

    override fun showLanguageCountSelection(chatId: String, language: Language): InlineKeyboardMarkup {

        val keyboard = InlineKeyboardMarkup()
        val row = mutableListOf<InlineKeyboardButton>()

        for (i in 1..3) {
            row.add(InlineKeyboardButton().apply {
                text = "$i"
                callbackData = "${CALLBACK_LANGUAGE_COUNT_PREFIX}$i"
            })
        }

        keyboard.keyboard = listOf(row)
        return keyboard
    }

    override fun endBtn(): ReplyKeyboardMarkup {
        val keyboardMarkup = ReplyKeyboardMarkup()
        keyboardMarkup.resizeKeyboard = true

        val button = KeyboardButton().apply {
            text = END
        }

        val row = KeyboardRow()
        row.add(button)
        keyboardMarkup.keyboard = listOf(row)
        return keyboardMarkup
    }

    override fun userEndBtn(): ReplyKeyboardMarkup {
        val keyboardMarkup = ReplyKeyboardMarkup()
        keyboardMarkup.resizeKeyboard = true

        val button = KeyboardButton().apply {
            text = END
        }

        val button1 = KeyboardButton().apply {
            text = HELP

        }

        val button2 = KeyboardButton().apply {
            text = "/lang"
        }

        val row = KeyboardRow()
        row.add(button)
        row.add(button1)
//        row.add(button2)
        keyboardMarkup.keyboard = listOf(row)
        return keyboardMarkup
    }

    override fun startBtn(): ReplyKeyboardMarkup {
        val keyboardMarkup = ReplyKeyboardMarkup()
        keyboardMarkup.resizeKeyboard = true

        val button = KeyboardButton().apply {
            text = START
        }

        val button1 = KeyboardButton().apply {
            text = HELP
        }

        val button2 = KeyboardButton().apply {
            text = "/lang"
        }

        val row = KeyboardRow()
        row.add(button)
        row.add(button1)
//        row.add(button2)
        keyboardMarkup.keyboard = listOf(row)
        return keyboardMarkup
    }

    override fun changeLanguageInlineKeyboard(): InlineKeyboardMarkup {
        val uzBtn = InlineKeyboardButton().apply {
            text = "UZB 🇺🇿"
            callbackData = "CHANGE_UZB_BUTTON"
        }
        val ruBtn = InlineKeyboardButton().apply {
            text = "RUS 🇷🇺"
            callbackData = "CHANGE_RUS_BUTTON"
        }
        val enBtn = InlineKeyboardButton().apply {
            text = "ENG 🇬🇧"
            callbackData = "CHANGE_ENG_BUTTON"
        }
        val row: MutableList<InlineKeyboardButton> = ArrayList()
        row.add(uzBtn)
        row.add(ruBtn)
        row.add(enBtn)
        val keyboard: MutableList<MutableList<InlineKeyboardButton>> = ArrayList()
        keyboard.add(row)

        InlineKeyboardMarkup().keyboard = keyboard
        return InlineKeyboardMarkup(keyboard)
    }

    override fun shareContactBtn(): ReplyKeyboardMarkup {
        val keyboardMarkup = ReplyKeyboardMarkup()
        keyboardMarkup.resizeKeyboard = true
        keyboardMarkup.oneTimeKeyboard = true

        val button = KeyboardButton().apply {
            text = "Share Contact"
            requestContact = true
        }

        val row = KeyboardRow()
        row.add(button)
        keyboardMarkup.keyboard = listOf(row)
        return keyboardMarkup
    }

    override fun saveLanguage(message: SendMessage, language: String) {
        userRepository.findByChatIdAndDeletedFalse(message.chatId)?.let { user ->
            val lang = when (language) {
                "ENG" -> Language.ENG
                "RUS" -> Language.RUS
                else -> Language.UZB
            }
            user.language = lang
            userRepository.save(user)
        }
    }

    override fun contactChangeInlineKeyboard(language: String): InlineKeyboardMarkup? {
        val yesBtn = InlineKeyboardButton().apply {
            text = BotMessage.YES_TEXT.getText(language)
            callbackData = "YES_BUTTON"
        }
        val notBtn = InlineKeyboardButton().apply {
            text = BotMessage.NO_TEXT.getText(language)
            callbackData = "NO_BUTTON"
        }

        val row: MutableList<InlineKeyboardButton> = ArrayList()
        row.add(yesBtn)
        row.add(notBtn)
        val keyboard: MutableList<MutableList<InlineKeyboardButton>> = ArrayList()
        keyboard.add(row)

        InlineKeyboardMarkup().keyboard = keyboard
        return InlineKeyboardMarkup(keyboard)
    }
}


@Service
class UserServiceImpl(
    @Value("\${telegram.bot.token}")
    private val botToken: String,
    private val userRepository: UserRepository,
    private val messageSendingService: MessageSendingService,
    private val operatorUsersRepository: OperatorUsersRepository,
    private val userComponentService: UserComponentsService,
    private val notificationService: NotificationService,
    private val messageMappingService: MessageMappingService,
    private val messageMappingRepository: MessageMappingRepository,
    private val queueService: QueueService,
    private val fileRepository: FileRepository,
) : UserService {

    private val log = LoggerFactory.getLogger(UserServiceImpl::class.java)

    override fun handleNewUser(chatId: String, text: String, message: Message) {
        when (text) {
            "/start" -> {
                val name = message.chat.firstName ?: "User "
                val welcome = BotMessage.WELCOME_MESSAGE.getText(
                    Language.UZB,
                    "name" to name
                )
                messageSendingService.sendMessage(chatId, welcome)
                saveUser(chatId, name)
            }

            else -> {
                messageSendingService.sendMessage(chatId, "Iltimos, /start bosing!")
            }
        }
    }

    override fun handleTextMessage(
        user: User,
        text: String,
        chatId: String,
        messageId: String,
        replyToMessageId: String?
    ) {
        val userChatId = user.chatId
        val language = user.language
        val startBtn = userComponentService.startBtn()
        val userEndBtn = userComponentService.userEndBtn()

        when (text) {
            START -> {
                userRepository.updateUserEndedStatusToFalse(userChatId)
                messageSendingService.sendLocalizedMessage(
                    userChatId,
                    BotMessage.NO_OPERATOR_AVAILABLE,
                    language,
                    userEndBtn
                )
                queueService.enqueueUser(language, userChatId)
                return
            }

            HELP -> {
                messageSendingService.sendLocalizedMessage(userChatId, BotMessage.HELP_TEXT, language)
                return
            }
//            "/lang" -> {
//                val langInlineMarkup = userComponentService.changeLanguageInlineKeyboard()
//                messageSendingService.sendLocalizedMessage(userChatId, BotMessage.CHANGE_LANGUAGE_MESSAGE, language, langInlineMarkup)
//                return
//            }
            END -> {
                val activeOperator = userRepository.findOperatorByActiveSession(userChatId)

                operatorUsersRepository.updateSession(null, userChatId)
                userRepository.updateUserEndedStatus(userChatId)
                messageSendingService.sendLocalizedMessage(userChatId, BotMessage.END_SESSION, language, startBtn)

                queueService.removeFromQueue(language, userChatId)
//                pendingMessages.remove(userChatId)

                if (activeOperator != null) {
                    val user =
                        userRepository.findByChatIdAndDeletedFalse(activeOperator) ?: throw UserNotFoundException()
                    val operatorLanguage = user.language
                    messageSendingService.sendEndedUsersDetail(
                        activeOperator,
                        userChatId,
                        operatorLanguage
                    )
                    tryConnectNextUserToOperator(activeOperator)
                }
                return
            }
        }

        val activeOperator = userRepository.findOperatorByActiveSession(userChatId)

        if (activeOperator != null) {
            val operatorReceivedMessageId =
                messageSendingService.sendMessageAndReplyMessageIfExists(
                    activeOperator,
                    text,
                    replyToMessageId
                )

            messageMappingService.messageMappingSave(
                activeOperator,
                userChatId,
                operatorReceivedMessageId,
                text,
                messageId,
                null
            )
            return
        }

        queueService.addPendingMessage(userChatId, text)

        if (!queueService.isUserInQueue(language, userChatId)) {
            queueService.enqueueUser(language, userChatId)
        }

        val operatorChatIdV2 = userRepository.findAvailableOperatorByLanguage(user.language.name) ?: return
        val user = userRepository.findByChatIdAndDeletedFalse(operatorChatIdV2) ?: throw UserNotFoundException()
        val operatorLanguage = user.language

        val operatorBusy = operatorUsersRepository.isOperatorBusy(operatorChatIdV2)
        if (operatorBusy) {
            return
        }

        val queuedUser = queueService.dequeueUser(language)
        if (queuedUser != null) {
            if (queuedUser == userChatId) {

                saveOperatorUserRelationIfNotExists(operatorChatIdV2, userChatId)
                userRepository.updateBusyByChatId(operatorChatIdV2)

                notificationService.notifyClientOnOperatorJoin(userChatId)
                notificationService.notifyOperatorOnWorkStart(operatorChatIdV2)

                messageSendingService.sendOperatorsDetail(operatorChatIdV2, userChatId, language)
                messageSendingService.sendUsersDetail(
                    operatorChatIdV2,
                    userChatId,
                    operatorLanguage
                )
                queueService.deliverPendingMessagesToOperator(operatorChatIdV2, userChatId, messageId)
            } else {

                saveOperatorUserRelationIfNotExists(operatorChatIdV2, queuedUser)
                userRepository.updateBusyByChatId(operatorChatIdV2)
                notificationService.notifyClientOnOperatorJoin(queuedUser)
                notificationService.notifyOperatorOnWorkStart(operatorChatIdV2)

                queueService.deliverPendingMessagesToOperator(operatorChatIdV2, queuedUser, messageId)

                queueService.enqueueUser(language, userChatId)
            }
            return
        }

        saveOperatorUserRelationIfNotExists(operatorChatIdV2, userChatId)
        userRepository.updateBusyByChatId(operatorChatIdV2)
        notificationService.notifyClientOnOperatorJoin(userChatId)
        notificationService.notifyOperatorOnWorkStart(operatorChatIdV2)

        messageSendingService.sendOperatorsDetail(operatorChatIdV2, userChatId, language)
        messageSendingService.sendUsersDetail(operatorChatIdV2, userChatId, operatorLanguage)
        messageSendingService.sendMessageToOperator(operatorChatIdV2, text)
    }

    override fun saveUser(chatId: String, name: String) {
        userRepository.save(
            User(
                chatId = chatId,
                phoneNumber = "",
                busy = false,
                language = Language.UZB,
                role = Role.USER,
                name = name
            )
        )
    }

    override fun saveOperatorUserRelationIfNotExists(
        operatorChatId: String,
        userChatId: String
    ) {
        val obj = operatorUsersRepository.find(operatorChatId, userChatId)
        if (obj == null) {
            try {
                val opUser = OperatorUsers(
                    userChatId = userChatId,
                    operatorChatId = operatorChatId
                )
                operatorUsersRepository.save(opUser)

            } catch (ex: DataIntegrityViolationException) {
                log.warn("OperatorUsers relation already exists: $operatorChatId / $userChatId")
            }
        } else {
            obj.session = true
            operatorUsersRepository.save(obj)
        }
    }

    override fun tryConnectNextUserToOperator(operatorChatId: String) {
        val operatorDbId = userRepository.findByChatIdAndDeletedFalse(operatorChatId)?.id ?: return
        val languages = operatorUsersRepository.findLanguagesOperator(operatorDbId)

        var nextUser: String? = null

        for (lang in languages) {
            val waitingUser = queueService.dequeueUser(Language.valueOf(lang))
            if (waitingUser != null) {
                nextUser = waitingUser
                break
            }
        }

        if (nextUser != null) {
            saveOperatorUserRelationIfNotExists(operatorChatId, nextUser)
            userRepository.updateBusyByChatId(operatorChatId)
            notificationService.notifyClientOnOperatorJoin(nextUser)
            notificationService.notifyOperatorOnWorkStart(operatorChatId)

            val user = userRepository.findByChatIdAndDeletedFalse(nextUser) ?: throw UserNotFoundException()
            val userLanguage = user.language

            val operator =
                userRepository.findByChatIdAndDeletedFalse(operatorChatId) ?: throw OperatorNotFoundException()
            val operatorLang = operator.language

            messageSendingService.sendOperatorsDetail(operatorChatId, nextUser, userLanguage)
            messageSendingService.sendUsersDetail(operatorChatId, nextUser, operatorLang)

            queueService.deliverPendingMessagesToOperator(operatorChatId, nextUser, "")

            log.info("Operator $operatorChatId automatically connected to next user $nextUser")
        } else {
            userRepository.updateBusyByChatId(operatorChatId)
            val operator = userRepository.findByChatIdAndDeletedFalse(operatorChatId) ?: throw UserNotFoundException()
            val operatorLang = operator.language
            messageSendingService.sendLocalizedMessage(
                operatorChatId,
                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                operatorLang
            )
            log.info("No waiting users for operator $operatorChatId")
        }
    }

    override fun updateUserPhoneNumber(chatId: String, phoneNumber: String) {
        userRepository.findByChatIdAndDeletedFalse(chatId)?.let { user ->
            user.phoneNumber = normalizePhoneNumber(phoneNumber)
            userRepository.save(user)
        }
    }

    override fun normalizePhoneNumber(phoneNumber: String): String {
        return if (phoneNumber.startsWith("+")) {
            phoneNumber
        } else {
            "+$phoneNumber"
        }
    }

    private fun getYmDString(): String {
        val year = Calendar.getInstance()[Calendar.YEAR]
        val month = Calendar.getInstance()[Calendar.MONTH] + 1
        val day = Calendar.getInstance()[Calendar.DATE]
        return "$year/$month/$day"
    }

    override fun handlePhoto(user: User, message: Message, messageId: String) {
        val photos = message.photo
        if (photos.isEmpty()) return

        val photo = photos.maxByOrNull { it.fileSize ?: 0 } ?: photos.last()
        val userChatId = user.chatId
        val language = user.language


        val pathFolder = getYmDString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")
        val key = UUID.randomUUID().toString()

        if (!folder.exists()) {
            folder.mkdirs()
        }

        var savedPath: String? = null
        try {
            val getFile = GetFile()
            getFile.fileId = photo.fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.jpg")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.jpg"

        } catch (e: Exception) {
            e.printStackTrace()
        }

        val fileId = photo.fileId

        val operatorChatId = userRepository.findAvailableOperatorByLanguage(user.language.name)

        if (operatorChatId == null) {
            messageSendingService.sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
            return
        }

        if (!queueService.isUserInQueue(language, userChatId)) {
            queueService.enqueueUser(language, userChatId)
        }

        val hasActiveSession = operatorUsersRepository.hasActiveSession(operatorChatId, userChatId)

        if (hasActiveSession) {
            val savedFile = fileRepository.save(
                File(
                    name = "photo_${key}.jpg",
                    fileId = photo.fileId,
                    extension = "jpg",
                    size = photo.fileSize ?: 0,
                    path = savedPath ?: ""
                )
            )
            val messageId = messageSendingService.sendPhoto(operatorChatId, fileId)

            messageMappingRepository.save(
                MessageMapping(
                    operatorChatId = operatorChatId,
                    userChatId = userChatId,
                    operatorMessageId = messageId,
                    message = "photo",
                    userMessageId = message.messageId.toString(),
                    file = savedFile
                )
            )
        } else {
            messageSendingService.sendLocalizedMessage(userChatId, BotMessage.OPERATOR_OFFLINE, language)
        }
    }

    override fun handleVideo(user: User, message: Message, messageId: String) {
        val video = message.video ?: return
        val fileId = video.fileId

        val pathFolder = getYmDString()
        val key = UUID.randomUUID().toString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        var savedPath: String? = null
        val fileName = video.fileName ?: "video"
        try {
            val getFile = GetFile()
            getFile.fileId = fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.${getExtension(fileName)}")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.${getExtension(fileName)}"

            val userChatId = user.chatId
            val language = user.language

            val operatorChatId = userRepository.findAvailableOperatorByLanguage(user.language.name)

            if (operatorChatId == null) {
                messageSendingService.sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
                return
            }


            val hasActiveSession = operatorUsersRepository.hasActiveSession(operatorChatId, userChatId)

            if (hasActiveSession) {
                val savedFile = fileRepository.save(
                    File(
                        video.fileName ?: "video",
                        fileId,
                        getExtension(fileName),
                        video.duration,
                        savedPath
                    )
                )
                val messageId = messageSendingService.sendVideo(operatorChatId, fileId)

                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId = operatorChatId,
                        userChatId = userChatId,
                        operatorMessageId = message.messageId.toString(),
                        message = "video",
                        userMessageId = messageId,
                        file = savedFile
                    )
                )
            } else {
                messageSendingService.sendLocalizedMessage(operatorChatId, BotMessage.OPERATOR_OFFLINE, language)
            }
        } catch (e: TelegramApiException) {
            log.error("handle video user")
        }
    }

    private fun getExtension(fileName: String): String {
        val separator = "."
        val lastIndex = fileName.lastIndexOf(separator)
        return fileName.substring(lastIndex + 1)
    }

    override fun handleDocument(user: User, message: Message, messageId: String) {
        val userChatId = user.chatId
        val language = user.language

        val document = message.document ?: return
        val fileId = document.fileId

        val pathFolder = getYmDString()
        val key = UUID.randomUUID().toString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        val fileName = document.fileName ?: "document";

        var savedPath: String? = null
        try {
            val getFile = GetFile()
            getFile.fileId = fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.${getExtension(fileName)}")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.${getExtension(fileName)}"

            val operatorChatId = userRepository.findAvailableOperatorByLanguage(user.language.name)

            if (operatorChatId == null) {
                messageSendingService.sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
                return
            }

            val hasActiveSession = operatorUsersRepository.hasActiveSession(operatorChatId, userChatId)

            if (hasActiveSession) {
                val savedFile = fileRepository.save(
                    File(
                        fileName,
                        fileId,
                        getExtension(fileName),
                        document.fileSize,
                        savedPath
                    )
                )

                val messageId = messageSendingService.sendDocument(operatorChatId, fileId)

                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId = operatorChatId,
                        userChatId = userChatId,
                        operatorMessageId = message.messageId.toString(),
                        message = "document",
                        userMessageId = messageId,
                        file = savedFile
                    )
                )

            } else {
                messageSendingService.sendLocalizedMessage(userChatId, BotMessage.OPERATOR_OFFLINE, language)
            }
        } catch (e: TelegramApiException) {
            log.error("handle user document")
        }
    }

    override fun handleVoice(user: User, message: Message) {
        val userChatId = user.chatId
        val language = user.language
        val voice = message.voice ?: return
        val fileId = voice.fileId

        val pathFolder = getYmDString()
        val key = UUID.randomUUID().toString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        val fileName = "voice"

        var savedPath: String? = null
        try {
            val getFile = GetFile()
            getFile.fileId = fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.voice")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.voice"

            val activeOperator = userRepository.findOperatorByActiveSession(userChatId)

            if (activeOperator != null) {

                val savedFile = fileRepository.save(
                    File(
                        fileName,
                        fileId,
                        getExtension(fileName),
                        voice.fileSize,
                        savedPath
                    )
                )

                val messageId = messageSendingService.sendVoice(activeOperator, fileId)

                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId = activeOperator,
                        userChatId = userChatId,
                        operatorMessageId = message.messageId.toString(),
                        message = "voice",
                        userMessageId = messageId,
                        file = savedFile
                    )
                )
                return
            }
            messageSendingService.sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
        } catch (e: TelegramApiException) {
            log.error("handle user voice")
        }
    }

    override fun handleSticker(user: User, message: Message) {
        val userChatId = user.chatId
        val language = user.language

        val sticker = message.sticker ?: return

        val fileId = sticker.fileId

        val activeOperator = userRepository.findOperatorByActiveSession(userChatId)

        if (activeOperator != null) {
            messageSendingService.sendSticker(activeOperator, fileId)
            return
        }
        messageSendingService.sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
    }

    override fun handleVideoNote(user: User, message: Message) {
        val userChatId = user.chatId
        val language = user.language

        val videoNote = message.videoNote ?: return
        val fileId = videoNote.fileId

        val pathFolder = getYmDString()
        val key = UUID.randomUUID().toString()
        val folder = java.io.File("$FOLDER_NAME/$pathFolder")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        val fileName = "video note"

        var savedPath: String? = null
        try {
            val getFile = GetFile()
            getFile.fileId = fileId
            val telegramFile = messageSendingService.sendFileToTelegram(getFile)

            val fileUrl = "https://api.telegram.org/file/bot$botToken/${telegramFile.filePath}"

            val path: Path = Paths.get(FOLDER_NAME, pathFolder, "$key.video_note")
            Files.createDirectories(path.parent)

            val client = HttpClient.newHttpClient()

            val request = HttpRequest
                .newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            Files.write(path, response.body())

            savedPath = "$pathFolder/$key.video_note"

            val activeOperator = userRepository.findOperatorByActiveSession(userChatId)

            if (activeOperator != null) {
                val savedFile = fileRepository.save(
                    File(
                        fileName,
                        fileId,
                        getExtension(fileName),
                        videoNote.fileSize,
                        savedPath
                    )
                )

                val messageId = messageSendingService.sendVideoNote(activeOperator, fileId)

                messageMappingRepository.save(
                    MessageMapping(
                        operatorChatId = activeOperator,
                        userChatId = userChatId,
                        operatorMessageId = message.messageId.toString(),
                        message = "video note",
                        userMessageId = messageId,
                        file = savedFile
                    )
                )
                return
            }
            messageSendingService.sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
        } catch (e: TelegramApiException) {
            log.error("handle video Note ")
        }
    }

    override fun handleEditedMessage(user: User, editedMessage: Message, editedMessageId: String) {
        if (!editedMessage.hasText()) return

        val userChatId = user.chatId
        val activeOperator = userRepository.findOperatorByActiveSession(userChatId) ?: return
        val mapping = messageMappingRepository.findByUserMessageId(editedMessageId) ?: return
        val operatorMessageId = mapping.operatorMessageId
        messageMappingRepository.save(
            MessageMapping(
                activeOperator,
                userChatId,
                editedMessageId,
                editedMessage.text,
                editedMessageId
            )
        )
        messageSendingService.sendEditedMessage(activeOperator, operatorMessageId.toInt(), editedMessage.text)
    }
}

@Component
class TelegramBotService(
    private val botConfig: BotConfig,
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val operatorService: OperatorService,
    private val callBackQueryService: CallBackQueryService,
    private val messageSendingService: MessageSendingService,
    private val queueService: QueueService
) : TelegramLongPollingBot() {

    private val log = LoggerFactory.getLogger(TelegramBotService::class.java)

    override fun getBotUsername(): String = botConfig.botName
    override fun getBotToken(): String = botConfig.botToken

    override fun onUpdateReceived(update: Update) {
        when {
            update.hasMessage() && update.message.hasText() -> handleTextUpdate(update)
            update.hasMessage() && update.message.hasContact() -> handleContactUpdate(update)
            update.hasMessage() && update.message.hasPhoto() -> handlePhotoUpdate(update)
            update.hasMessage() && update.message.hasVideo() -> handleVideoUpdate(update)
            update.hasMessage() && update.message.hasVideoNote() -> handleVideoNoteUpdate(update)
            update.hasMessage() && update.message.hasDocument() -> handleDocumentUpdate(update)
            update.hasMessage() && update.message.hasVoice() -> handleVoiceUpdate(update)
            update.hasMessage() && update.message.hasSticker() -> handleStickerUpdate(update)
            update.hasCallbackQuery() -> handleCallBackQueryUpdate(update)
            update.hasEditedMessage() -> handleEditedMessageUpdate(update)

        }
    }

    private fun handleEditedMessageUpdate(update: Update) {
        val editedMessage = update.editedMessage
        val editedMessageId = update.editedMessage.messageId.toString()
        val chatIdStr = editedMessage.chatId.toString()

        val user = userRepository.findByChatIdAndDeletedFalse(chatIdStr) ?: return

        if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
            messageSendingService.sendMessageWithContactButton(
                chatIdStr,
                BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
            )
            return
        }
        if (user.role == Role.OPERATOR) {
            operatorService.handleEditedMessage(user, editedMessage, editedMessageId)
        } else {
            userService.handleEditedMessage(user, editedMessage, editedMessageId)
        }
    }

    private fun handleVideoNoteUpdate(update: Update) {
        val message = update.message
        val chatIdStr = message.chatId.toString()

        val user = userRepository.findByChatIdAndDeletedFalse(chatIdStr) ?: return

        if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
            messageSendingService.sendMessageWithContactButton(
                chatIdStr,
                BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
            )
            return
        }
        if (user.role == Role.OPERATOR) {
            operatorService.handleVideoNote(user, message)
        } else {
            userService.handleVideoNote(user, message)
        }
    }

    private fun handleStickerUpdate(update: Update) {
        val message = update.message
        val chatIdStr = message.chatId.toString()

        val user = userRepository.findByChatIdAndDeletedFalse(chatIdStr) ?: return

        if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
            messageSendingService.sendMessageWithContactButton(
                chatIdStr,
                BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
            )
            return
        }

        if (user.role == Role.OPERATOR) {
            operatorService.handleSticker(user, message)
        } else {
            userService.handleSticker(user, message)
        }
    }

    private fun handleVoiceUpdate(update: Update) {
        val message = update.message
        val chatIdStr = message.chatId.toString()

        val user = userRepository.findByChatIdAndDeletedFalse(chatIdStr) ?: return

        if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
            messageSendingService.sendMessageWithContactButton(
                chatIdStr,
                BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
            )
            return
        }

        if (user.role == Role.OPERATOR) {
            operatorService.handleVoice(user, message)
        } else {
            userService.handleVoice(user, message)
        }
    }

    private fun handleDocumentUpdate(update: Update) {
        val message = update.message
        val messageId = message.messageId.toString()
        val chatIdStr = message.chatId.toString()

        val user = userRepository.findByChatIdAndDeletedFalse(chatIdStr) ?: throw UserNotFoundException()

        if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
            messageSendingService.sendMessageWithContactButton(
                chatIdStr,
                BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
            )
            return
        }

        if (user.role == Role.OPERATOR) {
            operatorService.handleDocument(user, message)
        } else {
            userService.handleDocument(user, message, messageId)
        }
    }

    private fun handleVideoUpdate(update: Update) {
        val message = update.message
        val messageId = message.messageId.toString()
        val chatIdStr = message.chatId.toString()

        val user = userRepository.findByChatIdAndDeletedFalse(chatIdStr) ?: throw UserNotFoundException()

        if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
            messageSendingService.sendMessageWithContactButton(
                chatIdStr,
                BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
            )
            return
        }

        if (user.role == Role.OPERATOR) {
            operatorService.handleVideo(user, message)
        } else {
            userService.handleVideo(user, message, messageId)
        }
    }

    private fun handlePhotoUpdate(update: Update) {
        val message = update.message
        val messageId = message.messageId.toString()
        val chatIdStr = message.chatId.toString()
        val replyMessageId = message.replyToMessage?.messageId?.toString()

        val user = userRepository.findByChatIdAndDeletedFalse(chatIdStr) ?: throw UserNotFoundException()

        if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
            messageSendingService.sendMessageWithContactButton(
                chatIdStr,
                BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
            )
            return
        }

        if (user.role == Role.OPERATOR) {
            operatorService.handlePhoto(user, message, replyMessageId)
        } else {
            userService.handlePhoto(user, message, messageId)
        }
    }

    private fun handleContactUpdate(update: Update) {
        val message = update.message
        val currentPhoneNumber = message.contact.phoneNumber
        val chatId = message.chatId.toString()

        val user = userRepository.findByChatIdAndDeletedFalse(chatId) ?: throw UserNotFoundException()
        val operatorLanguage = user.language
        val operator = userRepository.checkOnOperator(chatId)

        if (operator) {
            messageSendingService.sendLocalizedMessage(
                chatId,
                BotMessage.OPERATOR_CONTACT_NOT_NEEDED,
                operatorLanguage
            )
            return
        }

        val normalizePhoneNumber = userService.normalizePhoneNumber(currentPhoneNumber)
        val phoneNumber = user.phoneNumber

        if (phoneNumber.isEmpty()) {
            userService.updateUserPhoneNumber(chatId, currentPhoneNumber)
            messageSendingService.sendLocalizedMessage(
                chatId,
                BotMessage.USER_CONTACT_ANSWER_MESSAGE,
                operatorLanguage
            )
            return
        }

        if (phoneNumber != normalizePhoneNumber) {
            val text = BotMessage.PHONE_CHANGE_CONFIRMATION.getText(
                operatorLanguage,
                "oldPhone" to phoneNumber,
                "newPhone" to normalizePhoneNumber
            )
            queueService.addPendingPhoneChange(chatId, normalizePhoneNumber)
            messageSendingService.sendContactChangeInlineKeyboard(chatId, text, operatorLanguage.toString())

        } else {
            messageSendingService.sendLocalizedMessage(
                chatId,
                BotMessage.USER_CONTACT_SAME_NUMBER,
                operatorLanguage
            )
            log.info("⚠ User $chatId sent same phone number")
        }
    }

    private fun handleCallBackQueryUpdate(update: Update) {
        val callbackQuery = update.callbackQuery
        val chatId = callbackQuery.from.id.toString()
        val user = userRepository.findByChatIdAndDeletedFalse(chatId) ?: throw UserNotFoundException()
        val language = user.language

        userRepository.findByChatIdAndDeletedFalse(chatId)?.let {
            if (it.role == Role.OPERATOR) {
                callBackQueryService.handleOperatorCallbackQuery(callbackQuery, language)
            } else {
                callBackQueryService.handleUserCallbackQuery(callbackQuery)
            }
        } ?: throw UserNotFoundException()
    }

    private fun handleTextUpdate(update: Update) {
        val message = update.message
        val user = userRepository.findByChatIdAndDeletedFalse(message.chatId.toString())

        if (user == null) {
            userService.handleNewUser(
                message.chatId.toString(),
                message.text,
                message
            )
            return
        }

        if (user.role == Role.OPERATOR) {
            operatorService.handleTextMessage(
                user,
                message.text,
                message.messageId.toString(),
                message.replyToMessage?.messageId?.toString(),
            )
        } else {
            userService.handleTextMessage(
                user,
                message.text,
                message.chatId.toString(),
                message.messageId.toString(),
                message.replyToMessage?.messageId?.toString()
            )
        }
    }
}