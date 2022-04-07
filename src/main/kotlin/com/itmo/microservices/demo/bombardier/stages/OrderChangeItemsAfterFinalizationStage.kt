package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.commonlib.annotations.InjectEventLogger
import com.itmo.microservices.commonlib.logging.EventLogger
import com.itmo.microservices.demo.bombardier.flow.CoroutineLoggingFactory
import com.itmo.microservices.demo.bombardier.external.OrderStatus
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import com.itmo.microservices.demo.bombardier.logging.OrderChangeItemsAfterFinalizationNotableEvents.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import com.itmo.microservices.demo.common.logging.EventLoggerWrapper
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

@Component
class OrderChangeItemsAfterFinalizationStage : TestStage {
    @InjectEventLogger
    lateinit var eventLog: EventLogger

    lateinit var eventLogger: EventLoggerWrapper

    override suspend fun run(userManagement: UserManagement, externalServiceApi: ExternalServiceApi): TestStage.TestContinuationType {
        eventLogger = EventLoggerWrapper(eventLog, testCtx().serviceName)
        val shouldRunStage = Random.nextBoolean()
        if (!shouldRunStage) {
            eventLogger.info(I_STATE_SKIPPED, testCtx().orderId)
            return TestStage.TestContinuationType.CONTINUE
        }

        testCtx().wasChangedAfterFinalization = true
        eventLogger.info(I_START_CHANGING_ITEMS, testCtx().orderId)

        val items = externalServiceApi.getAvailableItems(testCtx().userId!!)
        val theOrderBefore = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)
        repeat(Random.nextInt(1, 5)) {
            val itemToAdd = items.random()
            if (itemToAdd.amount < 1) return@repeat

            val amount = Random.nextInt(1, min(itemToAdd.amount, 13))
            externalServiceApi.putItemToOrder(testCtx().userId!!, testCtx().orderId!!, itemToAdd.id, amount)

            ConditionAwaiter.awaitAtMost(3, TimeUnit.SECONDS)
                .condition {
                    val theOrder = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)

                    theOrder.itemsMap.any {
                        it.key.id == itemToAdd.id &&
                                it.value == amount +
                                (theOrderBefore.itemsMap.firstNotNullOfOrNull {
                                        it2 -> if (it2.key.id == it.key.id) it2.value else null
                                } ?: 0)
                    }
                            && theOrder.status == OrderStatus.OrderCollecting
                }
                .onFailure {
                    eventLogger.error(E_ORDER_CHANGE_AFTER_FINALIZATION_FAILED, itemToAdd.id, amount, testCtx().orderId)
                    val theOrder = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)
                    println("${itemToAdd.id}: $amount ${theOrder.itemsMap.filter { it.key.id == itemToAdd.id }}")
                    println("${theOrderBefore.itemsMap.firstNotNullOfOrNull {  }}")
                    if (it != null) {
                        throw it
                    }
                    throw TestStage.TestStageFailedException("Exception instead of silently fail")
                }.startWaiting()
        }

        return TestStage.TestContinuationType.CONTINUE
    }
}