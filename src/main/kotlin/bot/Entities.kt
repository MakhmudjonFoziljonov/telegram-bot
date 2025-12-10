package bot

import jakarta.persistence.*
import org.hibernate.annotations.ColumnDefault
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.util.*

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
class BaseEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @CreatedDate @Temporal(TemporalType.TIMESTAMP) var createdDate: Date? = null,
    @LastModifiedDate @Temporal(TemporalType.TIMESTAMP) var modifiedDate: Date? = null,
    @CreatedBy var createdBy: Long? = null,
    @LastModifiedBy var lastModifiedBy: Long? = null,
    @Column(nullable = false) @ColumnDefault(value = "false") var deleted: Boolean = false
)


@Entity(name = "users")
class User(
    val chatId: String,
    var phoneNumber: String,
    var busy: Boolean,

    @Enumerated(EnumType.STRING)
    var language: Language = Language.UZB,
    @Enumerated(EnumType.STRING)
    var role: Role,

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "user_languages",
        joinColumns = [JoinColumn(name = "user_chat_id")]
    )
    @Enumerated(EnumType.STRING)
    var languages: MutableSet<Language> = mutableSetOf(language),

    val userEnded: Boolean = false,
    val name: String

) : BaseEntity()

@Entity
@Table(name = "operator_users")
class OperatorUsers(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    var operatorChatId: String,
    var userChatId: String,
    var session: Boolean = true
)

@Entity
@Table(name = "message_mappings")
class MessageMapping(

    var operatorChatId: String,
    var userChatId: String,

    var operatorMessageId: String,
    var userMessageId: String,

    var createdAt: Long = System.currentTimeMillis()
) : BaseEntity()