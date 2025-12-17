package bot

import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault
import org.telegram.telegrambots.meta.bots.AbsSender
import org.telegram.telegrambots.meta.exceptions.TelegramApiException

@Component
class BotConstants {
    val commands = listOf(
        BotCommand("start", "Start bot"),
        BotCommand("help", "Help menu"),
        BotCommand("lang", "Change language"),
        BotCommand("end", "End the bot session")
    )

    fun setCommands(bot: AbsSender) {
        try {
            bot.execute(SetMyCommands(commands, BotCommandScopeDefault(), null))
        } catch (e: TelegramApiException) {
            e.printStackTrace()
        }
    }
}
