package bot


import jakarta.persistence.EntityManager
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.support.JpaEntityInformation
import org.springframework.data.jpa.repository.support.SimpleJpaRepository
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.findByIdOrNull
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

@NoRepositoryBean
interface BaseRepository<T : BaseEntity> : JpaRepository<T, Long>, JpaSpecificationExecutor<T> {
    fun findByIdAndDeletedFalse(id: Long): T?
    fun trash(id: Long): T?
    fun trashList(ids: List<Long>): List<T?>
    fun findAllNotDeleted(): List<T>
    fun saveAndRefresh(t: T): T
}

class BaseRepositoryImpl<T : BaseEntity>(
    entityInformation: JpaEntityInformation<T, Long>, private val entityManager: EntityManager
) : SimpleJpaRepository<T, Long>(entityInformation, entityManager), BaseRepository<T> {

    val isNotDeletedSpecification = Specification<T> { root, _, cb -> cb.equal(root.get<Boolean>("deleted"), false) }

    override fun findByIdAndDeletedFalse(id: Long) = findByIdOrNull(id)?.run {
        if (deleted) null else this
    }

    @jakarta.transaction.Transactional
    override fun trash(id: Long): T? = findByIdOrNull(id)?.run {
        deleted = true
        save(this)
    }

    override fun findAllNotDeleted(): List<T> = findAll(isNotDeletedSpecification)

    @jakarta.transaction.Transactional
    override fun trashList(ids: List<Long>): List<T?> = ids.map { trash(it) }

    @jakarta.transaction.Transactional
    override fun saveAndRefresh(t: T): T {
        return save(t).apply { entityManager.refresh(this) }
    }
}

interface UserRepository : BaseRepository<User> {

    fun findByChatIdAndDeletedFalse(chatId: String): User?

    @Query(
        value = """
        select chat_id
          from users
            where role = 'OPERATOR'
            and id = :operatorId
    """, nativeQuery = true
    )
    fun findExactOperatorById(operatorId: Long): String?

    @Modifying
    @Transactional
    @Query(value = "update users set busy = true where chat_id = :chatId ", nativeQuery = true)
    fun updateBusyByChatId(chatId: String)

    @Modifying
    @Transactional
    @Query(value = "update users set busy = false where chat_id = :chatId ", nativeQuery = true)
    fun updateBusyEndByChatId(chatId: String)

    @Modifying
    @Transactional
    @Query(
        value = """
            DELETE FROM user_languages 
            WHERE user_chat_id = (SELECT id FROM users WHERE chat_id = :chatId)
        """,
        nativeQuery = true
    )
    fun clearOperatorLanguages(chatId: String)

    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO user_languages (user_chat_id, languages) 
            VALUES ((SELECT u.id FROM users u WHERE u.chat_id = :chatId), :language)
        """,
        nativeQuery = true
    )
    fun addOperatorLanguage(chatId: String, language: String)

    @Query(
        value = """
        SELECT u.chat_id
        FROM users u
        JOIN user_languages ul ON u.id = ul.user_chat_id
        WHERE u.role = 'OPERATOR'
            AND u.deleted = false      
            AND ul.languages = :language
        ORDER BY u.created_date ASC
        LIMIT 1
    """, nativeQuery = true
    )
    fun findAvailableOperatorByLanguage(language: String): String?

    @Query(
        value = """
        SELECT ou.user_chat_id
        FROM operator_users ou
        WHERE ou.operator_chat_id = :operatorChatId
            AND ou.session = true
    """, nativeQuery = true
    )
    fun findActiveUsersByOperator(operatorChatId: String): List<String>

    @Modifying
    @Transactional
    @Query(
        value = "update users set user_ended = true where chat_id = :userChatId and role = 'USER' ",
        nativeQuery = true
    )
    fun updateUserEndedStatus(userChatId: String)

    @Modifying
    @Transactional
    @Query(
        value = "update users set user_ended = false where chat_id = :userChatId and role = 'USER' ",
        nativeQuery = true
    )
    fun updateUserEndedStatusToFalse(userChatId: String)

    @Modifying
    @Transactional
    @Query(
        value = "update users set user_ended = true where chat_id = :userChatId and role = 'OPERATOR' ",
        nativeQuery = true
    )
    fun updateOperatorEndedStatus(userChatId: String)

    @Modifying
    @Transactional
    @Query(
        value = "update users set user_ended = false where chat_id = :userChatId and role = 'OPERATOR' ",
        nativeQuery = true
    )
    fun updateOperatorEndedStatusToTrue(userChatId: String)

    @Query(
        """
    SELECT ou.operator_chat_id 
    FROM operator_users ou 
    WHERE ou.user_chat_id = :userChatId 
    AND ou.session = true
    LIMIT 1
""", nativeQuery = true
    )
    fun findOperatorByActiveSession(userChatId: String): String?

    @Query(
        value = "select exists(select 1 from users where chat_id = :chatId and role = 'OPERATOR')",
        nativeQuery = true
    )
    fun checkOnOperator(@Param("chatId") chatId: String): Boolean

}

interface OperatorUsersRepository : BaseRepository<OperatorUsers> {

    @Query(
        value = "select * from operator_users " +
                "where  operator_chat_id = :operatorChatId " +
                "and user_chat_id = :userChatId", nativeQuery = true
    )
    fun find(
        @Param("operatorChatId") operatorChatId: String,
        @Param("userChatId") userChatId: String
    ): OperatorUsers?

    @Modifying
    @Transactional
    @Query("UPDATE operator_users SET session = false WHERE id = :id", nativeQuery = true)
    fun deactivateById(@Param("id") id: Long)


    @Query(
        value = """
            SELECT * FROM operator_users
            WHERE operator_chat_id = :operatorChatId
            AND session = true
            ORDER BY id DESC
        """,
        nativeQuery = true
    )
    fun findActiveSessionsByOperator(operatorChatId: String): List<OperatorUsers>

    @Modifying
    @Transactional
    @Query(
        value = """
    UPDATE operator_users
    SET session = false
    WHERE (
      (:operatorChatId IS NOT NULL AND operator_chat_id = :operatorChatId)
      OR
      (:userChatId IS NOT NULL AND user_chat_id = :userChatId)
    )
    AND session = true
  """,
        nativeQuery = true
    )
    fun updateSession(
        @Param("operatorChatId") operatorChatId: String?,
        @Param("userChatId") userChatId: String?
    )

    @Query(
        value = """
        SELECT COUNT(*) > 0 FROM operator_users
        WHERE operator_chat_id = :operatorChatId 
        AND user_chat_id = :userChatId 
        AND session = true
    """,
        nativeQuery = true
    )
    fun hasActiveSession(operatorChatId: String, userChatId: String): Boolean

    @Query(value = "select languages from user_languages where user_chat_id= :id", nativeQuery = true)
    fun findLanguagesOperator(id: Long): List<String>

    @Query(
        value = """
    SELECT COUNT(*) > 0 FROM operator_users
    WHERE operator_chat_id = :operatorChatId
      AND session = true
    """,
        nativeQuery = true
    )
    fun isOperatorBusy(operatorChatId: String): Boolean
}

interface MessageMappingRepository : BaseRepository<MessageMapping> {
    fun findByOperatorMessageId(messageId: String): MessageMapping?
    fun findByUserMessageId(messageId: String): MessageMapping?

}

interface PendingMessagesRepository : BaseRepository<PendingMessages> {
    fun findByUserChatIdAndStatus(
        userChatId: String,
        status: MessageStatus
    ): List<PendingMessages>

    @Modifying
    @Transactional
    @Query(value = "update pending_messages set status = 'DELIVERED' " +
            "where user_chat_id = :userChatId and status = 'PENDING' ", nativeQuery = true)
    fun updatePendingMessagesByChatId(userChatId: String)

}

interface FileRepository : BaseRepository<File> {
    fun findCurrentUserByOperator(operatorChatId: String): String?
}