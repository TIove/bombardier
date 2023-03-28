package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.commonlib.annotations.InjectEventLogger
import com.itmo.microservices.commonlib.logging.EventLogger
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import com.itmo.microservices.demo.bombardier.logging.OrderCollectingNotableEvents.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import com.itmo.microservices.demo.common.logging.EventLoggerWrapper
import org.springframework.stereotype.Component
import java.lang.Integer.min
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class OrderCollectingStage : TestStage {
    @InjectEventLogger
    private lateinit var eventLog: EventLogger

    lateinit var eventLogger: EventLoggerWrapper

    override suspend fun run(userManagement: UserManagement, externalServiceApi: ExternalServiceApi): TestStage.TestContinuationType {
        eventLogger = EventLoggerWrapper(eventLog, testCtx().serviceName)

        eventLogger.info(I_ADDING_ITEMS, testCtx().orderId)

        val itemIds = mutableMapOf<UUID, Int>()
        val items = externalServiceApi.getAvailableItems(testCtx().userId!!)
        if (items.size < 3) {
            throw IllegalStateException("Products found: ${items.size}, required: at least 2")
        }
        repeat(Random.nextInt(1, min(20, items.size))) {
            val itemToAdd = items.random()
            val amount = Random.nextInt(1, min(20, itemToAdd.amount))
            itemIds[itemToAdd.id] = amount

            externalServiceApi.putItemToOrder(testCtx().userId!!, testCtx().orderId!!, itemToAdd.id, amount)
        }

        var failedId : UUID? = null
        var failedCount : Int? = null
        var failedAmount : Int? = null
        var finalNumOfItems : Int? = null
        ConditionAwaiter.awaitAtMost(6, TimeUnit.SECONDS)
            .condition {
                val finalOrder = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)
                val orderMap = finalOrder.itemsMap.mapKeys { it.key.id }
                finalNumOfItems = finalOrder.itemsMap.size
                itemIds.all { (id, count) ->
                    val isPassed = orderMap.containsKey(id) && orderMap[id] == count
                    if (!isPassed) {
                        failedId = id
                        failedCount = count
                        failedAmount = orderMap[id] ?: 0
                    }
                    isPassed
                }
                        && finalNumOfItems == itemIds.size
            }
            .onFailure {
                if (failedId != null)
                    eventLogger.error(E_ADD_ITEMS_FAIL, failedId, failedCount, failedAmount)
                else if (finalNumOfItems != itemIds.size)
                    eventLogger.error(E_ITEMS_MISMATCH, finalNumOfItems, itemIds.size)

                if (it != null) {
                    throw it
                }
                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }.startWaiting()

        eventLogger.info(I_ORDER_COLLECTING_SUCCESS, itemIds.size, testCtx().orderId)
        return TestStage.TestContinuationType.CONTINUE
    }

}