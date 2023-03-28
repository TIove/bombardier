package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.commonlib.annotations.InjectEventLogger
import com.itmo.microservices.commonlib.logging.EventLogger
import com.itmo.microservices.demo.bombardier.external.*
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import com.itmo.microservices.demo.bombardier.logging.OrderCommonNotableEvents
import com.itmo.microservices.demo.bombardier.logging.OrderFinaizingNotableEvents.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import com.itmo.microservices.demo.common.logging.EventLoggerWrapper
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class OrderFinalizingStage : TestStage {
    @InjectEventLogger
    lateinit var eventLog: EventLogger

    lateinit var eventLogger: EventLoggerWrapper


    override suspend fun run(
        userManagement: UserManagement,
        externalServiceApi: ExternalServiceApi
    ): TestStage.TestContinuationType {
        eventLogger = EventLoggerWrapper(eventLog, testCtx().serviceName)

        if (!testCtx().finalizationNeeded()) {
            eventLogger.info(I_SKIP_FINALIZING, testCtx().orderId)
            return TestStage.TestContinuationType.CONTINUE
        }

        eventLogger.info(I_START_FINALIZING, testCtx().orderId)

        val orderStateBeforeFinalizing = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)

        val bookingResult = externalServiceApi.bookOrder(testCtx().userId!!, testCtx().orderId!!)

        val orderStateAfterBooking = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)

        var failedItem : OrderItem? = null
        var bookingRecords : List<BookingLogRecord> = listOf()
        ConditionAwaiter.awaitAtMost(6, TimeUnit.SECONDS)
            .condition {
                bookingRecords = externalServiceApi.getBookingHistory(testCtx().userId!!, bookingResult.id)
                failedItem = orderStateAfterBooking.itemsMap.keys.find  { item ->
                    bookingRecords.none { it.itemId == item.id }
                }
                failedItem == null
            }
            .onFailure {
                eventLogger.error(E_BOOKING_LOG_RECORD_NOT_FOUND, bookingResult.id, failedItem!!.id, testCtx().orderId)
                if (it != null) {
                    throw it
                }
                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }.startWaiting()

        when (orderStateAfterBooking.status) { //TODO Elina рассмотреть результат discard
            OrderStatus.OrderBooked -> {
                if (bookingResult.failedItems.isNotEmpty()) {
                    eventLogger.error(E_ORDER_HAS_FAIL_ITEMS, testCtx().orderId)
                    return TestStage.TestContinuationType.FAIL
                }

                for (item in orderStateAfterBooking.itemsMap.keys) {
                    val itemRecord = bookingRecords.firstOrNull { it.itemId == item.id }
                    if (itemRecord == null || itemRecord.status != BookingStatus.SUCCESS) {
                        eventLogger.error(
                            E_ITEMS_FAIL,
                            bookingResult.id,
                            testCtx().orderId,
                            item.id,
                            itemRecord?.status
                        )
                        return TestStage.TestContinuationType.FAIL
                    }
                }
                eventLogger.info(I_SUCCESS_VALIDATE_BOOKED, testCtx().orderId)
            }
            OrderStatus.OrderCollecting -> {
                if (bookingResult.failedItems.isEmpty()) {
                    eventLogger.error(E_BOOKING_FAIL_BUT_ITEMS_SUCCESS, testCtx().orderId, bookingResult.id)
                    return TestStage.TestContinuationType.FAIL
                }

                val failed = bookingRecords
                    .filter { it.status != BookingStatus.SUCCESS }
                    .map { it.itemId }
                    .toSet()

                if (failed != bookingResult.failedItems) {
                    eventLogger.error(E_LIST_FAILED_ITEMS_MISMATCH, bookingResult.failedItems, failed)
                    return TestStage.TestContinuationType.FAIL
                }

                val failedList = orderStateAfterBooking.itemsMap.filter { it.key.id in failed }
                    .map { Triple(it.key.id, it.key.title, it.value) }

                eventLogger.info(I_SUCCESS_VALIDATE_NOT_BOOKED, testCtx().orderId, failedList)
                return TestStage.TestContinuationType.STOP
            }
            else -> {
                eventLogger.error(
                    OrderCommonNotableEvents.E_ILLEGAL_ORDER_TRANSITION,
                    orderStateAfterBooking.id,
                    orderStateBeforeFinalizing.status,
                    orderStateAfterBooking.status
                )

                return TestStage.TestContinuationType.FAIL
            }
        }

        return TestStage.TestContinuationType.CONTINUE
    }
}