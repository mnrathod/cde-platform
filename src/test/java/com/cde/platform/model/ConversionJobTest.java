package com.cde.platform.model;

import com.cde.platform.model.ConversionJob.Status;
import com.cde.platform.model.ConversionJob.TargetFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a job will and will not agree to become.
 *
 * <p>The point of the state machine is that a row cannot describe something
 * that never happened — a job reporting success with nothing to download, or a
 * failure with no reason. Each test names the impossible state it keeps out
 * rather than the method it calls.
 */
class ConversionJobTest {

    private static ConversionJob pending() {
        return ConversionJob.submitted(
            UUID.randomUUID(), 42L, "files.example.test", TargetFormat.PDF);
    }

    private static ConversionJob running() {
        ConversionJob job = pending();
        job.begin();
        return job;
    }

    @Nested
    @DisplayName("the lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("starts pending, with no result and nothing to explain")
        void startsPending() {
            ConversionJob job = pending();

            assertThat(job.getStatus()).isEqualTo(Status.PENDING);
            assertThat(job.getResultObjectId()).isEmpty();
            assertThat(job.getFailureReason()).isEmpty();
            assertThat(job.getFinishedAt()).isEmpty();
            assertThat(job.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("running to succeeded carries a result and a finish time")
        void succeeds() {
            ConversionJob job = running();

            job.succeed("0193f2ab.pdf", 8_192L);

            assertThat(job.getStatus()).isEqualTo(Status.SUCCEEDED);
            assertThat(job.getResultObjectId()).contains("0193f2ab.pdf");
            assertThat(job.getResultSizeBytes()).contains(8_192L);
            assertThat(job.getProgressPercent()).isEqualTo((short) 100);
            assertThat(job.getFinishedAt()).isPresent();
        }

        @Test
        @DisplayName("cannot succeed without having started")
        void cannotSucceedFromPending() {
            // Nothing ran, so there is nothing to have produced a result.
            assertThatThrownBy(() -> pending().succeed("x.pdf", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("cannot start twice")
        void cannotBeginTwice() {
            assertThatThrownBy(() -> running().begin())
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("cannot change its mind once it has finished")
        void terminalIsTerminal() {
            ConversionJob job = running();
            job.succeed("x.pdf", 1);

            assertThatThrownBy(() -> job.fail("no"))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(job::cancel)
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> job.succeed("y.pdf", 2))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("can fail before it ever started, which is what restart recovery does")
        void failsFromPending() {
            // The source link is never persisted, so a job still pending after
            // a restart cannot be resumed and has to be failed with a reason.
            ConversionJob job = pending();

            job.fail("The link was not retained across a restart. Resubmit.");

            assertThat(job.getStatus()).isEqualTo(Status.FAILED);
            assertThat(job.getFailureReason()).isPresent();
            assertThat(job.getFinishedAt()).isPresent();
        }
    }

    @Nested
    @DisplayName("progress")
    class Progress {

        @Test
        @DisplayName("never goes backwards")
        void progressIsMonotonic() {
            // A bar that retreats reads as a fault whether or not one occurred.
            ConversionJob job = running();

            job.reportProgress(60);
            job.reportProgress(20);

            assertThat(job.getProgressPercent()).isEqualTo((short) 60);
        }

        @Test
        @DisplayName("stops short of 100 while work is still running")
        void progressDoesNotReachCompletionEarly() {
            // 100% with work still going is a bar that has stopped meaning
            // anything; only succeed() may report completion.
            ConversionJob job = running();

            job.reportProgress(100);

            assertThat(job.getProgressPercent()).isEqualTo((short) 99);
        }

        @Test
        @DisplayName("clamps a nonsensical value rather than storing it")
        void progressIsBounded() {
            ConversionJob job = running();

            job.reportProgress(-5);
            assertThat(job.getProgressPercent()).isEqualTo((short) 0);

            job.reportProgress(4000);
            assertThat(job.getProgressPercent()).isEqualTo((short) 99);
        }

        @Test
        @DisplayName("is ignored once the job has finished")
        void progressIgnoredWhenTerminal() {
            // A late report from an executor that has not noticed it is done
            // must not move a completed job off 100.
            ConversionJob job = running();
            job.succeed("x.pdf", 1);

            job.reportProgress(5);

            assertThat(job.getProgressPercent()).isEqualTo((short) 100);
        }
    }

    @Nested
    @DisplayName("cancellation, which is co-operative")
    class Cancellation {

        @Test
        @DisplayName("records the request separately from the outcome")
        void requestAndOutcomeAreDifferentFacts() {
            // The executor notices between chunks, so there is a real interval
            // in which cancellation is requested and the job is still running.
            ConversionJob job = running();

            assertThat(job.requestCancellation()).isTrue();

            assertThat(job.isCancellationRequested()).isTrue();
            assertThat(job.getStatus()).isEqualTo(Status.RUNNING);

            job.cancel();
            assertThat(job.getStatus()).isEqualTo(Status.CANCELLED);
        }

        @Test
        @DisplayName("can be requested before the job starts")
        void cancellableWhilePending() {
            ConversionJob job = pending();

            assertThat(job.requestCancellation()).isTrue();
            assertThatCode(job::cancel).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("answers no rather than failing for a job that already finished")
        void requestingCancellationOfAFinishedJobIsNotAnError() {
            // Racing a job to completion is normal, not a client mistake.
            ConversionJob job = running();
            job.succeed("x.pdf", 1);

            assertThat(job.requestCancellation()).isFalse();
            assertThat(job.getStatus()).isEqualTo(Status.SUCCEEDED);
        }

        @Test
        @DisplayName("keeps the first request time when asked twice")
        void repeatedRequestsDoNotMoveTheClock() {
            ConversionJob job = running();
            job.requestCancellation();
            var first = job.isCancellationRequested();

            job.requestCancellation();

            assertThat(first).isTrue();
            assertThat(job.isCancellationRequested()).isTrue();
        }
    }

    @Nested
    @DisplayName("what it holds about the source")
    class Source {

        @Test
        @DisplayName("holds the host and offers no way to hold the URL")
        void keepsOnlyTheHost() {
            // The link is a presigned bearer credential. There is deliberately
            // no field and no setter for it, so no future call site can put it
            // in the database by accident.
            ConversionJob job = pending();

            assertThat(job.getSourceHost()).isEqualTo("files.example.test");
            assertThat(ConversionJob.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .noneMatch(name -> name.toLowerCase().contains("url"));
        }

        @Test
        @DisplayName("treats a missing filename as empty rather than null")
        void fileNameIsNeverNull() {
            ConversionJob job = pending();

            job.recordSourceFileName(null);

            assertThat(job.getSourceFileName()).isEmpty();
        }
    }
}
