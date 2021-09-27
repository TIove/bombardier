package com.itmo.microservices.demo.bombardier.external.storage

import com.itmo.microservices.demo.bombardier.flow.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class UserStorage {
    private val users = ConcurrentHashMap<UUID, Pair<User, Mutex>>()

    suspend fun create(user: User): User {
        val existing = users.putIfAbsent(user.id, user to Mutex())
        if (existing != null) {
            throw IllegalArgumentException("User already exists: $user")
        }
        return user
    }

    suspend fun getAndUpdate(userId: UUID, updateFunction: suspend (User) -> User): User {
        val (_, mutex) = users[userId] ?: throw IllegalArgumentException("No such user: $userId")
        mutex.withLock {
            val (user, _) = users[userId] ?: throw IllegalArgumentException("No such user: $userId")
            val updatedOrder = updateFunction(user)
            users[userId] = updatedOrder to mutex
            return updatedOrder
        }
    }

    suspend fun get(userId: UUID) = users[userId]?.first ?: throw IllegalArgumentException("No such user: $userId")
}