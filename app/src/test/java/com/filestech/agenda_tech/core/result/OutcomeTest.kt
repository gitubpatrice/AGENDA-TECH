package com.filestech.agenda_tech.core.result

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OutcomeTest {

    @Test
    fun `map transforms a success value`() {
        val result = Outcome.Success(2).map { it * 3 }
        assertThat(result).isEqualTo(Outcome.Success(6))
    }

    @Test
    fun `map leaves a failure untouched`() {
        val failure: Outcome<Int> = Outcome.Failure(AppError.Validation("bad"))
        val result = failure.map { it * 3 }
        assertThat(result).isSameInstanceAs(failure)
    }

    @Test
    fun `flatMap chains successes`() {
        val result = Outcome.Success(4).flatMap { Outcome.Success(it + 1) }
        assertThat(result).isEqualTo(Outcome.Success(5))
    }

    @Test
    fun `onSuccess and onFailure fire on the matching branch only`() {
        var seenValue: Int? = null
        var seenError: AppError? = null

        Outcome.Success(7)
            .onSuccess { seenValue = it }
            .onFailure { seenError = it }

        assertThat(seenValue).isEqualTo(7)
        assertThat(seenError).isNull()
    }

    @Test
    fun `runCatchingOutcome wraps a thrown exception as a mapped failure`() {
        val result = runCatchingOutcome(
            block = { error("boom") },
            errorMapper = { AppError.Unknown(it) },
        )
        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.Unknown::class.java)
    }
}
