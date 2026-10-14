package com.leap.leaplaughlove.marketdata.common;

import com.leap.leaplaughlove.common.web.BaseGlobalExceptionHandler;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.io.IOException;

/**
 * Translates exceptions raised anywhere in the market data service into a consistent
 * JSON error body.
 */
@RestControllerAdvice
public class MarketDataGlobalExceptionHandler extends BaseGlobalExceptionHandler {

    private static final DisconnectedClientHelper DISCONNECTED_CLIENTS =
            new DisconnectedClientHelper(MarketDataGlobalExceptionHandler.class.getName());

    /**
     * Quietly drops a write that failed because the client went away - routine for the price
     * stream, whose subscribers disconnect whenever a tab closes, the frontend reconnects with a
     * new symbol set, or this service shuts down. Unhandled, each one reached the container and
     * was logged as an ERROR with a full stack trace. Any other I/O failure is rethrown.
     * @param ex the exception raised while writing the response
     * @throws Exception the same exception, if it wasn't a client disconnect
     */
    @ExceptionHandler({IOException.class, AsyncRequestNotUsableException.class})
    public void handleClientDisconnect(Exception ex) throws Exception {
        if (!DISCONNECTED_CLIENTS.checkAndLogClientDisconnectedException(ex)) {
            throw ex;
        }
    }
}
