//package bot
//
//import org.telegram.telegrambots.bots.TelegramLongPollingBot
//import org.telegram.telegrambots.meta.api.methods.send.SendMessage
//import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
//import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
//import org.telegram.telegrambots.meta.exceptions.TelegramApiException
//
//fun TelegramLongPollingBot.sendLocalizedMessage(
//    chatId: String,
//    message: BotMessage,
//    language: Language
//) {
//    try {
//        execute(SendMessage(chatId, message.getText(language)))
//    } catch (ex: TelegramApiException) {
//        // error
//    }
//}
//
//fun TelegramLongPollingBot.sendLocalizedMessage(
//    chatId: String,
//    message: BotMessage,
//    language: Language,
//    replyMarkup: ReplyKeyboardMarkup? = null,
//) {
//    val text = message.getText(language)
//
//    val sendMessage = SendMessage().apply {
//        this.chatId = chatId
//        this.text = text
//        if (replyMarkup != null) {
//            this.replyMarkup = replyMarkup
//        }
//    }
//
//    try {
//        execute(sendMessage)
//    } catch (ex: TelegramApiException) {
//        ex.printStackTrace()
//    }
//}
//
//fun TelegramLongPollingBot.sendLocalizedMessage(
//    chatId: String,
//    message: BotMessage,
//    language: Language,
//    inlineMarkup: InlineKeyboardMarkup? = null,
//) {
//    val text = message.getText(language)
//
//    val sendMessage = SendMessage().apply {
//        this.chatId = chatId
//        this.text = text
//        if (inlineMarkup != null) {
//            this.replyMarkup = inlineMarkup
//        }
//    }
//
//    try {
//        execute(sendMessage)
//    } catch (ex: TelegramApiException) {
//        ex.printStackTrace()
//    }
//}