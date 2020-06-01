package io.spinnaker.spinrel

import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThan
import strikt.assertions.isLessThan

class VersionNumberTest {

    @Test
    fun `test parse`() {
        expectThat(VersionNumber.parse("1.2.3")).isEqualTo(VersionNumber(1, 2, 3))
    }

    @Test
    fun `test parse all zeroes`() {
        expectThat(VersionNumber.parse("0.0.0")).isEqualTo(VersionNumber(0, 0, 0))
    }

    @Test
    fun `test parse mostly zeroes`() {
        expectThat(VersionNumber.parse("0.1.0")).isEqualTo(VersionNumber(0, 1, 0))
    }

    @Test
    fun `testparse larger versions`() {
        expectThat(VersionNumber.parse("999.888.777777")).isEqualTo(VersionNumber(999, 888, 777777))
    }

    @Test
    fun `test toString`() {
        expectThat(VersionNumber(1, 2, 3).toString()).isEqualTo("1.2.3")
    }

    @Test
    fun `test toString with all zeroes`() {
        expectThat(VersionNumber(0, 0, 0).toString()).isEqualTo("0.0.0")
    }

    @Test
    fun `test toString with mostly zeroes`() {
        expectThat(VersionNumber(0, 1, 0).toString()).isEqualTo("0.1.0")
    }

    @Test
    fun `test toString with larger versions`() {
        expectThat(VersionNumber(999, 888, 777777).toString()).isEqualTo("999.888.777777")
    }

    @Test
    fun `test compareTo`() {
        expectThat(VersionNumber(0, 0, 0).compareTo(VersionNumber(0, 0, 0))).isEqualTo(0)
        expectThat(VersionNumber(0, 1, 0).compareTo(VersionNumber(0, 1, 0))).isEqualTo(0)
        expectThat(VersionNumber(9, 8, 7).compareTo(VersionNumber(9, 8, 7))).isEqualTo(0)

        expectThat(VersionNumber(4, 5, 6)) {
            isLessThan(VersionNumber(5, 0, 0))
            isLessThan(VersionNumber(4, 6, 0))
            isLessThan(VersionNumber(4, 5, 7))
            isGreaterThan(VersionNumber(4, 5, 5))
            isGreaterThan(VersionNumber(4, 4, 0))
            isGreaterThan(VersionNumber(3, 0, 0))
            isGreaterThan(VersionNumber(0, 0, 0))
        }
    }
}
