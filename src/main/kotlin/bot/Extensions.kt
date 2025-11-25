package bot

import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.exceptions.TelegramApiException

fun TelegramLongPollingBot.sendLocalizedMessage(
    chatId: String,
    message: BotMessage,
    language: Language
) {
    try {
        execute(SendMessage(chatId, message.getText(language)))
    } catch (ex: TelegramApiException) {
        // error
    }
}