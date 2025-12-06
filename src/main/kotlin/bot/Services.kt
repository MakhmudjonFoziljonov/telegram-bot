package bot


import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.*
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

interface TelegramBot {
    fun startMessage(chatId: String, text: String)

    fun sendMessage(text: SendMessage)
    fun sendContact(chatId: Long, lang: String)
    fun sendMessage(chatId: String, text: String)
    fun sendContactToRegularUser(chatId: Long, lang: String)
    fun sendMessageToOperator(operatorChatId: String, text: String)
    fun sendMessageWithContactButton(chatId: String, text: String)
    fun sendContactAnswerTextToUser(lang: String): String

    fun handleContact(message: Message)
    fun handleOperatorMesage(operator: User, text: String)
    fun handleCallbackQueryUser(callbackQuery: CallbackQuery)
    fun handleNewUser(chatId: String, text: String, message: Message)
    fun handleRegularUserMessage(user: User, text: String, chatId: String)
    fun handleCallbackQuery(callbackQuery: CallbackQuery, mainLanguage: Language)

    fun saveUser(chatId: String)
    fun saveLanguageUser(message: SendMessage, language: String)
    fun saveOperatorLanguages(chatId: String, languages: Set<Language>)
    fun saveOperatorUserRelationIfNotExists(operatorChatId: String, userChatId: String)

    fun adminProcessLanguageSelection(callbackQuery: CallbackQuery, user: User, language: String)

    fun notifyOperatorOnWorkEnd(operatorChatId: String)
    fun notifyAndUpdateSessions(operatorId: String, operatorLanguage: String)
    fun notifyClientOnOperatorJoin(userChatId: String)
    fun notifyOperatorOnWorkStart(chatId: String)
    fun notifyOperatorSelectLanguage(chatId: String)

    fun showLanguageCountSelection(chatId: String, language: Language)
    fun showLanguageSelection(chatId: String, requiredCount: Int, language: Language)

    fun shareContactBtn(): ReplyKeyboardMarkup

    fun updateOperatorLanguage(user: User, lang: String)
    fun updateUserPhoneNumber(chatId: String, phoneNumber: String)
    fun updateLanguageSelection(chatId: Long, messageId: Int, requiredCount: Int, language: Language)

    fun toLanguage(code: String): Language

    fun languageInlineKeyboard(): InlineKeyboardMarkup
    fun removeInlineKeyboard(chatId: Long, messageId: Int)
    fun processLanguageSelection(chatId: Long, callBackData: String)
    fun normalizePhoneNumber(phoneNumber: String): String
    fun changePhoneNumber(chatId: String, operatorLanguage: String)
}

private const val CALLBACK_LANGUAGE_COUNT_PREFIX = "LANG_COUNT_"
private const val CALLBACK_LANGUAGE_SELECT_PREFIX = "LANG_SELECT_"
private const val CALLBACK_LANGUAGE_CONFIRM = "LANG_CONFIRM"

@Component
class TelegramBotImpl(
    private val botConfig: BotConfig,
    private val userRepository: UserRepository,
    private val operatorUsersRepository: OperatorUsersRepository

) : TelegramLongPollingBot(), TelegramBot {

    private val log = LoggerFactory.getLogger(BotInitializer::class.java)
    private val operatorLanguageSelection = mutableMapOf<String, OperatorLanguageState>()

    private val waitingQueues: ConcurrentHashMap<Language, ConcurrentLinkedQueue<String>> = ConcurrentHashMap()
    private val pendingMessages: ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> = ConcurrentHashMap()
    private val pendingPhoneChanges: ConcurrentHashMap<String, String> = ConcurrentHashMap()


    override fun getBotUsername(): String = botConfig.botName
    override fun getBotToken(): String = botConfig.botToken
    override fun onUpdateReceived(update: Update) {
        if (update.hasMessage() && update.message.hasText()) {

            val message = update.message
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
                handleRegularUserMessage(user, text, chatIdStr)
            } else {
                handleOperatorMesage(user, text)
            }

        } else if (update.hasCallbackQuery()) {
            val callbackQuery = update.callbackQuery
            val chatId = callbackQuery.from.id.toString()
            val language = userRepository.findLangByChatId(chatId) ?: "UZB"

            userRepository.findByChatId(chatId)?.let {
                if (it.role == Role.OPERATOR) {
                    adminProcessLanguageSelection(callbackQuery, it, language)
                } else {
                    handleCallbackQueryUser(callbackQuery)
                }
            } ?: throw UserNotFoundException()


        } else if (update.message?.hasContact() == true) {
            val message = update.message
            val currentPhoneNumber = message.contact.phoneNumber
            val chatId = message.chatId.toString()

            val operatorLanguage = userRepository.findLanguageByChatId(chatId) ?: "UZB"
            val operator = userRepository.checkOnOperator(chatId)

            val user = userRepository.findByChatId(chatId) ?: throw UserNotFoundException()

            if (operator) {
                sendLocalizedMessage(
                    chatId,
                    BotMessage.OPERATOR_CONTACT_NOT_NEEDED,
                    Language.valueOf(operatorLanguage)
                )
                return
            }

            val normalizePhoneNumber = normalizePhoneNumber(currentPhoneNumber)
            val phoneNumber = user.phoneNumber

            if (phoneNumber.isEmpty()) {
                updateUserPhoneNumber(chatId, currentPhoneNumber)
                sendLocalizedMessage(
                    chatId,
                    BotMessage.USER_CONTACT_ANSWER_MESSAGE,
                    Language.valueOf(operatorLanguage)
                )
                return
            }

            if (phoneNumber != normalizePhoneNumber) {
                addPendingPhoneChange(chatId, normalizePhoneNumber)

                val confirmMessage = SendMessage()
                confirmMessage.chatId = chatId
                confirmMessage.text = BotMessage.PHONE_CHANGE_CONFIRMATION.getText(
                    Language.valueOf(operatorLanguage),
                    "oldPhone" to phoneNumber,
                    "newPhone" to normalizePhoneNumber
                )
                confirmMessage.replyMarkup = contactChangeInlineKeyboard(operatorLanguage)

                try {
                    execute(confirmMessage)
                } catch (e: TelegramApiException) {
                    log.error(" Error sending confirmation to $chatId", e)
                }
            } else {
                sendLocalizedMessage(
                    chatId,
                    BotMessage.USER_CONTACT_SAME_NUMBER,
                    Language.valueOf(operatorLanguage)
                )
                log.info("⚠ User $chatId sent same phone number")
            }
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
        }   else if (update.hasMessage() && update.message.hasDocument()) {
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
        } else if (update.hasMessage() && update.message.hasVoice()) {
            val message = update.message
            val chatIdStr = message.chatId.toString()

            val user = userRepository.findByChatId(chatIdStr) ?: return

            if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
                sendMessageWithContactButton(
                    chatIdStr,
                    BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
                )
                return
            }

            if (user.role != Role.OPERATOR) {
                handleUserVoice(user, message)
            } else {
                handleOperatorVoice(user, message)
            }
        } else if (update.hasMessage() && update.message.hasAudio()) {
            val message = update.message
            val chatIdStr = message.chatId.toString()

            val user = userRepository.findByChatId(chatIdStr) ?: return

            if (user.phoneNumber.isEmpty() && user.role != Role.OPERATOR) {
                sendMessageWithContactButton(
                    chatIdStr,
                    BotMessage.PHONE_ANSWER_TEXT.getText(user.language)
                )
                return
            }

            if (user.role != Role.OPERATOR) {
                handleUserAudio(user, message)
            } else {
                handleOperatorAudio(user, message)
            }
        }

    }

    private fun handleOperatorAudio(operator: User, message: Message) {
        val operatorChatId = userRepository.findExactOperatorById(operator.id!!) ?: throw OperatorNotFoundException()

        if (operator.userEnded) {
            sendLocalizedMessage(
                operatorChatId,
                BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                operator.language
            )
            return
        }

        val audio = message.audio ?: return

        val fileId = audio.fileId
        val title = audio.title ?: "Audio"

        val activeUsers = userRepository.findActiveUsersByOperator(operatorChatId)

        if (activeUsers.isEmpty()) {
            sendLocalizedMessage(
                operatorChatId,
                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                operator.language
            )
            return
        }

        activeUsers.forEach { userChatId ->
            try {
                val sendAudio = SendAudio()
                sendAudio.chatId = userChatId
                sendAudio.audio = InputFile(fileId)

                execute(sendAudio)
                log.info(" Audio sent: operator=$operatorChatId → user=$userChatId ($title)")
            } catch (e: TelegramApiException) {
                log.error(" Failed to send audio to user $userChatId", e)
            }
        }
    }

    private fun handleUserAudio(user: User, message: Message) {
        val userChatId = user.chatId
        val language = user.language

        val audio = message.audio ?: return

        val fileId = audio.fileId
        val title = audio.title ?: "Audio"

        val activeOperator = userRepository.findOperatorByActiveSession(userChatId)

        if (activeOperator != null) {
            try {
                val sendAudio = SendAudio()
                sendAudio.chatId = activeOperator
                sendAudio.audio = InputFile(fileId)

                execute(sendAudio)
                log.info(" Audio sent: user=$userChatId → operator=$activeOperator ($title)")
            } catch (e: TelegramApiException) {
                log.error(" Failed to send audio to operator $activeOperator", e)
            }
            return
        }

//        if (!isUserInQueue(language, userChatId)) {
//            enqueueUser(language, userChatId)
//        }

        sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
    }

    private fun handleOperatorVoice(operator: User, message: Message) {
        val operatorChatId = userRepository.findExactOperatorById(operator.id!!) ?: throw OperatorNotFoundException()

        if (operator.userEnded) {
            sendLocalizedMessage(
                operatorChatId,
                BotMessage.OPERATOR_TEXT_BEGIN_WORK,
                operator.language
            )
            return
        }

        val voice = message.voice ?: return

        val fileId = voice.fileId

        val activeUsers = userRepository.findActiveUsersByOperator(operatorChatId)

        if (activeUsers.isEmpty()) {
            sendLocalizedMessage(
                operatorChatId,
                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                operator.language
            )
            return
        }

        activeUsers.forEach { userChatId ->
            try {
                val sendVoice = SendVoice()
                sendVoice.chatId = userChatId
                sendVoice.voice = InputFile(fileId)

                execute(sendVoice)
                log.info(" Voice sent: operator=$operatorChatId → user=$userChatId")
            } catch (e: TelegramApiException) {
                log.error(" Failed to send voice to user $userChatId", e)
            }
        }
    }

    private fun handleUserVoice(user: User, message: Message) {
        val userChatId = user.chatId
        val language = user.language

        val voice = message.voice ?: return

        val fileId = voice.fileId

        val activeOperator = userRepository.findOperatorByActiveSession(userChatId)

        if (activeOperator != null) {
            try {
                val sendVoice = SendVoice()
                sendVoice.chatId = activeOperator
                sendVoice.voice = InputFile(fileId)

                execute(sendVoice)
                log.info( " Voice sent: user=$userChatId → operator=$activeOperator")
            } catch (e: TelegramApiException) {
                log.error(" Failed to send voice to operator $activeOperator", e)
            }
            return
        }
//
//        if (!isUserInQueue(language, userChatId)) {
//            enqueueUser(language, userChatId)
//        }
        sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
    }

    private fun handleUserDocument(user: User, message: Message, messageId: String) {

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
//                val operatorCaption = """
//                👤 User: ${user.phoneNumber}
//                ${if (caption.isNotEmpty()) "Caption: $caption" else ""}
//            """.trimIndent()

                val sendDocument = SendDocument()
                sendDocument.chatId = operatorChatId
                sendDocument.document = InputFile(fileId)
//                sendDocument.caption = operatorCaption
                execute(sendDocument)
//                val sentMessage = execute(sendDocument)
//                val botMessageId = sentMessage.messageId.toString()
//                sendLocalizedMessage(userChatId, BotMessage.MESSAGE_SENT_TO_OPERATOR, language)

            } catch (e: TelegramApiException) {
                log.error("Failed to send video to operator", e)
            }
        } else {
            sendLocalizedMessage(userChatId, BotMessage.OPERATOR_OFFLINE, language)
        }
    }

    private fun handleOperatorDocument(operator: User, message: Message, replyToMessageId: String?) {
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

//                if (replyToMessageId != null) {
//                    val userMessageId = messageMappingRepository.findUserMessageId(
//                        operatorId,
//                        replyToMessageId
//                    )
//
//                    if (userMessageId != null) {
//                        sendDocument.replyToMessageId = userMessageId.toInt()
//                    }
//                }

                execute(sendDocument)
                log.info("Video sent: operator=$operatorId → user=$userChatId")
            } catch (e: TelegramApiException) {
                log.error("Failed to send video to user $userChatId", e)
            }
        }
    }

    private fun handleOperatorVideo(
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

//                if (replyToMessageId != null) {
//                    val userMessageId = messageMappingRepository.findUserMessageId(
//                        operatorId,
//                        replyToMessageId
//                    )
//
//                    if (userMessageId != null) {
//                        sendVideo.replyToMessageId = userMessageId.toInt()
//                    }
//                }

                execute(sendVideo)
                log.info("Video sent: operator=$operatorId → user=$userChatId")
            } catch (e: TelegramApiException) {
                log.error("Failed to send video to user $userChatId", e)
            }
        }
    }

    private fun handleUserVideo(
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
//                val operatorCaption = """
//                👤 User: ${user.phoneNumber}
//                ${if (caption.isNotEmpty()) "Caption: $caption" else ""}
//            """.trimIndent()

                val sendVideo = SendVideo()
                sendVideo.chatId = operatorChatId
                sendVideo.video = InputFile(fileId)
//                sendVideo.caption = operatorCaption

//                val sentMessage = execute(sendVideo)
//                val botMessageId = sentMessage.messageId.toString()
//                saveMessageMapping(operatorChatId, userChatId, botMessageId, messageId)
                execute(sendVideo)

            } catch (e: TelegramApiException) {
                log.error("Failed to send video to operator", e)
            }
        } else {
            sendLocalizedMessage(userChatId, BotMessage.OPERATOR_OFFLINE, language)
        }
    }

    private fun handleUserPhoto(
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
//                val operatorCaption = """
//                👤 User: ${user.phoneNumber}
//                ${if (caption.isNotEmpty()) " Caption: $caption" else ""}
//            """.trimIndent()

                val sendPhoto = SendPhoto()
                sendPhoto.chatId = operatorChatId
                sendPhoto.photo = InputFile(fileId)
//                sendPhoto.caption = operatorCaption

//                val sentMessage = execute(sendPhoto)
//                val botMessageId = sentMessage.messageId.toString()
//                saveMessageMapping(operatorChatId, userChatId, botMessageId, messageId)
                execute(sendPhoto)
            } catch (e: TelegramApiException) {
                log.error("Failed to send photo to operator", e)
            }
        } else {
            sendLocalizedMessage(userChatId, BotMessage.OPERATOR_OFFLINE, language)
        }
    }

    private fun handleOperatorPhoto(
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

//                if (replyToMessageId != null) {
//                    val userMessageId = messageMappingRepository.findUserMessageId(
//                        operatorId,
//                        replyToMessageId
//                    )

//                    if (userMessageId != null) {
//                        sendPhoto.replyToMessageId = userMessageId
//                    }
//                }
                execute(sendPhoto)
                log.info("Photo sent: operator=$operatorId → user=$userChatId")
            } catch (e: TelegramApiException) {
                log.error("Failed to send photo to user $userChatId", e)
            }
        }
    }

    private fun addPendingPhoneChange(chatId: String, newPhone: String) {
        pendingPhoneChanges[chatId] = newPhone
    }

    private fun getPendingPhoneChange(chatId: String): String? {
        return pendingPhoneChanges[chatId]
    }

    private fun removePendingPhoneChange(chatId: String) {
        pendingPhoneChanges.remove(chatId)
    }

    private fun enqueueUser(language: Language, userChatId: String): Int {
        val queue = waitingQueues.computeIfAbsent(language) { ConcurrentLinkedQueue() }
        if (!queue.contains(userChatId)) queue.offer(userChatId)
        var pos = 1
        for (id in queue) {
            if (id == userChatId) return pos
            pos++
        }
        return pos
    }

    private fun isUserInQueue(language: Language, userChatId: String): Boolean =
        waitingQueues[language]?.contains(userChatId) ?: false

    private fun getQueuePosition(language: Language, userChatId: String): Int {
        val queue = waitingQueues[language] ?: return -1
        var pos = 1
        for (id in queue) {
            if (id == userChatId) return pos
            pos++
        }
        return -1
    }

    private fun removeFromQueue(language: Language, userChatId: String): Boolean {
        val queue = waitingQueues[language] ?: return false
        return queue.remove(userChatId)
    }

    private fun dequeueUser(language: Language): String? =
        waitingQueues[language]?.poll()


    private fun addPendingMessage(userChatId: String, text: String) {
        val q = pendingMessages.computeIfAbsent(userChatId) { ConcurrentLinkedQueue() }
        q.offer(text)
    }

    private fun getAndClearPendingMessages(userChatId: String): List<String> {
        val q = pendingMessages.remove(userChatId) ?: return emptyList()
        val out = mutableListOf<String>()
        while (true) {
            val m = q.poll() ?: break
            out.add(m)
        }
        return out
    }

    private fun deliverPendingMessagesToOperator(operatorChatId: String, userChatId: String) {
        val messages = getAndClearPendingMessages(userChatId)
        if (messages.isEmpty()) return

        messages.forEach { msg ->
            try {
                sendMessageToOperator(operatorChatId, msg)
            } catch (e: TelegramApiException) {
                log.error("Failed to deliver pending message from $userChatId to operator $operatorChatId", e)
            }
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

    override fun handleOperatorMesage(operator: User, text: String) {
        val operatorLanguage = operator.language.name
        val operatorLanguageEnum = operator.language
        val operatorDbId = operator.id
        val operatorChatId = userRepository.findExactOperatorById(operatorDbId!!) ?: throw OperatorNotFoundException()

        if (text == "/end") {
            notifyAndUpdateSessions(operatorChatId, operatorLanguage)
            return
        }

        val languages = operatorUsersRepository.findLanguagesOperator(operator.id!!)

        when (text) {
            "/start" -> {
                userRepository.updateBusyByChatId(operatorChatId)
                notifyOperatorSelectLanguage(operatorChatId)
                return
            }

            "/begin" -> {
                val endBtn = endBtn()
                val sendMessage = SendMessage()
                sendMessage.chatId = operatorChatId
                sendMessage.text = BotMessage.OPERATOR_TEXT_END_WORK.getText(operatorLanguage)
                sendMessage.replyMarkup = endBtn
                execute(sendMessage)

                userRepository.updateOperatorEndedStatusToTrue(operatorChatId)
                val currentSessions = operatorUsersRepository.findActiveSessionsByOperator(operatorChatId)
                if (currentSessions.isNotEmpty()) {
                    sendLocalizedMessage(operatorChatId, BotMessage.OPERATOR_WARN_MESSAGE, operatorLanguageEnum)
                    return
                }

                var foundUser: String? = null

                for (language in languages) {
                    val waitingUser = dequeueUser(Language.valueOf(language))
                    if (waitingUser != null) {
                        foundUser = waitingUser
                        break
                    }
                }

                if (foundUser == null) {
                    sendLocalizedMessage(
                        operatorChatId,
                        BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                        Language.valueOf(operatorLanguage)
                    )
                    return
                }

                try {
                    userRepository.updateBusyByChatId(operatorChatId)
                    deliverPendingMessagesToOperator(operatorChatId, foundUser)
                    saveOperatorUserRelationIfNotExists(operatorChatId, foundUser)

                    notifyOperatorOnWorkStart(operatorChatId)
                    notifyClientOnOperatorJoin(foundUser)

                    log.info(" Operator $operatorChatId connected with user $foundUser ")
                } catch (ex: Exception) {
                    log.error("Failed to start session for operator=$operatorChatId user=$foundUser", ex)
                    userRepository.updateBusyByChatId(operatorChatId)
                }
            }

            else -> {
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
                            sendMessage(userChatId, text)
                            log.info(" Message sent: operator=$operatorChatId → user=$userChatId")
                        } catch (e: TelegramApiException) {
                            log.error(" Failed to send message to user $userChatId", e)
                        }
                    }
                } ?: throw OperatorNotFoundException()
            }
        }
    }

    override fun notifyAndUpdateSessions(operatorId: String, operatorLanguage: String) {
        notifyOperatorOnWorkEnd(operatorId)
        userRepository.updateBusyEndByChatId(operatorId)
        userRepository.updateOperatorEndedStatus(operatorId)
        operatorUsersRepository.updateSession(operatorId, null)

        val beginBtn = beginBtn()
        val text = BotMessage.OPERATOR_TEXT_BEGIN_WORK_BUTTON.getText(operatorLanguage)
        val sendMessage = SendMessage()
        sendMessage.chatId = operatorId
        sendMessage.text = text
        sendMessage.replyMarkup = beginBtn
        execute(sendMessage)

        log.info("Operator with $operatorId ended work")
    }

    override fun handleRegularUserMessage(
        user: User,
        text: String,
        chatId: String,
    ) {
        val userChatId = user.chatId
        val language = user.language

        when (text) {
            "/start" -> {
                userRepository.updateUserEndedStatusToFalse(userChatId)
                sendLocalizedMessage(userChatId, BotMessage.NO_OPERATOR_AVAILABLE, language)
                enqueueUser(language, userChatId)
                return
            }

            "/help" -> {
                sendLocalizedMessage(userChatId, BotMessage.HELP_TEXT, language)
                return
            }

            "/end" -> {
                val activeOperator = userRepository.findOperatorByActiveSession(userChatId)

                operatorUsersRepository.updateSession(null, userChatId)
                userRepository.updateUserEndedStatus(userChatId)
                sendLocalizedMessage(userChatId, BotMessage.END_SESSION, language)

                removeFromQueue(language, userChatId)
                pendingMessages.remove(userChatId)

                if (activeOperator != null) {
                    tryConnectNextUserToOperator(activeOperator)
                }
                return
            }
        }

        val activeOperator = userRepository.findOperatorByActiveSession(userChatId)

        if (activeOperator != null) {
            sendMessageToOperator(activeOperator, text)
            return
        }

        addPendingMessage(userChatId, text)

        if (!isUserInQueue(language, userChatId)) {
            enqueueUser(language, userChatId)
        }

        val operatorChatIdV2 = userRepository.findAvailableOperatorByLanguage(user.language.name) ?: return

        val operatorBusy = operatorUsersRepository.isOperatorBusy(operatorChatIdV2)
        if (operatorBusy) {
            return
        }

        val queuedUser = dequeueUser(language)
        if (queuedUser != null) {
            if (queuedUser == userChatId) {

                saveOperatorUserRelationIfNotExists(operatorChatIdV2, userChatId)
                userRepository.updateBusyByChatId(operatorChatIdV2)
                notifyClientOnOperatorJoin(userChatId)
                notifyOperatorOnWorkStart(operatorChatIdV2)

                deliverPendingMessagesToOperator(operatorChatIdV2, userChatId)
            } else {

                saveOperatorUserRelationIfNotExists(operatorChatIdV2, queuedUser)
                userRepository.updateBusyByChatId(operatorChatIdV2)
                notifyClientOnOperatorJoin(queuedUser)
                notifyOperatorOnWorkStart(operatorChatIdV2)

                deliverPendingMessagesToOperator(operatorChatIdV2, queuedUser)

                enqueueUser(language, userChatId)
            }
            return
        }

        saveOperatorUserRelationIfNotExists(operatorChatIdV2, userChatId)
        userRepository.updateBusyByChatId(operatorChatIdV2)
        notifyClientOnOperatorJoin(userChatId)
        notifyOperatorOnWorkStart(operatorChatIdV2)

        sendMessageToOperator(operatorChatIdV2, text)
    }

    private fun tryConnectNextUserToOperator(operatorChatId: String) {
        val operatorDbId = userRepository.findByChatId(operatorChatId)?.id ?: return
        val languages = operatorUsersRepository.findLanguagesOperator(operatorDbId)

        var nextUser: String? = null

        for (lang in languages) {
            val waitingUser = dequeueUser(Language.valueOf(lang))
            if (waitingUser != null) {
                nextUser = waitingUser
                break
            }
        }

        if (nextUser != null) {
            saveOperatorUserRelationIfNotExists(operatorChatId, nextUser)
            userRepository.updateBusyByChatId(operatorChatId)
            notifyClientOnOperatorJoin(nextUser)
            notifyOperatorOnWorkStart(operatorChatId)

            deliverPendingMessagesToOperator(operatorChatId, nextUser)

            log.info("Operator $operatorChatId automatically connected to next user $nextUser")
        } else {
            userRepository.updateBusyByChatId(operatorChatId)
            val operatorLang = userRepository.findLanguageByChatId(operatorChatId) ?: "UZB"
            sendLocalizedMessage(
                operatorChatId,
                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                Language.valueOf(operatorLang)
            )
            log.info("No waiting users for operator $operatorChatId")
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
        handleCallbackQuery(callbackQuery, Language.valueOf(language))
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

    private fun endBtn(): ReplyKeyboardMarkup {
        val keyboardMarkup = ReplyKeyboardMarkup()
        keyboardMarkup.resizeKeyboard = true

        val button = KeyboardButton().apply {
            text = "/end"
        }

        val row = KeyboardRow()
        row.add(button)
        keyboardMarkup.keyboard = listOf(row)
        return keyboardMarkup
    }

    private fun beginBtn(): ReplyKeyboardMarkup {
        val keyboardMarkup = ReplyKeyboardMarkup()
        keyboardMarkup.resizeKeyboard = true

        val button = KeyboardButton().apply {
            text = "/begin"
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

    private fun contactChangeInlineKeyboard(language: String): InlineKeyboardMarkup {
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

    override fun handleCallbackQueryUser(callbackQuery: CallbackQuery) {
        val callBackData = callbackQuery.data
        val chatId = callbackQuery.from.id
        val chatIdStr = callbackQuery.from.id.toString()
        val messageId = callbackQuery.message?.messageId ?: return

        when (callBackData) {
            "YES_BUTTON" -> {
                val newPhone = getPendingPhoneChange(chatIdStr)

                if (newPhone != null) {
                    updateUserPhoneNumber(chatIdStr, newPhone)
                    removePendingPhoneChange(chatIdStr)
                    removeInlineKeyboard(chatId, messageId)

                    val user = userRepository.findByChatId(chatIdStr)
                    if (user != null) {
                        sendLocalizedMessage(chatIdStr, BotMessage.PHONE_CHANGED_SUCCESS, user.language)
                    }
                } else {
                    removeInlineKeyboard(chatId, messageId)
                    sendMessage(chatIdStr, "Xatolik yuz berdi. Qaytadan contact yuboring.")
                }
            }

            "NO_BUTTON" -> {
                removePendingPhoneChange(chatIdStr)
                removeInlineKeyboard(chatId, messageId)
                val user = userRepository.findByChatId(chatIdStr)
                if (user != null) {
                    sendLocalizedMessage(chatIdStr, BotMessage.PHONE_CHANGE_CANCELLED, user.language)
                }
            }

            else -> {
                removeInlineKeyboard(chatId, messageId)
                processLanguageSelection(chatId, callBackData)
            }
        }
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

    override fun changePhoneNumber(chatId: String, operatorLanguage: String) {
        val sendMessage = SendMessage()
        sendMessage.chatId = chatId
        sendMessage.text = BotMessage.PHONE_CHANGE_CONFIRMATION.getText(operatorLanguage)
        sendMessage.replyMarkup = contactChangeInlineKeyboard(operatorLanguage)
        try {
            execute(sendMessage)
        } catch (e: TelegramApiException) {
            log.warn("Error sending message to $chatId: changePhoneNumber", e)
        }
    }

    override fun handleNewUser(chatId: String, text: String, message: Message) {
        saveUser(chatId)

        when (text) {
            "/start" -> {
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
