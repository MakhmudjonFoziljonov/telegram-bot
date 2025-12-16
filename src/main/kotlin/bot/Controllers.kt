package bot

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
class UsersController(
    private val userRepository: UserRepository
) {

    @GetMapping("change-role/{chatId}")
    fun changeRole(@PathVariable("chatId") chatId: String) {
        val user = userRepository.findByChatIdAndDeletedFalse(chatId) ?: throw UserNotFoundException()
        user.role = Role.OPERATOR
        userRepository.save(user)
    }
}