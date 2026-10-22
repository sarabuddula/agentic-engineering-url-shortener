package com.agenticengineering.urlshortener.orchestration.engine;

import com.agenticengineering.urlshortener.orchestration.persistence.StaleStateException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.TransientDataAccessException;

/**
 * Failure classification (data-model "Failure classification"; FR-025). Executors state the class in the reason
 * ("transient: ..." / "permanent: ..."); thrown exceptions are classified by type. Anything else is permanent: an
 * unknown failure is never retried.
 */
public final class FailureClassifier {

    public enum Kind { TRANSIENT, PERMANENT }

    private FailureClassifier() {
    }

    public static Kind classify(String reason) {
        return reason != null && reason.startsWith("transient:") ? Kind.TRANSIENT : Kind.PERMANENT;
    }

    /** Storage timeouts and optimistic-lock conflicts are transient; everything else, including bugs, is permanent. */
    public static Kind classify(Throwable error) {
        return error instanceof TransientDataAccessException || error instanceof StaleStateException
                || error instanceof SocketTimeoutException || error instanceof TimeoutException ? Kind.TRANSIENT : Kind.PERMANENT;
    }
}
