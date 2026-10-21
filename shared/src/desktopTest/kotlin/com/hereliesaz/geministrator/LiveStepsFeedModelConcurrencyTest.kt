package com.hereliesaz.geministrator

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveStepsFeedModelConcurrencyTest {
    @Test
    fun concurrentPushesAndClearsLeaveTheLatestRowsPublished() {
        repeat(50) { round ->
            val model = LiveStepsFeedModel(maxRows = 3)
            val start = CountDownLatch(1)
            val workers = (0 until 8).map { worker ->
                thread {
                    start.await()
                    repeat(200) { step ->
                        if (step % 37 == 0) model.clear() else model.push("r$round w$worker s$step")
                    }
                }
            }
            start.countDown()
            workers.forEach(Thread::join)
            model.push("final")
            val texts = model.rows.value.filterNot { it.exiting }.map { it.text }
            assertEquals("final", texts.last())
            assertEquals(texts.size, texts.distinct().size)
        }
    }
}
