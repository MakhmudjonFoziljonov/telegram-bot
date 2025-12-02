package bot


import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiException

private const val CALLBACK_LANGUAGE_COUNT_PREFIX = "LANG_COUNT_"
private const val CALLBACK_LANGUAGE_SELECT_PREFIX = "LANG_SELECT_"
private const val CALLBACK_LANGUAGE_CONFIRM = "LANG_CONFIRM"
private const val START = "/start"
private const val BEGIN = "/begin"
private const val END = "/end"
private const val HELP = "/help"

interface TelegramBot {
    fun sendMessage(text: SendMessage)
    fun sendContact(chatId: Long, lang: String)
    fun sendMessage(chatId: String, text: String)
    fun sendContactAnswerTextToUser(lang: String): String
    fun sendContactToRegularUser(chatId: Long, lang: String)
    fun sendMessageToOperator(operatorChatId: String, text: String)
    fun sendMessageWithContactButton(chatId: String, text: String)

    fun handleContact(message: Message)
    fun handleCallbackQueryUser(callbackQuery: CallbackQuery)
    fun handleNewUser(chatId: String, text: String, message: Message)
    fun handleCallbackQuery(callbackQuery: CallbackQuery, mainLanguage: Language)

    fun handleUserPhoto(user: User, message: Message, messageId: String)
    fun handleOperatorPhoto(operator: User, message: Message, replyToMessageId: String?)

    fun handleUserVideo(user: User, message: Message, messageId: String)
    fun handleOperatorVideo(operator: User, message: Message, replyToMessageId: String?)

    fun handleUserDocument(user: User, message: Message, messageId: String)
    fun handleOperatorDocument(operator: User, message: Message, replyToMessageId: String?)

    fun saveUser(chatId: String)
    fun saveLanguageUser(message: SendMessage, language: String)
    fun saveOperatorLanguages(chatId: String, languages: Set<Language>)
    fun saveOperatorUserRelationIfNotExists(operatorChatId: String, userChatId: String)

    fun notifyOperatorOnWorkStart(chatId: String)
    fun notifyAndUpdateSessions(operatorId: String)
    fun notifyOperatorSelectLanguage(chatId: String)
    fun notifyClientOnOperatorJoin(userChatId: String)
    fun notifyOperatorOnWorkEnd(operatorChatId: String)

    fun showLanguageCountSelection(chatId: String, language: Language)
    fun showLanguageSelection(chatId: String, requiredCount: Int, language: Language)

    fun updateOperatorLanguage(user: User, lang: String)
    fun updateUserPhoneNumber(chatId: String, phoneNumber: String)
    fun updateLanguageSelection(chatId: Long, messageId: Int, requiredCount: Int, language: Language)

    fun toLanguage(code: String): Language
    fun shareContactBtn(): ReplyKeyboardMarkup
    fun startMessage(chatId: String, text: String)
    fun languageInlineKeyboard(): InlineKeyboardMarkup
    fun normalizePhoneNumber(phoneNumber: String): String
    fun removeInlineKeyboard(chatId: Long, messageId: Int)
    fun processLanguageSelection(chatId: Long, callBackData: String)
    fun adminProcessLanguageSelection(callbackQuery: CallbackQuery, user: User, language: String)
}

@Component
class TelegramBotImpl(
    private val botConfig: BotConfig,
    private val userRepository: UserRepository,
    private val operatorUsersRepository: OperatorUsersRepository,
    private val messageMappingRepository: MessageMappingRepository
) : TelegramLongPollingBot(), TelegramBot {

    private val log = LoggerFactory.getLogger(BotInitializer::class.java)
    private val operatorLanguageSelection = mutableMapOf<String, OperatorLanguageState>()

    override fun getBotUsername(): String = botConfig.botName
    override fun getBotToken(): String = botConfig.botToken

    override fun onUpdateReceived(update: Update) {
        if (update.hasMessage() && update.message.hasText()) {

            val message = update.message
            val replyMessageId = message.replyToMessage?.messageId?.toString()
            val messageId = message.messageId.toString()
            val text = message.text
            val chatId = message.chatId
            val chatIdStr = chatId.toString()

            val user = userRepository.findByChatId(chatIdStr)
            if (user == null) {
                handleNewUser(chatIdStr, text, message)
                return
            }

            if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
                val language = user.language
                sendMessageWithContactButton(
                    chatId = chatIdStr,
                    BotMessage.PHONE_ANSWER_TEXT.getText(language)
                )
                return
            }

            if (user.role != Role.OPERATOR) {
                handleRegularUserMessage(user, text, chatIdStr, messageId, message)
            } else {
                handleOperatorResponse(user, text, messageId, replyMessageId)
            }

        } else if (update.hasCallbackQuery()) {
            val callbackQuery = update.callbackQuery
            val chatId = callbackQuery.from.id.toString()
            val language = userRepository.findLangByChatId(chatId) ?: "UZB"

            userRepository.findByChatId(chatId)?.let {
                adminProcessLanguageSelection(callbackQuery, it, language)
            } ?: throw UserNotFoundException()


        } else if (update.message?.hasContact() == true) {
            val message = update.message
            val phoneNumber = message.contact.phoneNumber
            val chatId = message.chatId.toString()

            updateUserPhoneNumber(chatId, phoneNumber)
            handleContact(message)

        } else if (update.hasMessage() && update.message.hasPhoto()) {
            val message = update.message
            val messageId = message.messageId
            val chatIdStr = message.chatId.toString()
            val replyMessageId = message.replyToMessage?.messageId?.toString()

            val user = userRepository.findByChatId(chatIdStr) ?: throw UserNotFoundException()

            if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
                sendMessageWithContactButton(
                    chatIdStr,
                    BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
                )
                return
            }

            if (user.role != Role.OPERATOR) {
                handleUserPhoto(user, message, messageId.toString())
            } else {
                handleOperatorPhoto(user, message, replyMessageId)
            }

        } else if (update.hasMessage() && update.message.hasVideo()) {
            val message = update.message
            val messageId = message.messageId
            val chatIdStr = message.chatId.toString()
            val replyMessageId = message.replyToMessage?.messageId?.toString()

            val user = userRepository.findByChatId(chatIdStr) ?: throw UserNotFoundException()

            if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
                sendMessageWithContactButton(
                    chatIdStr,
                    BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
                )
                return
            }

            if (user.role != Role.OPERATOR) {
                handleUserVideo(user, message, messageId.toString())
            } else {
                handleOperatorVideo(user, message, replyMessageId)
            }
        } else if (update.hasMessage() && update.message.hasDocument()) {
            val message = update.message
            val messageId = message.messageId
            val chatIdStr = message.chatId.toString()
            val replyMessageId = message.replyToMessage?.messageId?.toString()

            val user = userRepository.findByChatId(chatIdStr) ?: throw UserNotFoundException()

            if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
                sendMessageWithContactButton(
                    chatIdStr,
                    BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
                )
                return
            }

            if (user.role != Role.OPERATOR) {
                handleUserDocument(user, message, messageId.toString())
            } else {
                handleOperatorDocument(user, message, replyMessageId)
            }
        }
    }

    override fun handleOperatorPhoto(
        operator: User,
        message: Message,
        replyToMessageId: String?
    ) {
        val operatorId = userRepository.findExactOperatorByLanguage(operator.language.name)
            ?: throw OperatorNotFoundException()

        userRepository.findByChatId(operatorId)?.let { op ->
            if (op.userEnded) {
                sendLocalizedMessage(
                    operatorId,
                    BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                    operator.language
                )
                return
            }
        }

        val photos = message.photo
        if (photos.isEmpty()) {
            return
        }

        val photo = photos.maxByOrNull { it.fileSize ?: 0 } ?: photos.last()
        val fileId = photo.fileId
        val caption = message.caption ?: ""

        val activeUsers = userRepository.findActiveUsersByOperator(operatorId)

        if (activeUsers.isEmpty()) {
            sendLocalizedMessage(
                operatorId,
                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                operator.language
            )
            return
        }

        activeUsers.forEach { userChatId ->
            try {
                val sendPhoto = SendPhoto()
                sendPhoto.chatId = userChatId
                sendPhoto.photo = InputFile(fileId)
                sendPhoto.caption = caption

                if (replyToMessageId != null) {
                    val userMessageId = messageMappingRepository.findUserMessageId(
                        operatorId,
                        replyToMessageId
                    )

                    if (userMessageId != null) {
                        sendPhoto.replyToMessageId = userMessageId
                    }
                }

                execute(sendPhoto)
                log.info("Photo sent: operator=$operatorId → user=$userChatId")
            } catch (e: TelegramApiException) {
                log.error("Failed to send photo to user $userChatId", e)
            }
        }
    }

    override fun handleUserVideo(
        user: User,
        message: Message,
        messageId: String
    ) {
        val userChatId = user.chatId
        val language = user.language

        val video = message.video ?: return
        val fileId = video.fileId
        val caption = message.caption ?: ""

        val operatorChatId = userRepository.findAvailableOperatorByLanguage(user.language.name)

        if (operatorChatId == null) {
            sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
            return
        }

        val hasActiveSession = operatorUsersRepository.hasActiveSession(operatorChatId, userChatId)

        if (hasActiveSession) {
            try {
                val operatorCaption = """
                👤 User: ${user.phoneNumber}
                ${if (caption.isNotEmpty()) "Caption: $caption" else ""}
            """.trimIndent()

                val sendVideo = SendVideo()
                sendVideo.chatId = operatorChatId
                sendVideo.video = InputFile(fileId)
                sendVideo.caption = operatorCaption

                val sentMessage = execute(sendVideo)
                val botMessageId = sentMessage.messageId.toString()

                saveMessageMapping(operatorChatId, userChatId, botMessageId, messageId)

                sendLocalizedMessage(userChatId, BotMessage.MESSAGE_SENT_TO_OPERATOR, language)
            } catch (e: TelegramApiException) {
                log.error("Failed to send video to operator", e)
            }
        } else {
            sendLocalizedMessage(userChatId, BotMessage.OPERATOR_OFFLINE, language)
        }
    }

    override fun handleOperatorVideo(
        operator: User,
        message: Message,
        replyToMessageId: String?
    ) {
        val operatorId = userRepository.findExactOperatorByLanguage(operator.language.name)
            ?: throw OperatorNotFoundException()

        userRepository.findByChatId(operatorId)?.let { op ->
            if (op.userEnded) {
                sendLocalizedMessage(
                    operatorId,
                    BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                    operator.language
                )
                return
            }
        }

        val video = message.video ?: return
        val fileId = video.fileId
        val caption = message.caption ?: ""

        val activeUsers = userRepository.findActiveUsersByOperator(operatorId)

        if (activeUsers.isEmpty()) {
            sendLocalizedMessage(
                operatorId,
                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                operator.language
            )
            return
        }

        activeUsers.forEach { userChatId ->
            try {
                val sendVideo = SendVideo()
                sendVideo.chatId = userChatId
                sendVideo.video = InputFile(fileId)
                sendVideo.caption = caption

                if (replyToMessageId != null) {
                    val userMessageId = messageMappingRepository.findUserMessageId(
                        operatorId,
                        replyToMessageId
                    )

                    if (userMessageId != null) {
                        sendVideo.replyToMessageId = userMessageId.toInt()
                    }
                }

                execute(sendVideo)
                log.info("Video sent: operator=$operatorId → user=$userChatId")
            } catch (e: TelegramApiException) {
                log.error("Failed to send video to user $userChatId", e)
            }
        }
    }

    override fun handleUserPhoto(
        user: User,
        message: Message,
        messageId: String
    ) {
        val userChatId = user.chatId
        val language = user.language

        val photos = message.photo

        if (photos.isEmpty()) {
            return
        }

        val photo = photos.maxByOrNull { it.fileSize ?: 0 } ?: photos.last()
        val fileId = photo.fileId
        val caption = message.caption ?: ""

        val operatorChatId = userRepository.findAvailableOperatorByLanguage(user.language.name)

        if (operatorChatId == null) {
            sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
            return
        }

        val hasActiveSession = operatorUsersRepository.hasActiveSession(operatorChatId, userChatId)

        if (hasActiveSession) {
            try {
                val operatorCaption = """
                👤 User: ${user.phoneNumber}
                ${if (caption.isNotEmpty()) " Caption: $caption" else ""}
            """.trimIndent()

                val sendPhoto = SendPhoto()
                sendPhoto.chatId = operatorChatId
                sendPhoto.photo = InputFile(fileId)
                sendPhoto.caption = operatorCaption

                val sentMessage = execute(sendPhoto)
                val botMessageId = sentMessage.messageId.toString()

                saveMessageMapping(operatorChatId, userChatId, botMessageId, messageId)

                sendLocalizedMessage(userChatId, BotMessage.MESSAGE_SENT_TO_OPERATOR, language)
            } catch (e: TelegramApiException) {
                log.error("Failed to send photo to operator", e)
            }
        } else {
            sendLocalizedMessage(userChatId, BotMessage.OPERATOR_OFFLINE, language)
        }
    }

    override fun sendMessageWithContactButton(chatId: String, text: String) {
        val message = SendMessage(chatId, text)
        message.replyMarkup = shareContactBtn()

        try {
            execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error sending message with contact button to $chatId", e)
        }
    }

    private fun handleOperatorResponse(operator: User, text: String, messageId: String, replyMessageId: String?) {
        val operatorLanguage = operator.language.name
        val operatorLanguageEnum = operator.language
        val operatorDbId = operator.id
        val operatorId = userRepository.findExactOperatorByLanguage(operatorLanguage)
            ?: throw OperatorNotFoundException()

        if (text == END) {
//            handleEndCommand(operatorId, operatorLanguage, operatorDbId!!)
            notifyAndUpdateSessions(operatorId)
            return
        }

        val languages = operatorUsersRepository.findLanguagesOperator(operator.id!!)

        when (text) {
            START -> {
                userRepository.updateBusyByChatId(operatorId)
                notifyOperatorSelectLanguage(operatorId)
                return
            }

            BEGIN -> {
                userRepository.updateOperatorEndedStatusToTrue(operatorId)
                val currentSessions = operatorUsersRepository.findActiveSessionsByOperator(operatorId)
                if (currentSessions.isNotEmpty()) {
                    sendLocalizedMessage(operatorId, BotMessage.OPERATOR_WARN_MESSAGE, operatorLanguageEnum)
                    return
                }

                var foundUser: String? = null

                for (language in languages) {
                    val waitingUser = userRepository.findFirstWaitingUserByLanguage(language)
                    if (waitingUser != null) {
                        foundUser = waitingUser
                        break
                    }
                }

                if (foundUser == null) {
                    sendLocalizedMessage(
                        operatorId,
                        BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                        Language.valueOf(operatorLanguage)
                    )
                    return
                }

                try {
                    userRepository.updateBusyByChatId(operatorId)
                    saveOperatorUserRelationIfNotExists(operatorId, foundUser)
                    notifyOperatorOnWorkStart(operatorId)
                    notifyClientOnOperatorJoin(foundUser)

                    log.info(" Operator $operatorId connected with user $foundUser ")
                } catch (ex: Exception) {
                    log.error("Failed to start session for operator=$operatorId user=$foundUser", ex)
                    userRepository.updateBusyByChatId(operatorId)
                }
            }

            else -> {
                handleOperatorMessage(operatorId, text, operatorLanguage, replyMessageId)
            }
        }
    }

    private fun handleOperatorMessage(
        operatorChatId: String,
        text: String,
        operatorLanguage: String,
        replyToMessageId: String?
    ) {
        userRepository.findByChatId(operatorChatId)?.let { operator ->
            if (operator.userEnded) {
                sendLocalizedMessage(
                    operatorChatId,
                    BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                    Language.valueOf(operatorLanguage)
                )
                return
            }

            val activeUsers = userRepository.findActiveUsersByOperator(operatorChatId)

            if (activeUsers.isEmpty()) {
                sendLocalizedMessage(
                    operatorChatId,
                    BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                    Language.valueOf(operatorLanguage)
                )
                return
            }

            activeUsers.forEach { userChatId ->
                try {
                    if (replyToMessageId != null) {
                        val userMessageId = messageMappingRepository.findUserMessageId(
                            operatorChatId,
                            replyToMessageId
                        )

                        if (userMessageId != null) {
                            sendReplyMessage(userChatId, text, userMessageId)
                            log.info(" Reply sent: operator=$operatorChatId → user=$userChatId (replyTo=$userMessageId)")
                        } else {
                            val sentMessage = execute(SendMessage(userChatId, text))
                            saveMessageMapping(operatorChatId,userChatId, sentMessage.messageId.toString(), userMessageId.toString())
//                            sendMessage(userChatId, text)
                            log.info("⚠ Mapping not found for bot message $replyToMessageId, sent as normal")
                        }
                    } else {
                        val sentMessage = execute(SendMessage(userChatId, text))

                        saveMessageMapping(operatorChatId,userChatId, sentMessage.messageId.toString(), sentMessage.messageId.toString())
//                        sendMessage(userChatId, text)
                        log.info(" Message sent: operator=$operatorChatId → user=$userChatId")
                    }
                } catch (e: TelegramApiException) {
                    log.error(" Failed to send message to user $userChatId", e)
                }
            }
        } ?: throw OperatorNotFoundException()
    }

    private fun sendReplyMessage(chatId: String, text: String, replyToMessageId: Int) {
        val message = SendMessage()
        message.chatId = chatId
        message.text = text
        message.replyToMessageId = replyToMessageId

        try {
            execute(message)
        } catch (e: TelegramApiException) {
            if (e.message?.contains("message to reply not found") == true) {
                log.warn("⚠ Reply message not found, sending normal message")
                sendMessage(chatId, text)
            } else {
                throw e
            }
        }
    }

    private fun handleEndCommand(operatorId: String, operatorLanguage: String, operatorDbId: Long) {
        val currentUser = operatorUsersRepository.findCurrentUserByOperator(operatorId)

        if (currentUser != null) {
            operatorUsersRepository.updateSession(operatorId, currentUser)

            userRepository.findLanguageByChatId(currentUser)?.let { userLang ->
                sendLocalizedMessage(currentUser, BotMessage.END_SESSION, Language.valueOf(userLang))
            }
            sendLocalizedMessage(operatorId, BotMessage.THANK_YOU, Language.valueOf(operatorLanguage))
            log.info("Operator $operatorId ended session with user $currentUser")

            val languages = operatorUsersRepository.findLanguagesOperator(operatorDbId)

            var nextUser: String? = null

            for (lang in languages) {
                val waiting = userRepository.findFirstWaitingUserByLanguage(lang)
                if (waiting != null) {
                    nextUser = waiting
                    break
                }
            }

            if (nextUser != null) {
                saveOperatorUserRelationIfNotExists(operatorId, nextUser)
                notifyClientOnOperatorJoin(nextUser)
                notifyOperatorOnWorkStart(operatorId)

            } else {
                userRepository.updateBusyByChatId(operatorId)
                sendLocalizedMessage(
                    operatorId,
                    BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                    Language.valueOf(operatorLanguage)
                )
            }
        } else {
            sendLocalizedMessage(
                operatorId,
                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                Language.valueOf(operatorLanguage)
            )
        }
    }

    override fun notifyAndUpdateSessions(operatorId: String) {
        notifyOperatorOnWorkEnd(operatorId)
        userRepository.updateBusyEndByChatId(operatorId)
        userRepository.updateOperatorEndedStatus(operatorId)
        operatorUsersRepository.updateSession(operatorId, null)
        log.info("Operator with $operatorId ended work")
    }

    private fun handleRegularUserMessage(
        user: User,
        text: String,
        chatId: String,
        messageId: String,
        message: Message
    ) {
        val userChatId = user.chatId
        val language = user.language

        when (text) {
            START -> {
                userRepository.updateUserEndedStatusToFalse(userChatId)
                return
            }

            HELP -> {
                sendLocalizedMessage(userChatId, BotMessage.HELP_TEXT, language)
                return
            }

            END -> {
                operatorUsersRepository.updateSession(null, userChatId)
                userRepository.updateUserEndedStatus(userChatId)
                sendLocalizedMessage(userChatId, BotMessage.END_SESSION, language)
                return
            }
        }

        val operatorChatId = userRepository.findAvailableOperatorByLanguage(user.language.name)

        if (operatorChatId == null) {
            sendLocalizedMessage(chatId, BotMessage.NO_OPERATOR_AVAILABLE, user.language)
        } else {
            val hasActiveSession = operatorUsersRepository.hasActiveSession(operatorChatId, user.chatId)

            if (hasActiveSession) {
                val replyToMessage = message.replyToMessage
                var replyToBotMessageId: Int? = null

                if (replyToMessage != null) {
                    replyToBotMessageId = messageMappingRepository.findBotMessageId(
                        userChatId,
                        replyToMessage.messageId.toString()
                    )
                }
                val operatorMessage = """
                📩 Xabar: $text
                👤 User: ${user.phoneNumber}
            """.trimIndent()

                val sendMsg = SendMessage(operatorChatId, operatorMessage)

                if (replyToBotMessageId != null) {
                    sendMsg.replyToMessageId = replyToBotMessageId
                }

//                val sentMessage = execute(sendMsg) TODO()
//                val botMessageId = sentMessage.messageId.toString()
//
//                saveMessageMapping(operatorChatId, userChatId, botMessageId, messageId)

                sendMessageToOperator(operatorChatId, operatorMessage)
                sendLocalizedMessage(chatId, BotMessage.MESSAGE_SENT_TO_OPERATOR, user.language)
            } else {
                sendLocalizedMessage(chatId, BotMessage.OPERATOR_OFFLINE, user.language)
            }
        }
    }

    private fun saveMessageMapping(
        operatorChatId: String,
        userChatId: String,
        botMessageId: String,
        userMessageId: String
    ) {
        try {
            val mapping = MessageMapping(
                operatorChatId = operatorChatId,
                userChatId = userChatId,
                botMessageId = botMessageId,
                userMessageId = userMessageId
            )
            messageMappingRepository.save(mapping)

            log.info(" Saved mapping: bot=$botMessageId → user=$userMessageId")
        } catch (e: Exception) {
            log.error(" Failed to save message mapping", e)
        }
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

    override fun sendMessageToOperator(operatorChatId: String, text: String) {
        try {
            execute(SendMessage(operatorChatId, text))
        } catch (e: TelegramApiException) {
            log.error("Error sending operator", e)
        }
    }

    override fun adminProcessLanguageSelection(callbackQuery: CallbackQuery, user: User, language: String) {
        if (user.role == Role.OPERATOR) {
            handleCallbackQuery(callbackQuery, Language.valueOf(language))
        } else {
            handleCallbackQueryUser(callbackQuery)
        }
    }

    override fun handleUserDocument(user: User, message: Message, messageId: String) {

        val userChatId = user.chatId
        val language = user.language

        val document = message.document
        val fileId = document.fileId
//        val fileName = document.fileName
        val caption = message.caption ?: ""

        val operatorChatId = userRepository.findAvailableOperatorByLanguage(user.language.name)

        if (operatorChatId == null) {
            sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
            return
        }

        val hasActiveSession = operatorUsersRepository.hasActiveSession(operatorChatId, userChatId)

        if (hasActiveSession) {
            try {
                val operatorCaption = """
                👤 User: ${user.phoneNumber}
                ${if (caption.isNotEmpty()) "Caption: $caption" else ""}
            """.trimIndent()

                val sendDocument = SendDocument()
                sendDocument.chatId = operatorChatId
                sendDocument.document = InputFile(fileId)
                sendDocument.caption = operatorCaption

                val sentMessage = execute(sendDocument)
                val botMessageId = sentMessage.messageId.toString()


                sendLocalizedMessage(userChatId, BotMessage.MESSAGE_SENT_TO_OPERATOR, language)
            } catch (e: TelegramApiException) {
                log.error("Failed to send video to operator", e)
            }
        } else {
            sendLocalizedMessage(userChatId, BotMessage.OPERATOR_OFFLINE, language)
        }
    }

    override fun handleOperatorDocument(operator: User, message: Message, replyToMessageId: String?) {
        val operatorId = userRepository.findExactOperatorByLanguage(operator.language.name)
            ?: throw OperatorNotFoundException()

        userRepository.findByChatId(operatorId)?.let { op ->
            if (op.userEnded) {
                sendLocalizedMessage(
                    operatorId,
                    BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                    operator.language
                )
                return
            }
        }

        val document = message.document ?: return
        val fileId = document.fileId
        val caption = message.caption ?: ""

        val activeUsers = userRepository.findActiveUsersByOperator(operatorId)

        if (activeUsers.isEmpty()) {
            sendLocalizedMessage(
                operatorId,
                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                operator.language
            )
            return
        }

        activeUsers.forEach { userChatId ->
            try {
                val sendDocument = SendDocument()
                sendDocument.chatId = userChatId
                sendDocument.document = InputFile(fileId)
                sendDocument.caption = caption

                if (replyToMessageId != null) {
                    val userMessageId = messageMappingRepository.findUserMessageId(
                        operatorId,
                        replyToMessageId
                    )

                    if (userMessageId != null) {
                        sendDocument.replyToMessageId = userMessageId.toInt()
                    }
                }

                execute(sendDocument)
                log.info("Video sent: operator=$operatorId → user=$userChatId")
            } catch (e: TelegramApiException) {
                log.error("Failed to send video to user $userChatId", e)
            }
        }
    }

    override fun notifyOperatorOnWorkEnd(operatorChatId: String) {
        userRepository.findLanguageByChatId(operatorChatId)?.let { lang ->
            val language = when (lang) {
                "RUS" -> Language.RUS
                "ENG" -> Language.ENG
                else -> Language.UZB
            }

            try {
                sendLocalizedMessage(
                    operatorChatId,
                    BotMessage.THANK_YOU,
                    language
                )
            } catch (ex: TelegramApiException) {
                log.warn("notifyOperatorOnWorkEnd", ex)
            }
        } ?: throw OperatorNotFoundException()
    }

    override fun notifyClientOnOperatorJoin(userChatId: String) {
        userRepository.findLanguageByChatId(userChatId)?.let { userLanguage ->
            val language = when (userLanguage) {
                "RUS" -> Language.RUS
                "ENG" -> Language.ENG
                else -> Language.UZB
            }

            try {
                sendLocalizedMessage(
                    userChatId,
                    BotMessage.OPERATOR_JOINED,
                    language
                )
            } catch (ex: TelegramApiException) {
                log.warn("notifyClientOnOperatorJoin", ex)
            }
        } ?: throw UserNotFoundException()
    }

    override fun notifyOperatorOnWorkStart(chatId: String) {
        userRepository.findLanguageByChatId(chatId)?.let { lang ->
            val language = when (lang) {
                "RUS" -> Language.RUS
                "ENG" -> Language.ENG
                else -> Language.UZB
            }
            try {
                sendLocalizedMessage(
                    chatId,
                    BotMessage.START_WORK,
                    language
                )
            } catch (ex: TelegramApiException) {
                log.warn("notifyOperatorOnWorkStart", ex)
            }
        } ?: throw OperatorNotFoundException()
    }

    override fun notifyOperatorSelectLanguage(chatId: String) {
        userRepository.findLanguageByChatId(chatId)?.let { lang ->
            val language = when (lang) {
                "RUS" -> Language.RUS
                "ENG" -> Language.ENG
                else -> Language.UZB
            }
            try {
                showLanguageCountSelection(chatId, language)
            } catch (ex: TelegramApiException) {
                log.warn("notifyOperatorOnWorkStart", ex)
            }
        }
    }

    override fun showLanguageCountSelection(chatId: String, language: Language) {
        val message = SendMessage(
            chatId,
            BotMessage.OPERATOR_SELECT_LANGUAGE_COUNT.getText(language)
        )

        val keyboard = InlineKeyboardMarkup()
        val row = mutableListOf<InlineKeyboardButton>()

        for (i in 1..3) {
            row.add(InlineKeyboardButton().apply {
                text = "$i"
                callbackData = "${CALLBACK_LANGUAGE_COUNT_PREFIX}$i"
            })
        }

        keyboard.keyboard = listOf(row)
        message.replyMarkup = keyboard

        try {
            execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error showing language count selection", e)
        }
    }

    override fun showLanguageSelection(chatId: String, requiredCount: Int, language: Language) {
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
            execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error showing language selection", e)
        }
    }

    override fun updateLanguageSelection(chatId: Long, messageId: Int, requiredCount: Int, language: Language) {
        val state = operatorLanguageSelection[chatId.toString()]!!

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
            editMessage.chatId = chatId.toString()
            editMessage.messageId = messageId
            editMessage.replyMarkup = keyboard
            execute(editMessage)
        } catch (e: TelegramApiException) {
            log.error("Error updating language selection", e)
        }
    }

    override fun handleCallbackQuery(callbackQuery: CallbackQuery, mainLanguage: Language) {
        val callBackData = callbackQuery.data
        val chatId = callbackQuery.from.id.toString()
        val messageId = callbackQuery.message?.messageId ?: return

        when {
            callBackData.startsWith(CALLBACK_LANGUAGE_COUNT_PREFIX) -> {
                val count = callBackData.removePrefix(CALLBACK_LANGUAGE_COUNT_PREFIX).toInt()
                operatorLanguageSelection[chatId] = OperatorLanguageState(requiredCount = count)
                removeInlineKeyboard(chatId.toLong(), messageId)
                showLanguageSelection(chatId, count, mainLanguage)

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
                updateLanguageSelection(chatId.toLong(), messageId, state.requiredCount, mainLanguage)
            }

            callBackData == CALLBACK_LANGUAGE_CONFIRM -> {
                val state = operatorLanguageSelection[chatId]!!

                if (state.selectedLanguages.size == state.requiredCount) {
                    saveOperatorLanguages(chatId, state.selectedLanguages)
                    removeInlineKeyboard(chatId.toLong(), messageId)
                    sendMessage(
                        chatId,
                        BotMessage.OPERATOR_LANGUAGES_SAVED.getText(mainLanguage)
                    )

                    operatorLanguageSelection.remove(chatId)
                } else {
                    sendMessage(
                        chatId,
                        BotMessage.OPERATOR_SELECT_MORE_LANGUAGES.getText(
                            Language.UZB,
                            "total" to state.requiredCount.toString(),
                            "count" to state.selectedLanguages.size.toString()
                        )
                    )
                }
            }

            else -> {
                removeInlineKeyboard(chatId.toLong(), messageId)
                processLanguageSelection(chatId.toLong(), callBackData)
            }
        }
    }


    override fun saveOperatorLanguages(chatId: String, languages: Set<Language>) {
        userRepository.clearOperatorLanguages(chatId)

        languages.forEach { language ->
            userRepository.addOperatorLanguage(chatId, language.name)
        }
        log.info("Operator $chatId languages saved: $languages")
    }


    override fun handleContact(message: Message) {
        val chatId = message.chatId.toString()

        val lang = userRepository.findLangByChatId(chatId) ?: "UZB"

        val language = when (lang) {
            "RUS" -> Language.RUS
            "ENG" -> Language.ENG
            else -> Language.UZB
        }
        sendLocalizedMessage(chatId, BotMessage.HANDLE_CONTACT, language)
    }

    override fun sendContact(chatId: Long, lang: String) {
        val user = userRepository.findByChatId(chatId.toString()) ?: run {
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
            updateOperatorLanguage(user, lang)
        } else {
            sendContactToRegularUser(chatId, lang)
        }
    }

    override fun updateOperatorLanguage(user: User, lang: String) {
        user.language = toLanguage(lang)
        userRepository.save(user)
    }

    override fun sendContactToRegularUser(chatId: Long, lang: String) {
        val keyboardMarkup = shareContactBtn()
        val message = SendMessage(
            chatId.toString(),
            sendContactAnswerTextToUser(lang)
        ).apply {
            replyMarkup = keyboardMarkup
        }
        saveLanguageUser(message, lang)
        sendMessage(message)
    }


    override fun sendContactAnswerTextToUser(lang: String): String =
        BotMessage.SHARE_CONTACT.getText(lang)


    override fun toLanguage(code: String): Language =
        when (code) {
            "ENG" -> Language.ENG
            "RUS" -> Language.RUS
            else -> Language.UZB
        }


    override fun saveLanguageUser(message: SendMessage, language: String) {
        userRepository.findByChatId(message.chatId)?.let { user ->
            val lang = when (language) {
                "ENG" -> Language.ENG
                "RUS" -> Language.RUS
                else -> Language.UZB
            }
            user.language = lang
            userRepository.save(user)
        }
    }

    override fun saveUser(chatId: String) {
        userRepository.save(
            User(
                chatId = chatId,
                phoneNumber = "",
                busy = false,
                language = Language.UZB,
                role = Role.USER
            )
        )
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

    override fun sendMessage(text: SendMessage) {
        try {
            execute(text)
        } catch (e: TelegramApiException) {
            println("Error: $e")
        }
    }

    override fun sendMessage(chatId: String, text: String) {
        val removeKeyboard = ReplyKeyboardRemove(true)
        val message = SendMessage(chatId, text)
        message.replyMarkup = removeKeyboard

        try {
            execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error sending message to $chatId", e)
        }
    }

    override fun startMessage(chatId: String, text: String) {
        val message = SendMessage(chatId, text)
        message.replyMarkup = languageInlineKeyboard()
        try {
            execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error sending message to $chatId", e)
        }
    }

    override fun languageInlineKeyboard(): InlineKeyboardMarkup {
        val uzBtn = InlineKeyboardButton().apply {
            text = "UZB 🇺🇿"
            callbackData = "UZB_BUTTON"
        }
        val ruBtn = InlineKeyboardButton().apply {
            text = "RUS 🇷🇺"
            callbackData = "RUS_BUTTON"
        }
        val enBtn = InlineKeyboardButton().apply {
            text = "ENG 🇬🇧"
            callbackData = "ENG_BUTTON"
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

    override fun handleCallbackQueryUser(callbackQuery: CallbackQuery) {
        val callBackData = callbackQuery.data
        val chatId = callbackQuery.from.id
        val messageId = callbackQuery.message?.messageId ?: return

        removeInlineKeyboard(chatId, messageId)
        processLanguageSelection(chatId, callBackData)
    }

    override fun removeInlineKeyboard(chatId: Long, messageId: Int) {
        try {
            execute(EditMessageReplyMarkup().apply {
                this.chatId = chatId.toString()
                this.messageId = messageId
                this.replyMarkup = null
            })
        } catch (e: TelegramApiException) {
            log.error("Failed to remove inline keyboard for chat: $chatId", e)
        }
    }

    override fun processLanguageSelection(chatId: Long, callBackData: String) {
        val language = when (callBackData) {
            "UZB_BUTTON" -> "UZB"
            "RUS_BUTTON" -> "RUS"
            "ENG_BUTTON" -> "ENG"
            else -> return
        }
        sendContact(chatId, language)
    }

    override fun updateUserPhoneNumber(chatId: String, phoneNumber: String) {
        userRepository.findByChatId(chatId)?.let { user ->
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

    override fun handleNewUser(chatId: String, text: String, message: Message) {
        saveUser(chatId)

        when (text) {
            START -> {
                val name = message.chat.firstName ?: ""
                val welcome = BotMessage.WELCOME_MESSAGE.getText(
                    Language.UZB,
                    "name" to name
                )
                startMessage(chatId, welcome)
            }

            else -> {
                sendMessage(chatId, "Iltimos, /start bosing!")
            }
        }
    }
}
