package bot


import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiException

interface TelegramBot {

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
                handleOperatorResponse(user, text)
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
        }
    }

    private fun sendMessageWithContactButton(chatId: String, text: String) {
        val message = SendMessage(chatId, text)
        message.replyMarkup = shareContactBtn()

        try {
            execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error sending message with contact button to $chatId", e)
        }
    }

    private fun handleOperatorResponse(operator: User, text: String) {
        val operatorLanguage = operator.language.name
        val operatorId = userRepository.findExactOperatorByLanguage(operatorLanguage)
            ?: throw OperatorNotFoundException()

        if (text == "/end") {
            notifyAndUpdateSessions(operatorId)
            return
        }

        val languages = operatorUsersRepository.findLanguagesOperator(operator.id!!)

        when (text) {
            "/start" -> {
                userRepository.updateBusyByChatId(operatorId)
                notifyOperatorSelectLanguage(operatorId)
                return
            }

            "/begin" -> {
                var foundAnyUser = false

                languages.forEach { language ->
                    val users = userRepository.findUserByLanguage(language)
                    if (users.isNotEmpty()) {
                        foundAnyUser = true

                        userRepository.updateBusyByChatId(operatorId)
                        notifyOperatorOnWorkStart(operatorId)

                        users.forEach { userChatId ->
                            notifyClientOnOperatorJoin(userChatId)
                            saveOperatorUserRelationIfNotExists(operatorId, userChatId)
                        }
                    }
                }

                if (!foundAnyUser) {
                    sendLocalizedMessage(
                        operatorId,
                        BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                        Language.valueOf(operatorLanguage)
                    )
                }
            }

            else -> {
                val activeUsers = userRepository.findActiveUsersByOperator(operatorId)

                if (activeUsers.isEmpty()) {
                    sendLocalizedMessage(
                        operatorId,
                        BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
                        Language.valueOf(operatorLanguage)
                    )
                    return
                }
                activeUsers.forEach { userChatId ->
                    try {
                        sendMessage(userChatId, text)
                        log.info(" Message sent: operator=$operatorId → user=$userChatId")
                    } catch (e: TelegramApiException) {
                        log.error(" Failed to send message to user $userChatId", e)
                    }
                }
            }
        }
    }
//        when (text) {
//            "/end" -> handleEndCommand(operatorId, operatorLanguage)
//
//            "/start" -> {
//                userRepository.updateBusyByChatId(operatorId)
//                notifyOperatorSelectLanguage(operatorId)
//            }
//
//            "/begin" -> handleBeginCommand(operatorId, operator.id!!, operatorLanguage)
//
//            else -> handleOperatorMessage(operatorId, text, operatorLanguage)
//        }
//
//    }

//    private fun handleEndCommand(operatorId: String, operatorLanguage: String) {
//        val currentUser = operatorUsersRepository.findCurrentUserByOperator(operatorId)
//
//        if (currentUser != null) {
//            operatorUsersRepository.updateSession(operatorId, currentUser)
//
//            userRepository.findLanguageByChatId(currentUser)?.let { userLang ->
//                sendLocalizedMessage(
//                    currentUser,
//                    BotMessage.END_SESSION,
//                    Language.valueOf(userLang)
//                )
//            }
//
//            sendLocalizedMessage(
//                operatorId,
//                BotMessage.THANK_YOU,
//                Language.valueOf(operatorLanguage)
//            )
//
//            log.info("✅ Operator $operatorId ended session with user $currentUser")
//        } else {
//            sendMessage(operatorId, "❌ Hozir hech qanday aktiv suhbat yo'q")
//        }
//    }
//        userRepository.updateBusyEndByChatId(operatorId)
//
//    private fun handleBeginCommand(
//        operatorId: String,
//        operatorDbId: Long,
//        operatorLanguage: String
//    ) {
//        // 1. Avval joriy sessiya borligini tekshirish
//        val currentUser = operatorUsersRepository.findCurrentUserByOperator(operatorId)
//
//        if (currentUser != null) {
//            sendMessage(
//                operatorId,
//                "⚠️ Avval joriy suhbatni /end bilan tugating!\n" +
//                        "Joriy user: $currentUser"
//            )
//            return
//        }
//
//        // 2. Operator tillarini olish
//        val languages = operatorUsersRepository.findLanguagesOperator(operatorDbId)
//
//        // 3. Har bir tildan kutayotgan userni topish
//        var foundUser: String? = null
//        var foundLanguage: String? = null
//
//        for (language in languages) {
//            val waitingUser = userRepository.findFirstWaitingUserByLanguage(language)
//            if (waitingUser != null) {
//                foundUser = waitingUser
//                foundLanguage = language
//                break // Birinchi topilgan userni ol
//            }
//        }
//
//        // 4. Agar user topilsa - bog'lanish
//        if (foundUser != null) {
//            // Operator busy = true
//            userRepository.updateBusyByChatId(operatorId)
//
//            // Session yaratish
//            saveOperatorUserRelationIfNotExists(operatorId, foundUser)
//
//            // Xabarlar yuborish
//            notifyOperatorOnWorkStart(operatorId)
//            notifyClientOnOperatorJoin(foundUser)
//
//            // Operatorga userning ma'lumotlarini ko'rsatish
//            val userPhone = userRepository.findPhoneByChatId(foundUser)
//            sendMessage(
//                operatorId,
//                "✅ Yangi mijoz:\n" +
//                        "📱 Tel: $userPhone\n" +
//                        "🌍 Til: $foundLanguage\n" +
//                        "💬 User ID: $foundUser"
//            )
//
//            log.info("✅ Operator $operatorId connected with user $foundUser ($foundLanguage)")
//        } else {
//            // Hech qanday kutayotgan user yo'q
//            sendLocalizedMessage(
//                operatorId,
//                BotMessage.OPERATOR_ANSWER_USERS_NOT_ONLINE,
//                Language.valueOf(operatorLanguage)
//            )
//        }
//    }
//
//    private fun handleOperatorMessage(
//        operatorId: String,
//        text: String,
//        operatorLanguage: String
//    ) {
//        // Faqat joriy aktiv user bilan gaplashish
//        val currentUser = operatorUsersRepository.findCurrentUserByOperator(operatorId)
//
//        if (currentUser == null) {
//            sendMessage(
//                operatorId,
//                "❌ Hozir aktiv suhbat yo'q!\n" +
//                        "Yangi mijoz bilan gaplashish uchun /begin bosing"
//            )
//            return
//        }
//
//        // Faqat 1 ta userga xabar yuborish
//        try {
//            sendMessage(currentUser, text)
//            log.info("✅ Message sent: operator=$operatorId → user=$currentUser")
//        } catch (e: TelegramApiException) {
//            log.error("❌ Failed to send message to user $currentUser", e)
//            sendMessage(operatorId, "❌ Xabar yuborishda xatolik!")
//        }
//    }

    private fun notifyAndUpdateSessions(operatorId: String) {
        notifyOperatorOnWorkEnd(operatorId)
        userRepository.updateBusyEndByChatId(operatorId)
        operatorUsersRepository.updateSession(operatorId, null)
        log.info("Operator with $operatorId ended work")
    }

    private fun handleRegularUserMessage(
        user: User,
        text: String,
        chatId: String,
    ) {
        val userChatId = user.chatId;
        val language = user.language

        when (text) {
            "/start" -> {
                userRepository.updateUserEndedStatus(userChatId)
                return
            }

            "/help" -> {
                sendLocalizedMessage(userChatId, BotMessage.HELP_TEXT, language)
                return
            }

            "/end" -> {
                operatorUsersRepository.updateSession(null, userChatId)
                userRepository.updateUserEndedStatus(userChatId)
                sendLocalizedMessage(userChatId, BotMessage.END_SESSION, language)
                return
            }
        }
        val operatorChatIdV2 = userRepository
            .findAvailableOperatorByLanguage(user.language.name)

        if (operatorChatIdV2 == null) {
            sendLocalizedMessage(chatId, BotMessage.NO_OPERATOR_AVAILABLE, user.language)
        } else {
            val hasActiveSession = operatorUsersRepository.hasActiveSession(operatorChatIdV2, user.chatId)

            if (hasActiveSession) {
                val operatorMessage = """
            📩 Xabar: $text
            👤 User: ${user.phoneNumber}
        """.trimIndent()

                sendMessageToOperator(operatorChatIdV2, operatorMessage)
                sendLocalizedMessage(chatId, BotMessage.MESSAGE_SENT_TO_OPERATOR, user.language)
            } else {
                sendLocalizedMessage(chatId, BotMessage.OPERATOR_OFFLINE, user.language)
            }
        }
    }

    private fun saveOperatorUserRelationIfNotExists(
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

    private fun sendMessageToOperator(operatorChatId: String, text: String) {
        try {
            execute(SendMessage(operatorChatId, text))
        } catch (e: TelegramApiException) {
            log.error("Error sending operator", e)
        }
    }

    private fun adminProcessLanguageSelection(callbackQuery: CallbackQuery, user: User, language: String) {
        if (user.role == Role.OPERATOR) {
            handleCallbackQuery(callbackQuery, Language.valueOf(language))
        } else {
            handleCallbackQueryUser(callbackQuery)
        }
    }

    private fun notifyOperatorOnWorkEnd(operatorChatId: String) {
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

    private fun notifyClientOnOperatorJoin(userChatId: String) {
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

    private fun notifyOperatorOnWorkStart(chatId: String) {
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

    private fun notifyOperatorSelectLanguage(chatId: String) {
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

    private fun showLanguageCountSelection(chatId: String, language: Language) {
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

    private fun showLanguageSelection(chatId: String, requiredCount: Int, language: Language) {
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

    private fun updateLanguageSelection(chatId: Long, messageId: Int, requiredCount: Int, language: Language) {
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

    private fun handleCallbackQuery(callbackQuery: CallbackQuery, mainLanguage: Language) {
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


    private fun saveOperatorLanguages(chatId: String, languages: Set<Language>) {
        userRepository.clearOperatorLanguages(chatId)

        languages.forEach { language ->
            userRepository.addOperatorLanguage(chatId, language.name)
        }
        log.info("Operator $chatId languages saved: $languages")
    }


    private fun handleContact(message: Message) {
        val chatId = message.chatId.toString()

        val lang = userRepository.findLangByChatId(chatId) ?: "UZB"

        val language = when (lang) {
            "RUS" -> Language.RUS
            "ENG" -> Language.ENG
            else -> Language.UZB
        }
        sendLocalizedMessage(chatId, BotMessage.HANDLE_CONTACT, language)
    }

    private fun sendContact(chatId: Long, lang: String) {
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

    private fun updateOperatorLanguage(user: User, lang: String) {
        user.language = toLanguage(lang)
        userRepository.save(user)
    }

    private fun sendContactToRegularUser(chatId: Long, lang: String) {
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


    private fun sendContactAnswerTextToUser(lang: String): String =
        BotMessage.SHARE_CONTACT.getText(lang)


    private fun toLanguage(code: String): Language =
        when (code) {
            "ENG" -> Language.ENG
            "RUS" -> Language.RUS
            else -> Language.UZB
        }


    private fun saveLanguageUser(message: SendMessage, language: String) {
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

    private fun saveUser(chatId: String) {
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

    private fun shareContactBtn(): ReplyKeyboardMarkup {
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

    private fun sendMessage(text: SendMessage) {
        try {
            execute(text)
        } catch (e: TelegramApiException) {
            println("Error: $e")
        }
    }

    private fun sendMessage(chatId: String, text: String) {
        val removeKeyboard = ReplyKeyboardRemove(true)
        val message = SendMessage(chatId, text)
        message.replyMarkup = removeKeyboard

        try {
            execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error sending message to $chatId", e)
        }
    }

    private fun startMessage(chatId: String, text: String) {
        val message = SendMessage(chatId, text)
        message.replyMarkup = languageInlineKeyboard()
        try {
            execute(message)
        } catch (e: TelegramApiException) {
            log.error("Error sending message to $chatId", e)
        }
    }

    private fun languageInlineKeyboard(): InlineKeyboardMarkup {
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

    private fun handleCallbackQueryUser(callbackQuery: CallbackQuery) {
        val callBackData = callbackQuery.data
        val chatId = callbackQuery.from.id
        val messageId = callbackQuery.message?.messageId ?: return

        removeInlineKeyboard(chatId, messageId)
        processLanguageSelection(chatId, callBackData)
    }

    private fun removeInlineKeyboard(chatId: Long, messageId: Int) {
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

    private fun processLanguageSelection(chatId: Long, callBackData: String) {
        val language = when (callBackData) {
            "UZB_BUTTON" -> "UZB"
            "RUS_BUTTON" -> "RUS"
            "ENG_BUTTON" -> "ENG"
            else -> return
        }
        sendContact(chatId, language)
    }

    private fun updateUserPhoneNumber(chatId: String, phoneNumber: String) {
        userRepository.findByChatId(chatId)?.let { user ->
            user.phoneNumber = normalizePhoneNumber(phoneNumber)
            userRepository.save(user)
        }
    }

    private fun normalizePhoneNumber(phoneNumber: String): String {
        return if (phoneNumber.startsWith("+")) {
            phoneNumber
        } else {
            "+$phoneNumber"
        }
    }

    private fun handleNewUser(chatId: String, text: String, message: Message) {
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
