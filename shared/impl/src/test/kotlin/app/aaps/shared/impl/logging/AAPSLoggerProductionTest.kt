package app.aaps.shared.impl.logging

import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.logging.LTag
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.slf4j.LoggerFactory

class AAPSLoggerProductionTest {

    @Test
    fun `production adapter retains error details through the Android logging provider`() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger(LTag.PUMP.tag)
        val originalLevel = logger.level
        val originalAdditivity = logger.isAdditive
        val events = mutableListOf<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                events += event
            }
        }.apply {
            this.context = context
            start()
        }
        logger.level = Level.ERROR
        logger.isAdditive = false
        logger.addAppender(appender)
        try {
            val sut = AAPSLoggerProduction(mock<L>())
            val failure = IllegalStateException("Delivery acknowledgement unavailable")

            sut.error(LTag.PUMP, "Transport failed", failure)

            assertThat(events).hasSize(1)
            val error = events.single()
            assertThat(error.loggerName).isEqualTo(LTag.PUMP.tag)
            assertThat(error.level).isEqualTo(Level.ERROR)
            assertThat(error.formattedMessage).endsWith("Transport failed")
            assertThat(error.throwableProxy.className).isEqualTo(IllegalStateException::class.java.name)
            assertThat(error.throwableProxy.message).isEqualTo(failure.message)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = originalLevel
            logger.isAdditive = originalAdditivity
        }
    }
}
