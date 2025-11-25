package bot


import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

@Configuration
@PropertySource("classpath:application.properties")
class BotConfig(
    @Value("\${telegram.bot.name}")
    val botName: String,
    @Value("\${telegram.bot.token}")
    val botToken: String
)

@Configuration
class BotInitializer(
    private val telegramBot: TelegramBotImpl,
    private val constants: BotConstants
) {
    private val log = LoggerFactory.getLogger(BotInitializer::class.java)

    @Bean
    @EventListener(ContextRefreshedEvent::class)
    fun startBot(): TelegramBotsApi {
        val api = TelegramBotsApi(DefaultBotSession::class.java)
        try {
            api.registerBot(telegramBot)
            constants.setCommands(telegramBot)

        } catch (e: TelegramApiException) {
            log.error("Error starting bot: ${e.message}")
        }
        return api
    }
}