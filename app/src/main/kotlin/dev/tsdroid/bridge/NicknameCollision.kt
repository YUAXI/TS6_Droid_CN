package dev.tsdroid.bridge

import dev.tslib.User

internal const val MAX_NICKNAME_COLLISION_ATTEMPTS = 20

internal fun nicknameWithCollisionSuffix(nickname: String, attempt: Int): String {
    if (attempt <= 0) return nickname
    return buildString(nickname.length + attempt) {
        append(nickname)
        repeat(attempt) { append('1') }
    }
}

internal fun hasNicknameCollision(
    users: Array<User>?,
    ownClientId: Int?,
    nickname: String,
): Boolean {
    val matchingUsers = users
        ?.filterNotNull()
        ?.filter { it.nickname?.equals(nickname, ignoreCase = true) == true }
        ?: return false

    return if (ownClientId != null) {
        matchingUsers.any { it.id != ownClientId }
    } else {
        matchingUsers.size > 1
    }
}
