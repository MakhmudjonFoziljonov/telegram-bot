package bot

data class BaseMessage(val message: String?)


data class OperatorLanguageState(
    var requiredCount: Int = 0,
    val selectedLanguages: MutableSet<Language> = mutableSetOf()
)