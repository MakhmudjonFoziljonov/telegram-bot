package bot

enum class Language {
    UZB, RUS, ENG
}

enum class Role {
    OPERATOR, USER
}

enum class ErrorCodes {
    USER_NOT_FOUND,
    OPERATOR_NOT_FOUND
}

enum class BotMessage(
    val uzb: String,
    val rus: String,
    val eng: String
) {
    START_WORK(
        uzb = "Ishingizda muvaffaqiyatlar tilaymiz! Mijozlarga xushmuomala bo'ling.",
        rus = "Желаем вам удачной работы!\n" +
                "Пожалуйста, будьте вежливы с клиентами.",
        eng = "We wish you success in your work!\n" +
                "Please be polite to your customers."
    ),
    HANDLE_CONTACT(
        uzb = "Rahmat! Operator siz bilan tez orada bogʻlanadi.",
        rus = "Спасибо! Оператор свяжется с вами в ближайшее время. ",
        eng = "Thank you! Our operator will contact you shortly."
    ),
    SHARE_CONTACT(
        uzb = "Telefon raqamingizni ulashing:",
        rus = "Поделитесь своим номером телефона:",
        eng = "Share your phone number:"
    ),
    WELCOME_MESSAGE(
        uzb = "Assalomu alaykum, hurmatli {name}!\n\nTilni tanlang!",
        rus = "Здравствуйте, уважаемый {name}!\n\nВыберите язык!",
        eng = "Hello, dear {name}!\n\nChoose language!"
    ),
    HELP_TEXT(
        uzb = "Yordam bo'limiga xush kelibsiz!👋 Siz quyidagi buyruqlardan foydalanishingiz mumkin:\n\n" +
                "/start - Botni boshlash\n" +
                "/lang - Tilni o'zgartirish\n" +
                "/end - Bot sessiyasini yakunlash",
        rus = "Добро пожаловать в раздел помощи!👋 Вы можете использовать следующие команды:\n\n" +
                "/start - Начать бот\n" +
                "/lang - Изменить язык\n" +
                "/end - Завершить сеанс",
        eng = "Welcome to the help section!👋 You can use the following commands:\n\n" +
                "/start - Start the bot\n" +
                "/lang - Change language\n" +
                "/end - End the session"
    ),
    PHONE_ANSWER_TEXT(
        uzb = "❌ Iltimos, telefon raqamingizni pastdagi tugma orqali yuboring!\n\n" +
                "📱 **'Kontaktni ulashish'** tugmasini bosing.",
        rus = "❌ Пожалуйста, отправьте свой номер телефона с помощью кнопки ниже!\n\n" +
                "\uD83D\uDCF1 Нажмите кнопку **«Поделиться контактом»**.\n",
        eng = "❌ Please send your phone number using the button below!\n\n" +
                "\uD83D\uDCF1 Press the **'Share Contact'** button.\n"
    ),
    OPERATOR_JOINED(
        uzb = "Hurmatli mijoz, operator siz bilan bog'landi.\nSavolingizni berishingiz mumkin!",
        rus = "Уважаемый клиент, к вам подключился оператор.\nМожете задать свой вопрос!",
        eng = "Dear customer, an operator has connected with you.\nYou can ask your question!"
    ),
    OPERATOR_TEXT_START_WORK(
        uzb = "Ishni boshlash uchun /start tugmasini bosing",
        rus = "Чтобы начать работу, нажмите кнопку /start",
        eng = "To start work, press the /start button",
    ),
    THANK_YOU(
        uzb = "Ishlaganingiz uchun rahmat!",
        rus = "Спасибо за вашу работу!",
        eng = "Thank you for your work!"
    ),
    NO_OPERATOR_AVAILABLE(
        uzb = " Hozirda operator mavjud emas. Operatorning o'rtacha javob vaqti: 5 daq",
        rus = " В данный момент оператор недоступен. Среднее время ответа оператора: 5 мин.",
        eng = " No operator is available at the moment. The operator's average response time is 5 min."
    ),
    OPERATOR_OFFLINE(
        uzb = " Operator hozirda offline. Iltimos, keyinroq urinib ko'ring.\n" +
                "/start tugmasini bosing va operator siz bilan bog'lanishini kuting!",
        rus = " Оператор сейчас не в сети. Пожалуйста, попробуйте позже. Нажмите кнопку /start\n" +
                " и ждите, когда оператор свяжется с вами!",
        eng = " Operator is currently offline. Please try again later. Press the /start\n" +
                "button and wait for the operator to contact you.\n"
    ),
    MESSAGE_SENT_TO_OPERATOR(
        uzb = "✅ Xabaringiz operatorga yuborildi. Iltimos, javobni kuting...",
        rus = "✅ Ваше сообщение отправлено оператору. Пожалуйста, ожидайте ответа...",
        eng = "✅ Your message has been sent to the operator. Please wait for a response..."
    ),
    OPERATOR_SELECT_LANGUAGE_COUNT(
        uzb = "Nechta tilda ishlaysiz? 🌍",
        rus = "На скольких языках вы работаете? 🌍",
        eng = "How many languages do you work with? 🌍"
    ),
    OPERATOR_SELECT_LANGUAGES(
        uzb = "Tillarni tanlang: (Tanlangan: {total})",
        rus = "Выберите языки: (Выбрано: {total})",
        eng = "Select languages: (Selected: {total})"
    ),
    OPERATOR_CONFIRM_LANGUAGE(
        uzb = "✅ Tasdiqlamoq",
        rus = "✅ Подтвердить",
        eng = "✅ To confirm"
    ),
    OPERATOR_ANSWER_USERS_NOT_ONLINE(
        uzb = "Hozir faol userlar yo'q!",
        rus = "Сейчас активных пользователей нет!",
        eng = "There are no active users right now!"
    ),
    OPERATOR_LANGUAGES_SAVED(
        uzb = "✅ Tillar saqlandi! Endi /begin bosib ishlashni boshlang.",
        rus = "✅ Языки сохранены! Теперь нажмите /begin, чтобы начать работу.",
        eng = "✅ Languages saved! Now press /begin to begin working."
    ),
    OPERATOR_SELECT_MORE_LANGUAGES(
        uzb = "❌ Siz {total} ta til tanlashingiz kerak! (Hozir: {count})",
        rus = "❌ Вы должны выбрать {total} языков! (Сейчас: {count})",
        eng = "❌ You must select {total} languages! (Current: {count})"
    ),
    OPERATOR_WARN_MESSAGE(
        uzb = "⚠ Avval joriy suhbatni /end bilan tugating",
        rus = "⚠ Сначала завершите текущий диалог с помощью команды /end",
        eng = "⚠ First, finish the current conversation using the /end command"
    ),
    OPERATOR_NEW_CLIENT(
        uzb = "",
        rus = "",
        eng = ""
    ),
    OPERATOR_TEXT_BEGIN_WORK(
        uzb = "Ishni boshlash uchun /begin tugmasini bosing",
        rus = "Чтобы начать работу, нажмите кнопку /begin",
        eng = "To start work, press the /begin button",
    ),
    OPERATOR_TEXT_END_WORK(
        uzb = "Ishni tugatish uchun pastdagi tugmani bosing",
        rus = "Чтобы закончить работу, нажмите кнопку ниже",
        eng = "To end work, press the button below",
    ),
    OPERATOR_TEXT_BEGIN_WORK_BUTTON(
        uzb = "Ishni boshlash uchun pastdagi tugmani bosing",
        rus = "Чтобы начать работу, нажмите кнопку ниже",
        eng = "To start work, press the button bellow",
    ),
    END_SESSION(
        uzb = "Botdan foydalanganiz uchun ming rahmat!",
        rus = "Спасибо за использование бота!",
        eng = "Thank you for using the bot!"
    );

    fun getText(language: Language): String = when (language) {
        Language.UZB -> uzb
        Language.RUS -> rus
        Language.ENG -> eng
    }

    fun getText(language: String): String = when (language) {
        "UZB" -> uzb
        "RUS" -> rus
        else -> eng
    }

    fun getText(language: Language, vararg params: Pair<String, String>): String {
        var text = getText(language)
        params.forEach { (key, value) ->
            text = text.replace("{$key}", value)
        }
        return text
    }
}